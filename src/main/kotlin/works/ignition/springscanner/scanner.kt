package works.ignition.springscanner

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.DataInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.function.Consumer
import java.util.regex.Pattern
import java.util.stream.Collectors

@Service
class ScannerService {
    fun scanAppFolder(appFolder: String): List<FileScanner.Result> {
        val results = mutableListOf<FileScanner.Result>()
        Files.walk(Paths.get(appFolder)).use { walk ->
            val files = walk.filter(Files::isRegularFile)
                .map { x -> x.toFile() }.collect(Collectors.toList())

            val manifestFiles = files.filter { it.name.contains("MANIFEST.MF") }
            if (manifestFiles.isNotEmpty()) {
                results.add(ManifestScanner(manifestFiles.first()).scan())
            }

            val springBootDependencies = files.filter {it.name.contains(Regex("^spring-boot-(\\d\\.\\d\\..*).jar$")) }
            if (springBootDependencies.isNotEmpty()) {
                results.add(SpringBootDependencyScanner(springBootDependencies.first()).scan())
            }

            val classFiles = files.filter { it.name.contains(".class") }
            if (classFiles.isNotEmpty()) {
                results.add(ClassScanner(classFiles.first()).scan())
            }
        }
        return results
    }
}

abstract class FileScanner {
    data class Result(
        val fileName: String,
        val scannerType: String,
        val javaVersion: String?,
        val springBootVersion: String?
    )

    protected var file: File

    constructor(file: File) {
        this.file = file
    }

    abstract fun scan(): Result?

    protected fun extractValue(line: String, regex: String): String? {
        val m = Pattern.compile(regex).matcher(line)
        return if (!m.matches()) null else m.group(1)
    }
}

class ManifestScanner : FileScanner {
    constructor(file: File) : super(file)

    override fun scan(): Result {
        var springBootVersion: String? = null
        var javaVersion: String? = null
        file.bufferedReader().use {
            it.lines().forEach(Consumer { line ->
                if (javaVersion != null && springBootVersion != null)
                    return@Consumer
                javaVersion = extractValue(line, "Build-Jdk.*: (.*)") ?: javaVersion
                springBootVersion = extractValue(line, "Spring-Boot-Version: (.*)") ?: springBootVersion
            })
        }
        return Result(file.name, this.javaClass.name, javaVersion, springBootVersion)
    }
}

class SpringBootDependencyScanner : FileScanner {
    constructor(file: File) : super(file)

    override fun scan(): Result {
        val springBootVersion = extractValue(file.name, "spring-boot-(\\d\\.\\d\\..*).jar")
        return Result(file.name, this.javaClass.name, null, springBootVersion)
    }
}

class ClassScanner : FileScanner {
    val logger = LoggerFactory.getLogger(ClassScanner::class.java)

    constructor(file: File) : super(file)

    override fun scan(): Result {
        val magicNumber = 44 // see: https://docs.oracle.com/javase/specs/jvms/se19/html/jvms-4.html#jvms-4.1
        val majorNumber = getClassMajorNumber()
        val javaVersion = if (majorNumber != null) (majorNumber - magicNumber) else null
        return Result(file.name, this.javaClass.name, javaVersion.toString(), null)
    }

    private fun getClassMajorNumber(): Int? {
        return DataInputStream(file.inputStream()).use {
            val magic = it.readInt()
            if (magic != -0x35014542) {
                logger.error("${file.name} is not a valid class!")
                return null
            }
            it.readUnsignedShort() // minor
            return it.readUnsignedShort().toInt() // major
        }
    }
}