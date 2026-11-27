package com.benwrk.jabjotjai.raw

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException

/** A raw file schema version, `major.minor`. */
data class RawSchema(val major: Int, val minor: Int) {

    override fun toString(): String = "$major.$minor"

    companion object {
        // Declared before CURRENT: companion properties initialise in declaration order, and
        // CURRENT's initialiser calls parse, which reads FORMAT.
        private val FORMAT = Regex("""^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$""")

        /** The schema this build writes. */
        val CURRENT: RawSchema = requireNotNull(parse(RAW_SCHEMA)) { "RAW_SCHEMA is not major.minor" }

        /** Parses `"major.minor"`, or returns null for anything else. */
        fun parse(text: String): RawSchema? = FORMAT.matchEntire(text)?.let {
            RawSchema(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
    }
}

/** Thrown when a raw file cannot be read safely: no usable header, or a schema this build does not know. */
class UnreadableRawFileException(message: String) : IOException(message)

/**
 * Chooses how to read a raw file from the schema its header declares. Raw files are never
 * migrated on disk (decision `0006`): a file written in an older major is read by a decoder that
 * upgrades its records into the current model as it reads them.
 */
object RawDecoders {

    private val json = Json

    /**
     * The decoder for lines of a file written in [schema], yielding records in the current model,
     * or null if this build cannot read that major. Any minor of a known major is accepted, since
     * minor versions only add fields and record types, which a decoder skips or reports as
     * [RawLine.Unknown].
     */
    fun forSchema(schema: RawSchema): ((String) -> RawLine)? = when (schema.major) {
        1 -> RawCodec::decode
        else -> null
    }

    /**
     * The schema a header line declares, read without assuming any schema's decoder, because the
     * schema is what decides which decoder applies. Null if the line is not a header with a
     * well-formed `schema`.
     */
    fun schemaOf(headerLine: String): RawSchema? {
        val header = try {
            json.parseToJsonElement(headerLine) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return null
        if ((header["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content != "header") return null
        val schema = (header["schema"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return RawSchema.parse(schema)
    }

    /**
     * The decoder for a file whose first line is [headerLine].
     *
     * @throws UnreadableRawFileException if the line is not a header, or its schema is unknown here
     */
    fun forHeaderLine(headerLine: String, file: Any): (String) -> RawLine {
        val schema = schemaOf(headerLine)
            ?: throw UnreadableRawFileException("$file does not start with a header declaring a major.minor schema")
        return forSchema(schema)
            ?: throw UnreadableRawFileException("$file is schema $schema, which this build cannot read; it writes ${RawSchema.CURRENT}")
    }
}
