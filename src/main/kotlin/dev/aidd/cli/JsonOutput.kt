package dev.aidd.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Path

object JsonOutput {
    val mapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)

    fun write(path: Path, value: Any) {
        val content = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n"
        SafePaths.writeText(path, content)
    }

    fun writeNode(path: Path, value: JsonNode) = write(path, value)
}
