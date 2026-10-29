package io.github.aviato28.scopecreep.sim.airport

import java.io.InputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class UnsupportedSchemaException(message: String) : Exception(message)

object AirportLoader {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): Airport {
        val version = json.parseToJsonElement(text).jsonObject["schemaVersion"]?.jsonPrimitive?.int
        if (version != SCHEMA_VERSION) throw UnsupportedSchemaException("Unsupported airport schemaVersion $version (expected $SCHEMA_VERSION)")
        return json.decodeFromString(Airport.serializer(), text)
    }

    fun load(stream: InputStream): Airport = parse(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
}
