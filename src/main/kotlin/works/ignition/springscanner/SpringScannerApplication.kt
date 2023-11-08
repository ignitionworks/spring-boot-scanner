package works.ignition.springscanner

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import kotlin.system.exitProcess


@SpringBootApplication
class SpringScannerApplication : CommandLineRunner {
	@Autowired
	private lateinit var cfService: CfService
	@Autowired
	private lateinit var printService: PrintService

	val logger = LoggerFactory.getLogger(SpringScannerApplication::class.java)

	override fun run(vararg args: String?) {
		if (args.size != 1) {
			logger.error("You need to pass a valid space guid")
			exitProcess(-1)
		}
		val spaceGuid = args[0]!!
		runBlocking {
			val javaAppDetails = cfService.scanAppsInParallel(spaceGuid)
			printService.printJson(javaAppDetails)
		}
	}
}

fun main(args: Array<String>) {
	runApplication<SpringScannerApplication>(*args)
}
