package com.ruvk.tube.data.network

import okhttp3.Dns
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * Резолвер DNS для TMDB.
 * 1. Использует кэш в памяти (TTL 5 мин для DNS, 1 мин для Anycast-фолбэка).
 * 2. Пробует системный DNS с приоритетом IPv4 над IPv6 (в эмуляторах/ТВ IPv6 часто не имеет внешнего маршрута).
 * 3. В случае ошибки/блокировки опрашивает Quad9 (9.9.9.9:53) по открытому UDP.
 * 4. Резервный Anycast IP-фолбэк для CDN CloudFront / BunnyCDN при отказе локального DNS-шлюза.
 */
class TmdbDns : Dns {

    private data class CachedEntry(
        val addresses: List<InetAddress>,
        val expiresAtMs: Long
    )

    companion object {
        private const val UDP_TIMEOUT_MS = 1200
        private const val DNS_CACHE_TTL_MS = 5 * 60 * 1000L
        private const val FALLBACK_CACHE_TTL_MS = 60 * 1000L

        private val dnsCache = ConcurrentHashMap<String, CachedEntry>()

        private val FALLBACK_TMDB_API_IPS = listOf(
            "143.204.238.8",
            "143.204.238.50",
            "143.204.238.23",
            "143.204.238.7"
        )
        private val FALLBACK_TMDB_IMAGE_IPS = listOf(
            "169.150.247.34",
            "169.150.247.35",
            "169.150.247.36"
        )
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        dnsCache[hostname]?.let { cached ->
            if (now < cached.expiresAtMs && cached.addresses.isNotEmpty()) {
                return cached.addresses
            }
        }

        // 1. Пробуем системный DNS
        try {
            val addresses = Dns.SYSTEM.lookup(hostname)
            if (addresses.isNotEmpty()) {
                // Приоритет IPv4 над IPv6, чтобы не ловить таймауты в эмуляторах без IPv6 маршрутизации
                val sorted = addresses.sortedBy { it !is Inet4Address }
                dnsCache[hostname] = CachedEntry(sorted, now + DNS_CACHE_TTL_MS)
                return sorted
            }
        } catch (_: Exception) {
            // Системный DNS не ответил или заблокирован провайдером
        }

        // 2. Если домен TMDB и системный DNS не ответил — прямой UDP запрос к Quad9 (9.9.9.9)
        val isTmdb = hostname.contains("themoviedb.org", ignoreCase = true) || hostname.contains("tmdb.org", ignoreCase = true)
        if (isTmdb) {
            val quad9Result = resolveViaUdp(hostname, "9.9.9.9")
            if (quad9Result.isNotEmpty()) {
                val sorted = quad9Result.sortedBy { it !is Inet4Address }
                dnsCache[hostname] = CachedEntry(sorted, now + DNS_CACHE_TTL_MS)
                return sorted
            }

            // 3. Аварийный Anycast IP-фолбэк для TMDB
            val fallbackIps = if (hostname.contains("image.tmdb.org", ignoreCase = true)) {
                FALLBACK_TMDB_IMAGE_IPS
            } else {
                FALLBACK_TMDB_API_IPS
            }
            val resolvedFallback = fallbackIps.mapNotNull { ip ->
                runCatching { InetAddress.getByName(ip) }.getOrNull()
            }
            if (resolvedFallback.isNotEmpty()) {
                dnsCache[hostname] = CachedEntry(resolvedFallback, now + FALLBACK_CACHE_TTL_MS)
                return resolvedFallback
            }
        }

        throw UnknownHostException("Не удалось разрешить адрес хоста: $hostname")
    }

    private fun resolveViaUdp(hostname: String, dnsServerIp: String): List<InetAddress> {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket().apply { soTimeout = UDP_TIMEOUT_MS }
            val dnsServer = InetAddress.getByName(dnsServerIp)

            val queryBytes = buildDnsQuery(hostname)
            val sendPacket = DatagramPacket(queryBytes, queryBytes.size, dnsServer, 53)
            socket.send(sendPacket)

            val receiveBuffer = ByteArray(512)
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            socket.receive(receivePacket)

            parseDnsResponse(receiveBuffer, receivePacket.length)
        } catch (_: Exception) {
            emptyList()
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    internal fun buildDnsQuery(hostname: String): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        // Header
        dos.writeShort(0x1A2B) // Transaction ID
        dos.writeShort(0x0100) // Flags: Standard query, recursion desired
        dos.writeShort(1)      // Questions count
        dos.writeShort(0)      // Answer RRs
        dos.writeShort(0)      // Authority RRs
        dos.writeShort(0)      // Additional RRs

        // Question: QNAME
        for (part in hostname.split('.')) {
            val bytes = part.toByteArray(Charsets.US_ASCII)
            dos.writeByte(bytes.size)
            dos.write(bytes)
        }
        dos.writeByte(0) // End of name

        dos.writeShort(1) // QTYPE: A (IPv4)
        dos.writeShort(1) // QCLASS: IN

        return baos.toByteArray()
    }

    internal fun parseDnsResponse(buffer: ByteArray, length: Int): List<InetAddress> {
        val results = mutableListOf<InetAddress>()
        if (length < 12) return results

        val anCount = ((buffer[6].toInt() and 0xFF) shl 8) or (buffer[7].toInt() and 0xFF)
        if (anCount == 0) return results

        var pos = 12

        // Пропускаем Question секцию
        while (pos < length && buffer[pos].toInt() != 0) {
            val len = buffer[pos].toInt() and 0xFF
            if ((len and 0xC0) == 0xC0) {
                pos += 2
                break
            } else {
                pos += len + 1
            }
        }
        if (pos < length && buffer[pos].toInt() == 0) pos++ // 0x00 терминатор
        pos += 4 // QTYPE + QCLASS

        // Читаем Answer секцию
        for (i in 0 until anCount) {
            if (pos + 10 > length) break

            // Пропускаем имя (указатель или метки)
            if ((buffer[pos].toInt() and 0xC0) == 0xC0) {
                pos += 2
            } else {
                while (pos < length && buffer[pos].toInt() != 0) {
                    pos += (buffer[pos].toInt() and 0xFF) + 1
                }
                pos++
            }

            if (pos + 10 > length) break
            val type = ((buffer[pos].toInt() and 0xFF) shl 8) or (buffer[pos + 1].toInt() and 0xFF)
            val dataLen = ((buffer[pos + 8].toInt() and 0xFF) shl 8) or (buffer[pos + 9].toInt() and 0xFF)
            pos += 10

            if (type == 1 && dataLen == 4 && pos + 4 <= length) {
                val ipBytes = byteArrayOf(buffer[pos], buffer[pos + 1], buffer[pos + 2], buffer[pos + 3])
                results.add(InetAddress.getByAddress(ipBytes))
            }
            pos += dataLen
        }

        return results
    }
}
