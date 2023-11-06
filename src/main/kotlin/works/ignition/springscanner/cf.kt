package works.ignition.springscanner

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import org.springframework.aot.hint.ExecutableMode
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.ImportRuntimeHints
import org.springframework.stereotype.Service
import java.io.File

@Service
class CfService {
    @Autowired lateinit var scannerService: ScannerService

    @Value("\${dropletsTmpFolder}") lateinit var dropletsTmpFolder: String
    @Value("\${downloadDroplets}") lateinit var downloadDroplets: String
    @Value("\${cleanupDroplets}") lateinit var cleanupDroplets: String
    @Value("\${scannedAppsInParallel}") lateinit var scannedAppsInParallel: String

    val logger = LoggerFactory.getLogger(CfService::class.java)

    suspend fun scanAppsInParallel(): List<Map<String, *>> {
        val semaphore = Semaphore(scannedAppsInParallel.toInt())
        val apps = getApps()
        return coroutineScope {
            val deferredResults = apps.map {
                // To ensure that the tasks run in parallel using threads
                // designed for tasks that perform I/O operations
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        getAppDetails(it.name, it.guid)
                    }
                }
            }
            deferredResults.awaitAll()
        }
    }

    private fun getApps(): List<CfAppsResponse.Resource> {
        return cfCurl("/v3/apps", CfAppsResponse::class.java) { res: List<CfAppsResponse> ->
            res.flatMap { it.resources }
        }
    }

    private suspend fun getAppDetails(appName: String, guid: String): Map<String, *> {
        val procs = try {
            logger.info("Extracting app processes info for app = $appName")
            getAppProcesses(guid)
        } catch(e: Exception) {
            logger.error("Error extracting app processes info for app = $appName", e)
            null
        }
        val stats = try {
            logger.info("Extracting app process stats info for app = $appName")
            getAppProcessStats(guid)
        } catch(e: Exception) {
            logger.error("Error extracting app process stats info for app = $appName", e)
            null
        }
        val jdkEnv = try {
            logger.info("Extracting jdk env info for app = $appName")
            getAppJdkEnv(guid)
        } catch(e: Exception) {
            logger.error("Error extracting app jdk env info for app = $appName", e)
            null
        }
        val droplet = try {
            logger.info("Extracting app droplet info for app = $appName")
            getAppDroplet(guid)
        } catch(e: Exception) {
            logger.error("Error extracting app droplet info for app = $appName", e)
            null
        }
        var scanResults: List<FileScanner.Result>? = null
        if (droplet?.guid != null && droplet.buildpacks != null && droplet.buildpacks.any { it.name?.contains("java") ?: false }) {
            scanResults = try {
                logger.info("Scanning java app = $appName")
                getScanResults("${droplet!!.guid}", appName)
            } catch(e: Exception) {
                logger.error("Error scanning java app = $appName", e)
                null
            }
        }
        return mapOf("appGuid" to guid, "processes" to procs, "stats" to stats, "jdkEnv" to jdkEnv, "droplet" to droplet, "scanResults" to scanResults)
    }

    private fun getAppProcesses(guid: String) =
        cfCurl("/v3/apps/$guid/processes", CfAppProcessesResponse::class.java) { res: List<CfAppProcessesResponse> ->
            res.filter { it.resources != null }.flatMap { it.resources!! }
        }

    private fun getAppProcessStats(guid: String) =
        cfCurl(
            "/v3/processes/$guid/stats",
            CfAppProcessStatsResponse::class.java
        ) { res: List<CfAppProcessStatsResponse> ->
            res.filter { it.resources != null }.flatMap { it.resources!! }
        }

    private fun getAppJdkEnv(guid: String) =
        cfCurl(
            "/v3/apps/$guid/env",
            CfAppEnvsResponse::class.java
        ) { res: List<CfAppEnvsResponse> ->
            val filteredEnvVariables = res.filter {
                it.environmentVariables !=null && it.environmentVariables.contains("JBP_CONFIG_OPEN_JDK_JRE")
            }
            if (filteredEnvVariables.isNotEmpty())
                filteredEnvVariables[0].environmentVariables!!["JBP_CONFIG_OPEN_JDK_JRE"]
            else
                null
        }

    private fun getAppDroplet(guid: String) =
        cfCurl(
            "/v3/apps/$guid/droplets/current",
            CfAppDropletsResponse::class.java
        ) { res: List<CfAppDropletsResponse> ->
            res[0]
        }

    private fun getScanResults(dropletGuid: String, appName: String): List<FileScanner.Result>? {
        val dropletFileName = "droplet_${dropletGuid}"
        if (downloadDroplets.toBoolean()) {
            downloadDroplet(appName, dropletFileName)
            extractAppFolderFromDroplet(dropletFileName)
        }
        val results = scannerService.scanAppFolder("${dropletsTmpFolder}/${dropletFileName}/app")
        if (cleanupDroplets.toBoolean()) {
            removeDroplet(dropletFileName)
            removeDropletFolder(dropletFileName)
        }
        return results
    }

    private fun downloadDroplet(appName: String, dropletFileName: String) {
        val process = executeCommand("cf", "download-droplet", appName, "--path", "${dropletsTmpFolder}/${dropletFileName}.tgz")
        val exitCode = process.waitFor()
        val cfOutput = process.inputReader().readLines()
        logger.debug("CF download droplet = $cfOutput")
        if (exitCode != 0) {
            throw RuntimeException("Error downloading droplet guid=$appName")
        }
    }

    private fun extractAppFolderFromDroplet(dropletFileName: String) {
        val dropletFolderPath = "${dropletsTmpFolder}/${dropletFileName}"
        if (executeCommand("rm", "-rf", dropletFolderPath).waitFor() != 0)
            throw RuntimeException("Error removing $dropletFolderPath")
        if (executeCommand("mkdir", dropletFolderPath).waitFor() != 0)
            throw RuntimeException("Error creating folder $dropletFolderPath")
        if (executeCommand("tar", "xvfz", "${dropletFolderPath}.tgz", "-C", dropletFolderPath, "app").waitFor() != 0)
            throw RuntimeException("Error extracting app folder from ${dropletFolderPath}.tgz into $dropletFolderPath")
    }

    private fun removeDropletFolder(dropletFileName: String) {
        val dropletFolder = "${dropletsTmpFolder}/${dropletFileName}"
        if (executeCommand("rm", "-rf", dropletFolder).waitFor() != 0)
            throw RuntimeException("Error removing $dropletFolder")
    }

    private fun removeDroplet(dropletFileName: String) {
        val dropletPath = "${dropletsTmpFolder}/${dropletFileName}.tgz"
        if (executeCommand("rm", "-rf", dropletPath).waitFor() != 0)
            throw RuntimeException("Error removing $dropletPath")
    }

    private fun <T: CfResponse, O> cfCurl(url: String, clazz: Class<T>, adapter: (List<T>) -> O): O {
        val array = mutableListOf<T>()
        var nextPage: String? = url
        do {
            val process = executeCommand("cf", "curl", nextPage!!)
            val res = getMapper().readValue(process.inputStream, clazz)
            nextPage = res.pagination?.next
            array.add(res)
        } while(nextPage!=null)
        return adapter(array)
    }

    private fun executeCommand(vararg command: String, directory: File = File(System.getProperty("user.home"))): Process {
        val builder = ProcessBuilder()
        builder.command(command.asList())
        builder.directory(directory)
        return builder.start()
    }

    private fun getMapper(): ObjectMapper {
        val mapper = ObjectMapper()
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper
    }
}

