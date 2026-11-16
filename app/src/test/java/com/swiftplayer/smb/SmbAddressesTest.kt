package com.swiftplayer.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SmbAddressesTest {

    @Test
    fun `host without port uses SMB default`() {
        assertEquals(SmbEndpoint("192.168.1.100", 445), SmbAddresses.parse("192.168.1.100"))
    }

    @Test
    fun `host with explicit port is preserved`() {
        assertEquals(SmbEndpoint("192.168.1.100", 445), SmbAddresses.parse("192.168.1.100:445"))
        assertEquals(SmbEndpoint("nas.local", 1445), SmbAddresses.parse("nas.local:1445"))
    }

    @Test
    fun `scheme trailing slash and path do not become part of host`() {
        assertEquals(
            SmbEndpoint("192.168.1.100", 445),
            SmbAddresses.parse(" smb://192.168.1.100/movies/ ")
        )
        assertEquals(
            SmbEndpoint("192.168.1.100", 1445),
            SmbAddresses.parse("smb://192.168.1.100:1445/movies")
        )
    }

    @Test
    fun `IPv6 forms are supported`() {
        assertEquals(SmbEndpoint("::1", 445), SmbAddresses.parse("::1"))
        assertEquals(SmbEndpoint("fd00::1", 1445), SmbAddresses.parse("[fd00::1]:1445"))
    }

    @Test
    fun `invalid addresses fail clearly instead of silently using wrong endpoint`() {
        assertThrows(IllegalArgumentException::class.java) { SmbAddresses.parse("") }
        assertThrows(IllegalArgumentException::class.java) { SmbAddresses.parse("http://192.168.1.100") }
        assertThrows(IllegalArgumentException::class.java) { SmbAddresses.parse("192.168.1.100:") }
        assertThrows(IllegalArgumentException::class.java) { SmbAddresses.parse("192.168.1.100:70000") }
    }
}
