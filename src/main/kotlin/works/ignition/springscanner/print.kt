package works.ignition.springscanner

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service

@Service
class PrintService {
    fun printJson(report: Any) {
        val mapper = ObjectMapper()
        val json: String = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report)
        println(json)
    }
}