package com.nextyearrobotics.seniorcare.protocol

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** 계약 fixture 전부가 envelope로 읽히고 다시 직렬화해도 의미가 같은지 확인한다. fixture는 contracts/에서 sync한 복사본. */
class EnvelopeFixturesTest {
    private val fixturesDir: File = listOf("../contracts/phone-bridge/v1/fixtures", "contracts/phone-bridge/v1/fixtures")
        .map(::File).first { it.isDirectory }

    @Test
    fun allFixturesDecodeAndRoundTrip() {
        val files = fixturesDir.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
        assertTrue(files.size >= 26, "expected >= 26 fixtures, got ${files.size}")
        for (f in files) {
            val text = f.readText()
            val env = Protocol.decode(text)
            assertEquals(1, env.version, f.name)
            val again = Protocol.decode(Protocol.encode(env))
            assertEquals(env, again, f.name)
        }
    }

    @Test
    fun replyMessagesReferenceRequests() {
        val byName = fixturesDir.listFiles()!!.associate { it.nameWithoutExtension to Protocol.decode(it.readText()) }
        assertEquals(byName.getValue("hello").id, byName.getValue("hello-ack").replyTo)
        assertEquals(byName.getValue("trip-start").id, byName.getValue("trip-start-ack").replyTo)
        assertEquals(byName.getValue("sos-request").id, byName.getValue("sos-ack").replyTo)
    }
}
