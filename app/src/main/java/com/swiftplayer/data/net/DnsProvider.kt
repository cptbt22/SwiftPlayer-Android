package com.swiftplayer.data.net

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 全局 DNS 解析：系统 DNS 优先，失败或返回空时回退 DoH。
 *
 * 之所以单独抽出来，是因为 Emby API 与图片加载（Glide）用的是两套独立网络栈。
 * 若只有 API 走自定义 DNS，就会出现「影片列表正常、封面全是空白」——
 * API 请求经 DoH 解析成功，图片请求仍在用系统 DNS 而解析失败。
 * 两处必须共用同一个 [Dns] 实例。
 */
object DnsProvider {

    /** 解析结果缓存时长。短一些没关系，系统 DNS 失败时才走 DoH，代价主要在失败探测上 */
    private const val DNS_TTL_MS = 60_000L

    /** 某域名系统 DNS 失败后，这段时间内直接跳过它，不再每次干等超时 */
    private const val SYSTEM_FAIL_MEMO_MS = 120_000L

    private data class Entry(val addresses: List<InetAddress>, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val systemFailUntil = ConcurrentHashMap<String, Long>()

    /** 引导客户端：只用于连接 DoH 服务器，本身不含自定义 DNS，避免递归 */
    private val bootstrapClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    /**
     * DoH（DNS over HTTPS，阿里公共 DNS）。
     * bootstrapDnsHosts 使用 IP 字面量引导，因此系统 DNS 完全解析不出域名时依然可用。
     */
    private val dohDns: DnsOverHttps by lazy {
        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://dns.alidns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("223.5.5.5"),
                InetAddress.getByName("223.6.6.6")
            )
            .build()
    }

    /** 系统 DNS 优先；解析失败或返回空时自动回退 DoH。带缓存，避免每个图片请求都重复探测 */
    val smart: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val now = System.currentTimeMillis()

            cache[hostname]?.let { entry ->
                if (now < entry.expiresAt) return entry.addresses
            }

            // 该域名最近系统 DNS 失败过，跳过它直接走 DoH，不再每次等超时
            val memoUntil = systemFailUntil[hostname] ?: 0L
            if (now >= memoUntil) {
                val system = try {
                    Dns.SYSTEM.lookup(hostname)
                } catch (_: Exception) {
                    emptyList()
                }
                if (system.isNotEmpty()) {
                    cache[hostname] = Entry(system, now + DNS_TTL_MS)
                    systemFailUntil.remove(hostname)
                    return system
                }
                systemFailUntil[hostname] = now + SYSTEM_FAIL_MEMO_MS
            }

            val resolved = try {
                dohDns.lookup(hostname)
            } catch (_: Exception) {
                emptyList()
            }
            if (resolved.isNotEmpty()) {
                cache[hostname] = Entry(resolved, now + DNS_TTL_MS)
            }
            return resolved
        }
    }

    /** 图片加载专用客户端（Glide 复用），与 API 请求共用同一套解析 */
    val imageClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(smart)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** 仅走 DoH 解析一次，供连接诊断展示「系统 DNS 失败时 DoH 能否救回来」 */
    fun lookupOverDoh(hostname: String): List<InetAddress> = dohDns.lookup(hostname)
}
