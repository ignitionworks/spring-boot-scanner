package works.ignition.springscanner

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.slf4j.LoggerFactory
import org.springframework.aot.hint.ExecutableMode
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.ImportRuntimeHints
import org.springframework.stereotype.Service
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths


@Service
class CfService {
    @Autowired lateinit var scannerService: ScannerService

    @Value("\${dropletsTmpFolder}") lateinit var dropletsTmpFolder: String
    @Value("\${downloadDroplets}") lateinit var downloadDroplets: String
    @Value("\${cleanupDroplets}") lateinit var cleanupDroplets: String
    @Value("\${scannedAppsInParallel}") lateinit var scannedAppsInParallel: String

    @Value("#{systemProperties['user.home']}") lateinit var homeDir: String

    val logger = LoggerFactory.getLogger(CfService::class.java)

    suspend fun scanAppsInParallel(): Any {
        val semaphore = Semaphore(scannedAppsInParallel.toInt())
        val config = getCfConfig()
        val apps = getApps(config.spaceFields.guid)
        return coroutineScope {
            val deferredResults = apps.map {
                // Ensures that tasks run in parallel using threads
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        getAppDetails(it)
                    }
                }
            }
            val appDetails = deferredResults.awaitAll()
            mapOf(
                "config" to config,
                "appDetails" to appDetails
            )
        }
    }

    private fun getCfConfig() =
        getMapper().readValue(File("${homeDir}/.cf/config.json"), CfConfig::class.java)

    private fun getApps(spaceGuid: String): List<CfAppsResponse.Resource> {
        return cfCurl("/v3/apps?order_by=name&space_guids=${spaceGuid}", CfAppsResponse::class.java) { res: List<CfAppsResponse> ->
            res.filter{ it.resources != null }.flatMap { it.resources!! }
        }
    }

    private suspend fun getAppDetails(app: CfAppsResponse.Resource): Map<String, *> {
        val procs = try {
            logger.info("Extracting app processes info for app = $app.name")
            getAppProcesses(app.guid)
        } catch(e: Exception) {
            logger.error("Error extracting app processes info for app = $app.name", e)
            null
        }
        val stats = try {
            logger.info("Extracting app process stats info for app = $app.name")
            getAppProcessStats(app.guid)
        } catch(e: Exception) {
            logger.error("Error extracting app process stats info for app = $app.name", e)
            null
        }
        val jdkEnv = try {
            logger.info("Extracting jdk env info for app = $app.name")
            getAppJdkEnv(app.guid)
        } catch(e: Exception) {
            logger.error("Error extracting app jdk env info for app = $app.name", e)
            null
        }
        val droplet = try {
            logger.info("Extracting app droplet info for app = $app.name")
            getAppDroplet(app.guid)
        } catch(e: Exception) {
            logger.error("Error extracting app droplet info for app = $app.name", e)
            null
        }
        var scanResults: FileScanner.Response? = null
        if (droplet?.guid != null && droplet.buildpacks != null && droplet.buildpacks.any { it.name?.contains("java") ?: false }) {
            scanResults = try {
                logger.info("Scanning java app = $app.name")
                getScanResults("${droplet!!.guid}", app.name)
            } catch(e: Exception) {
                logger.error("Error scanning java app = $app.name", e)
                null
            }
        }
        return mapOf(
            "app" to app,
            "processes" to procs,
            "stats" to stats,
            "jdkEnv" to jdkEnv,
            "droplet" to droplet,
            "scanResults" to scanResults
        )
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
                it.environmentVariables != null && it.environmentVariables.contains("JBP_CONFIG_OPEN_JDK_JRE")
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

    private fun getScanResults(dropletGuid: String, appName: String): FileScanner.Response? {
        val dropletFileName = "droplet_${dropletGuid}"
        if (downloadDroplets.toBoolean()) {
            downloadDroplet(appName, dropletFileName)
            extractAppFolderFromDroplet(dropletFileName)
        }
        val results = scannerService.scanAppFolder("${dropletsTmpFolder}/${dropletFileName}/app")
        if (cleanupDroplets.toBoolean()) {
            removeFile("${dropletsTmpFolder}/${dropletFileName}.tgz") // removing droplet tarball
            removeFile("${dropletsTmpFolder}/${dropletFileName}") // removing droplet folder
        }
        return results
    }

    private fun downloadDroplet(appName: String, dropletFileName: String) {
        val process = executeCommand("cf", "download-droplet", appName, "--path", "${dropletsTmpFolder}/${dropletFileName}.tgz")
        val exitCode = process.waitFor()
        val cfOutput = process.inputReader().readLines()
        logger.debug("CF download droplet = $cfOutput")
        if (exitCode != 0) {
            throw RuntimeException("Error downloading droplet appName=$appName")
        }
    }

    private fun extractAppFolderFromDroplet(dropletFileName: String) {
        val dropletFolderPath = "${dropletsTmpFolder}/${dropletFileName}"
        removeFile(dropletFolderPath)
        createFolder(dropletFolderPath)
        extractAppFolder(dropletFolderPath)
    }

    private fun extractAppFolder(dropletFolderPath: String) {
        try {
            extractSpecificDirectory("${dropletFolderPath}.tgz", "./app", dropletFolderPath)
        } catch (e: Exception) {
            throw RuntimeException("Error extracting file ${dropletFolderPath}.tgz into folder $dropletFolderPath", e)
        }
    }

    fun extractSpecificDirectory(tarGzPath: String, directoryToExtract: String, destinationFolder: String) {
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(FileInputStream(tarGzPath)))).use { tarInput ->
            var tarEntry = tarInput.nextTarEntry
            while (tarEntry != null) {
                if (tarEntry.name.startsWith(directoryToExtract)) {
                    val relativePath = tarEntry.name.removePrefix(directoryToExtract)
                    val destPath = Paths.get(destinationFolder, directoryToExtract, relativePath)

                    if (tarEntry.isDirectory) {
                        Files.createDirectories(destPath)
                    } else {
                        Files.createDirectories(destPath.parent)
                        Files.copy(tarInput, destPath)
                    }
                }
                tarEntry = tarInput.nextTarEntry
            }
        }
    }

    private fun createFolder(dropletFolderPath: String) {
        try {
            val result = File(dropletFolderPath).mkdir()
            if (!result) throw RuntimeException()
        } catch (e: Exception) {
            throw RuntimeException("Error creating folder $dropletFolderPath", e)
        }
    }

    private fun removeFile(filePath: String) {
        try {
            val file = File(filePath)
            if (!file.exists())
                return
            Files.walk(file.toPath())
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach {it.delete() }
        } catch (e: Exception) {
            throw RuntimeException("Error removing $filePath", e)
        }
    }

    private fun <T: CfResponse, O> cfCurl(url: String, clazz: Class<T>, adapter: (List<T>) -> O): O {
        val array = mutableListOf<T>()
        var nextPage: String? = url
        do {
            val res = withExponentialBackoff {
                val process = executeCommand("cf", "curl", nextPage!!.replace("\u0026", "&"))
                getMapper().readValue(process.inputStream, clazz)
            }
            nextPage = res.pagination?.next?.href
            array.add(res)
        } while(nextPage!=null)
        return adapter(array)
    }

    private fun <T: CfResponse> withExponentialBackoff(maxRetries: Int = 3, initialDelay: Long = 300L, action: () -> T): T {
        var retries = 0
        var delay = initialDelay

        while (true) {
            try {
                return action()
            } catch (e: RuntimeException) {
                retries++
                if (retries >= maxRetries) {
                    throw e
                }
                // We might to consider using coroutines.delay for better performance
                Thread.sleep(delay)
                delay *= 2
            }
        }
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

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CfConfig(
    @JsonProperty("OrganizationFields") val organizationFields: OrganizationFields,
    @JsonProperty("SpaceFields") val spaceFields: SpaceFields,
    @JsonProperty("Target") val target: String,
    @JsonProperty("APIVersion") val apiVersion: String
) {
    data class OrganizationFields(
        @JsonProperty("GUID") val guid: String,
        @JsonProperty("Name") val name: String
    )
    data class SpaceFields(
        @JsonProperty("GUID") val guid: String,
        @JsonProperty("Name") val name: String
    )
}

abstract class CfResponse(val pagination: Pagination?) {
    data class Pagination(@JsonProperty("next") val next: Page? = null)
    data class Page(@JsonProperty("href") val href: String)
}

@JsonInclude(JsonInclude.Include.NON_NULL)
class CfAppsResponse @JsonCreator constructor(
    @JsonProperty("resources") val resources: List<Resource>? = null,
    @JsonProperty("pagination") pagination: Pagination? = null
): CfResponse(pagination) {
    data class Resource(
        @JsonProperty("guid") val guid: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("created_at") val createdAt: String,
        @JsonProperty("updated_at") val updatedAt: String,
        @JsonProperty("state") val state: String,
    )
}

class CfAppProcessesResponse @JsonCreator constructor(
    @JsonProperty("resources") val resources: List<Resource>? = null,
    @JsonProperty("pagination") pagination: Pagination? = null
): CfResponse(pagination) {
    data class Resource(
        @JsonProperty("instances") val instances: String? = null,
        @JsonProperty("disk_in_mb") val diskInMb: String? = null,
        @JsonProperty("memory_in_mb") val memoryInMb: String? = null
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
        @JsonProperty("mem") val mem: String? = null,
        @JsonProperty("disk") val disk: String? = null,
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
