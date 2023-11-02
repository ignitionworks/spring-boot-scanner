package works.ignition.springscanner

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service

@Service
class PrintService {
    fun printJson(javaAppDetails: List<Map<String, *>>) {
        val mapper = ObjectMapper()
        val json: String = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(javaAppDetails)
        println(json)
    }
}