package com.dl.m3u8recorder.manager

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class CustomDns : Dns {
    private val cache = ConcurrentHashMap<String, DnsEntry>()
    private val cacheTtlMs = TimeUnit.MINUTES.toMillis(5)

    private data class DnsEntry(
        val addresses: List<InetAddress>,
        val timestamp: Long
    )

    override fun lookup(hostname: String): List<InetAddress> {
        val cached = cache[hostname]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < cacheTtlMs) {
            return cached.addresses
        }

        try {
            val addresses = InetAddress.getAllByName(hostname).toList()
            cache[hostname] = DnsEntry(addresses, System.currentTimeMillis())
            return addresses
        } catch (e: UnknownHostException) {
            cache.remove(hostname)
            throw e
        }
    }

    fun clearCache() {
        cache.clear()
    }
}