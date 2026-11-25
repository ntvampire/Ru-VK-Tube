package com.ruvk.tube

import com.ruvk.tube.data.network.TmdbDns
import com.ruvk.tube.data.update.UpdateManager
import com.ruvk.tube.player.DeviceCapabilityHelper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Inet4Address

class CoreLogicTest {

    @Test
    fun `update version comparison handles semver and v-prefix`() {
        assertTrue(UpdateManager.isVersionNewer("1.2.12", "1.2.11"))
        assertTrue(UpdateManager.isVersionNewer("v1.3.0", "1.2.11"))
        assertTrue(UpdateManager.isVersionNewer("2.0.0", "1.9.9"))
        assertFalse(UpdateManager.isVersionNewer("1.2.11", "1.2.11"))
        assertFalse(UpdateManager.isVersionNewer("1.2.10", "1.2.11"))
    }

    @Test
    fun `tmdb dns query builds valid RFC1035 A record packet`() {
        val dns = TmdbDns()
        val packet = dns.buildDnsQuery("api.themoviedb.org")
        // Header (12 bytes) + "\x03api\x0athemoviedb\x03org\x00" (20 bytes) + QTYPE(2) + QCLASS(2) = 36 bytes
        assertEquals(36, packet.size)
        // QDCOUNT = 1
        assertEquals(0, packet[4].toInt())
        assertEquals(1, packet[5].toInt())
        // First label length = 3 ("api")
        assertEquals(3, packet[12].toInt())
    }

    @Test
    fun `tmdb dns response parser extracts IPv4 A records`() {
        val dns = TmdbDns()
        val query = dns.buildDnsQuery("api.themoviedb.org")

        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        // Copy header with ANCOUNT = 1
        dos.write(query, 0, 6)
        dos.writeShort(1) // ANCOUNT = 1
        dos.writeShort(0) // NSCOUNT
        dos.writeShort(0) // ARCOUNT
        // Copy Question section
        dos.write(query, 12, query.size - 12)
        // Answer section: compressed pointer to offset 12 (0xC00C)
        dos.writeShort(0xC00C)
        dos.writeShort(1) // TYPE A
        dos.writeShort(1) // CLASS IN
        dos.writeInt(300) // TTL
        dos.writeShort(4) // RDLENGTH
        dos.write(byteArrayOf(143.toByte(), 204.toByte(), 238.toByte(), 8.toByte()))

        val response = baos.toByteArray()
        val parsed = dns.parseDnsResponse(response, response.size)
        assertEquals(1, parsed.size)
        assertTrue(parsed[0] is Inet4Address)
        assertArrayEquals(byteArrayOf(143.toByte(), 204.toByte(), 238.toByte(), 8.toByte()), parsed[0].address)
    }

    @Test
    fun `device capability helper maps quality strings accurately`() {
        assertEquals(2160, DeviceCapabilityHelper.qualityToHeight("4K"))
        assertEquals(1440, DeviceCapabilityHelper.qualityToHeight("2K"))
        assertEquals(1080, DeviceCapabilityHelper.qualityToHeight("1080p"))
        assertEquals(720, DeviceCapabilityHelper.qualityToHeight("720p"))
        assertEquals(480, DeviceCapabilityHelper.qualityToHeight("480p"))
    }
}