abstract class CfResponse(val pagination: Pagination?) {
    data class Pagination(@JsonProperty("pagination") val next: String?)
}

@JsonInclude(JsonInclude.Include.NON_NULL)
class CfAppsResponse @JsonCreator constructor(
    @JsonProperty("resources") val resources: List<Resource>,
    @JsonProperty("pagination") pagination: Pagination
): CfResponse(pagination) {
    data class Resource(
        @JsonProperty("guid") val guid: String,
        @JsonProperty("name") val name: String
    )
}

class CfAppProcessesResponse @JsonCreator constructor(
    @JsonProperty("resources") val resources: List<Resource>? = null,
    @JsonProperty("pagination") pagination: Pagination? = null
): CfResponse(pagination) {
    data class Resource(
        @JsonProperty("instances") val instances: Integer? = null,
        @JsonProperty("disk_in_mb") val diskInMb: Integer? = null,
        @JsonProperty("memory_in_mb") val memoryInMb: Integer? = null
    )
}

class CfAppProcessStatsResponse @JsonCreator constructor(
    @JsonProperty("resources") val resources: List<Resource>? = null,
    @JsonProperty("pagination") pagination: Pagination? = null
): CfResponse(pagination) {
    data class Resource(
        @JsonProperty("state") val state: String? = null,
        @JsonProperty("usage") val usage: Usage? = null
    )
    data class Usage(
        @JsonProperty("cpu") val cpu: Float? = null,
        @JsonProperty("mem") val mem: Integer? = null,
        @JsonProperty("disk") val disk: Integer? = null,
    )
}

class CfAppEnvsResponse @JsonCreator constructor(
    @JsonProperty("environment_variables") val environmentVariables: Map<String, String>? = null,
    @JsonProperty("pagination") pagination: Pagination? = null
): CfResponse(pagination)

class CfAppDropletsResponse @JsonCreator constructor(
    @JsonProperty("guid") val guid: String? = null,
    @JsonProperty("buildpacks") val buildpacks: List<Buildpack>? = null,
    @JsonProperty("process_types") val processTypes: ProcessTypes? = null,
    @JsonProperty("pagination") pagination: Pagination? = null
): CfResponse(pagination) {
    data class Buildpack(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("version") val version: String? = null
    )
    data class ProcessTypes(
        @JsonProperty("task") val task: String? = null,
        @JsonProperty("web") val web: String? = null,
    )
}

/**
 * This class is only needed when using graalvm native executables
 */
@Configuration
@ImportRuntimeHints(ConfigForSpringNative.Companion.AppRuntimeHints::class)
class ConfigForSpringNative {
    companion object {
        class AppRuntimeHints : RuntimeHintsRegistrar {
            override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
                registerConstructors(hints, CfResponse.Pagination::class.java)
                registerConstructors(hints, CfAppsResponse::class.java)
                registerConstructors(hints, CfAppsResponse.Resource::class.java)
                registerConstructors(hints, CfAppProcessesResponse::class.java)
                registerConstructors(hints, CfAppProcessesResponse.Resource::class.java)
                registerConstructors(hints, CfAppProcessStatsResponse::class.java)
                registerConstructors(hints, CfAppProcessStatsResponse.Resource::class.java)
                registerConstructors(hints, CfAppProcessStatsResponse.Usage::class.java)
                registerConstructors(hints, CfAppEnvsResponse::class.java)
                registerConstructors(hints, CfAppDropletsResponse::class.java)
                registerConstructors(hints, CfAppDropletsResponse.Buildpack::class.java)
                registerConstructors(hints, CfAppDropletsResponse.ProcessTypes::class.java)
            }

            private fun registerConstructors(hints: RuntimeHints, clazz: Class<*>) {
                for (constructor in clazz.constructors) {
                    hints.reflection().registerConstructor(constructor, ExecutableMode.INVOKE)
                }
            }
        }
    }
}
