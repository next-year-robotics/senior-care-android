package com.nextyearrobotics.seniorcare.network

import org.junit.Test
import kotlin.test.*

class CleartextPolicyTest {
    @Test fun privateAndLinkLocalOnlyInRelease() {
        listOf("10.0.0.2", "172.16.0.1", "172.31.255.254", "192.168.179.22", "169.254.1.1", "[fd00::1]", "[fe80::1]").forEach {
            assertEquals("ws://$it:8765/bridge/v1", Endpoint.validate("ws://$it:8765/bridge/v1", false))
        }
        listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "100.64.0.1", "0.0.0.0", "255.255.255.255", "224.0.0.1", "127.0.0.1", "localhost", "mobile-robot.local", "example.com", "[::1]", "[2001:4860::1]", "[::ffff:127.0.0.1]", "0177.0.0.1", "2130706433").forEach {
            assertFailsWith<IllegalArgumentException>(it) { Endpoint.validate("ws://$it:8765/bridge/v1", false) }
        }
    }
    @Test fun debugLoopbackAndStrictUrl() {
        listOf("127.0.0.1", "localhost", "[::1]").forEach { Endpoint.validate("ws://$it:8765/bridge/v1", true) }
        listOf("ws://user@10.0.0.1:8765/bridge/v1", "ws://10.0.0.1:8765/bridge/v1?q=1", "http://10.0.0.1:8765/bridge/v1").forEach {
            assertFailsWith<IllegalArgumentException> { Endpoint.validate(it, true) }
        }
        Endpoint.validate("wss://example.com:8765/bridge/v1", false)
    }
}
