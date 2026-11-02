package com.ruvk.tube.data.network

import okhttp3.Dns
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Резолвер DNS для TMDB.
 * Сначала пробует системный DNS. В случае ошибки/блокировки опрашивает Quad9 (9.9.9.9:53) по открытому UDP.
 */
class TmdbDns : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        // 1. Пробуем системный DNS
        try {
            val addresses = Dns.SYSTEM.lookup(hostname)
            if (addresses.isNotEmpty()) {
                return addresses
            }
        } catch (e: Exception) {
            // Системный DNS не ответил или заблокирован провайдером
        }

        // 2. Если домен TMDB и системный DNS не ответил — прямой UDP запрос к Quad9 (9.9.9.9)
        if (hostname.contains("themoviedb.org", ignoreCase = true) || hostname.contains("tmdb.org", ignoreCase = true)) {
            val quad9Result = resolveViaUdp(hostname, "9.9.9.9")
            if (quad9Result.isNotEmpty()) {
                return quad9Result
            }
        }

        throw UnknownHostException("Не удалось разрешить адрес хоста: $hostname")
    }

    private fun resolveViaUdp(hostname: String, dnsServerIp: String): List<InetAddress> {
        return try {
            val socket = DatagramSocket().apply { soTimeout = 3000 }
            val dnsServer = InetAddress.getByName(dnsServerIp)

            val queryBytes = buildDnsQuery(hostname)
            val sendPacket = DatagramPacket(queryBytes, queryBytes.size, dnsServer, 53)
            socket.send(sendPacket)

            val receiveBuffer = ByteArray(512)
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            socket.receive(receivePacket)
            socket.close()

            parseDnsResponse(receiveBuffer, receivePacket.length)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun buildDnsQuery(hostname: String): ByteArray {
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

    private fun parseDnsResponse(buffer: ByteArray, length: Int): List<InetAddress> {
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
