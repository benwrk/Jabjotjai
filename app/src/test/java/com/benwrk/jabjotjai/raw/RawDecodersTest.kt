package com.benwrk.jabjotjai.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RawDecodersTest {

    @Test
    fun parsesMajorDotMinorAndNothingElse() {
        assertEquals(RawSchema(1, 0), RawSchema.parse("1.0"))
        assertEquals(RawSchema(1, 12), RawSchema.parse("1.12"))
        assertEquals(RawSchema(10, 0), RawSchema.parse("10.0"))
        for (text in listOf("", "1", "1.", ".1", "1.0.0", "v1.0", " 1.0", "1.0 ", "01.0", "1.01", "a.b", "-1.0")) {
            assertNull("<$text> should not parse", RawSchema.parse(text))
        }
    }

    @Test
    fun theCurrentSchemaIsTheOneTheWriterStamps() {
        assertEquals(RAW_SCHEMA, RawSchema.CURRENT.toString())
        assertEquals(RawSchema.CURRENT, RawDecoders.schemaOf(RawCodec.encode(Samples.header)))
    }

    @Test
    fun readsEveryMinorOfAKnownMajorAndRefusesOtherMajors() {
        assertNotNull(RawDecoders.forSchema(RawSchema(1, 0)))
        assertNotNull(RawDecoders.forSchema(RawSchema(1, 7)))
        assertNull(RawDecoders.forSchema(RawSchema(0, 9)))
        assertNull(RawDecoders.forSchema(RawSchema(2, 0)))
    }

    @Test
    fun readsTheSchemaWithoutDecodingTheRestOfTheHeader() {
        // A header from a future major may have changed every other field; its schema is still readable.
        assertEquals(RawSchema(2, 3), RawDecoders.schemaOf("""{"type":"header","schema":"2.3","renamed":true}"""))
    }

    @Test
    fun findsNoSchemaInALineThatIsNotAWellFormedHeader() {
        val notHeaders = listOf(
            "",
            "not json",
            """{"type":"ecg","schema":"1.0"}""",
            """{"type":"header"}""",
            """{"type":"header","schema":1.0}""",
            """{"type":"header","schema":"one"}""",
        )
        for (line in notHeaders) assertNull("<$line>", RawDecoders.schemaOf(line))
    }

    @Test
    fun forHeaderLineRefusesWhatItCannotRead() {
        assertThrows(UnreadableRawFileException::class.java) {
            RawDecoders.forHeaderLine("""{"type":"header","schema":"2.0"}""", "test file")
        }
        assertThrows(UnreadableRawFileException::class.java) {
            RawDecoders.forHeaderLine(RawCodec.encode(Samples.ecg), "test file")
        }
    }
}
