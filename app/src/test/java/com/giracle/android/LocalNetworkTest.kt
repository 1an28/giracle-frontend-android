package com.giracle.android

import com.giracle.android.data.net.LocalNetwork
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** Android 17 のローカルネットワーク権限を求める相手の判定 (#42) */
class LocalNetworkTest {
    private fun local(a: String) = LocalNetwork.isLocalAddress(InetAddress.getByName(a))

    @Test fun privateAddressesAreLocal() {
        listOf("192.168.1.10", "10.0.2.2", "172.16.0.5", "169.254.1.1", "fd00::1", "fe80::1").forEach { assertTrue(it, local(it)) }
    }

    @Test fun publicAndLoopbackAreNot() {
        listOf("8.8.8.8", "127.0.0.1", "::1", "2001:4860:4860::8888", "172.32.0.1").forEach { assertFalse(it, local(it)) }
    }

    @Test fun names() {
        assertTrue(LocalNetwork.isLocalName("giracle.local"))
        assertTrue(LocalNetwork.isLocalName("nas.home.arpa"))
        assertTrue(LocalNetwork.isLocalName("raspberrypi"))
        assertFalse(LocalNetwork.isLocalName("localhost"))
        assertFalse(LocalNetwork.isLocalName("chat.girak.moe"))
        assertTrue(LocalNetwork.looksLocal("192.168.0.2"))
        assertFalse(LocalNetwork.looksLocal("chat.girak.moe"))
        assertFalse(LocalNetwork.looksLocal("localhost"))
    }
}
