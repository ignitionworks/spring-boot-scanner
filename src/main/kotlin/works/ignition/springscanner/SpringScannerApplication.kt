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
		runBlocking {
			val report = cfService.scanAppsInParallel()
			printService.printJson(report)
		}
	}
}

fun main(args: Array<String>) {
	runApplication<SpringScannerApplication>(*args)
}
