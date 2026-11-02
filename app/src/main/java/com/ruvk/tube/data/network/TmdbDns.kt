package com.ruvk.tube.data.network

import okhttp3.Dns
import java.net.InetAddress

/**
 * Прямой Anycast IP-маппинг для доменов TMDB для обхода блокировок/подмены DNS провайдерами РФ.
 * Для всех остальных доменов (Rutube, VK) используется стандартный системный DNS.
 */
class TmdbDns : Dns {

    private val tmdbIps by lazy {
        listOf(
            "104.16.61.155",
            "104.16.62.155",
            "104.16.63.155",
            "104.16.64.155"
        ).mapNotNull {
            runCatching { InetAddress.getByName(it) }.getOrNull()
        }.ifEmpty {
            Dns.SYSTEM.lookup("api.themoviedb.org")
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        return when {
            hostname.equals("api.themoviedb.org", ignoreCase = true) ||
            hostname.equals("image.tmdb.org", ignoreCase = true) ||
            hostname.equals("themoviedb.org", ignoreCase = true) -> {
                tmdbIps
            }
            else -> Dns.SYSTEM.lookup(hostname)
        }
    }
}
