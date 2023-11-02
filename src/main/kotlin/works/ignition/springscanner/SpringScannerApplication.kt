package works.ignition.springscanner

import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication


@SpringBootApplication
class SpringScannerApplication : CommandLineRunner {
	@Autowired
	private lateinit var cfService: CfService
	@Autowired
	private lateinit var printService: PrintService

	override fun run(vararg args: String?) {
		runBlocking {
			val javaAppDetails = cfService.scanAppsInParallel()
			printService.printJson(javaAppDetails)
		}
	}
}

fun main(args: Array<String>) {
	runApplication<SpringScannerApplication>(*args)
}
