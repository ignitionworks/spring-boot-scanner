package works.ignition.springscanner

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.DataInputStream
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.function.Consumer
import java.util.regex.Pattern
import kotlin.io.path.*


@Service
class ScannerService {
    fun scanAppFolder(appFolder: String): FileScanner.Response {
        val scannedFiles = mutableListOf<FileScanner.ScannedFile>()
        val results = mutableListOf<FileScanner.Result>()
        // TODO Avoid looping multiple times for improving performance
        // Currently keeping the algorithm for simplicity sake
        Files.walk(Paths.get(appFolder)).use { walk ->
            var classScanned = false
            walk.forEach {
                if (!it.isRegularFile() ||
                        it.pathString.contains(".java-buildpack") ||
                        it.name.endsWith(".cached") ||
                        it.name.endsWith(".etag") ||
                        it.name.endsWith(".last_modified")) {
                    return@forEach
                }
                scannedFiles.add(extractFileDetails(it))
                if (it.name.contains("MANIFEST.MF")) {
                    results.add(ManifestScanner(it.toFile()).scan())
                } else if (it.name.contains(Regex("^spring-boot-(\\d\\.\\d\\..*).jar$"))) {
                    results.add(SpringBootDependencyScanner(it.toFile()).scan())
                } else if (!classScanned && it.name.contains(".class")) {
                    results.add(ClassScanner(it.toFile()).scan())
                    classScanned = true
                }
            }
        }
        return FileScanner.Response(scannedFiles, results)
    }

    private fun extractFileDetails(path: Path): FileScanner.ScannedFile {
        val data = Files.readAllBytes(path)
        val hash = MessageDigest.getInstance("MD5").digest(data)
        val checksum: String = BigInteger(1, hash).toString(16)
        return FileScanner.ScannedFile(path.absolutePathString(), path.fileSize().toString(), checksum)
    }
}

abstract class FileScanner {
    data class Response(
        val scannedFiles: List<ScannedFile>,
        val results: List<Result>
    )

    data class Result(
        val fileName: String,
        val scannerType: String,
        val javaVersion: String?,
        val springBootVersion: String?
    )

    data class ScannedFile(
        val filePath: String,
        val length: String,
        val checksum: String
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