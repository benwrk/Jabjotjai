package com.benwrk.jabjotjai.raw

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What one line of a raw file turned out to be. A line is reported, never silently dropped. */
sealed interface RawLine {
    data class Record(val record: RawRecord) : RawLine

    /** Valid JSON whose `type` this build does not know, as a newer schema minor may write. */
    data class Unknown(val type: String) : RawLine

    /** Not a valid record of any known type. */
    data class Malformed(val text: String, val error: String) : RawLine
}

/** Converts between [RawRecord]s and the lines of a raw file. */
object RawCodec {

    private val json = Json {
        classDiscriminator = "type"
        // An empty list such as `open_gaps` is written as [] rather than left out.
        encodeDefaults = true
        // A field that does not apply is left out rather than written as null.
        explicitNulls = false
        // A field added by a newer schema minor is skipped.
        ignoreUnknownKeys = true
    }

    /** The `type` values of this schema, one per [RawRecord] subtype. */
    val knownTypes: Set<String> = setOf("header", "event", "ecg", "acc", "hr", "gap", "end")

    /** One line of JSON, without the trailing newline. */
    fun encode(record: RawRecord): String = json.encodeToString(RawRecord.serializer(), record)

    /** Decodes one line, without its trailing newline. */
    fun decode(line: String): RawLine {
        val element = try {
            json.parseToJsonElement(line)
        } catch (e: SerializationException) {
            return RawLine.Malformed(line, e.message ?: "not valid JSON")
        }
        val type = ((element as? JsonObject)?.get("type") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?: return RawLine.Malformed(line, "not an object with a string \"type\" field")
        if (type !in knownTypes) return RawLine.Unknown(type)
        return try {
            RawLine.Record(json.decodeFromJsonElement(RawRecord.serializer(), element))
        } catch (e: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException, and so is a failed `require`
            // in a record's init block, such as an ECG frame whose lists differ in length.
            RawLine.Malformed(line, e.message ?: "does not match type \"$type\"")
        }
    }
}
