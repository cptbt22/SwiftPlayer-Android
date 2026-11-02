package com.swiftplayer.smb

import android.content.Context
import android.net.wifi.WifiManager
import com.swiftplayer.data.model.SmbServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

class LanDiscoveryService(private val context: Context) {

    interface DiscoveryCallback {
        fun onServerFound(server: SmbServer)
        fun onDiscoveryFinished(servers: List<SmbServer>)
        fun onDiscoveryError(message: String)
    }

    private var jmDns: JmDNS? = null
    private var wifiLock: WifiManager.MulticastLock? = null
    private val discoveredServers = mutableMapOf<String, SmbServer>()
    private var callback: DiscoveryCallback? = null
    private var mainHandler: android.os.Handler? = null

    suspend fun discoverServers(cb: DiscoveryCallback): Unit = withContext(Dispatchers.IO) {
        callback = cb
        mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        try {
            acquireMulticastLock()

            val localAddress = getWifiInetAddress()
            jmDns = if (localAddress != null) {
                JmDNS.create(localAddress)
            } else {
                JmDNS.create()
            }
            discoveredServers.clear()

            val listener = object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) {
                    jmDns?.requestServiceInfo(event.type, event.name, 3000)
                }

                override fun serviceRemoved(event: ServiceEvent) {
                    val key = "${event.type}/${event.name}"
                    discoveredServers.remove(key)
                }

                override fun serviceResolved(event: ServiceEvent) {
                    val info = event.info
                    val addresses = info.inet4Addresses
                    if (addresses.isEmpty()) return

                    val address = addresses.first().hostAddress
                    val port = info.port
                    val name = info.name
                    val key = "${event.type}/${event.name}"

                    if (!discoveredServers.containsKey(key)) {
                        val server = SmbServer(
                            name = name,
                            address = "$address:$port",
                            shareName = "",
                            username = "",
                            password = ""
                        )
                        discoveredServers[key] = server

                        mainHandler?.post {
                            callback?.onServerFound(server)
                        }
                    }
                }
            }

            jmDns?.addServiceListener(TYPE_SMB, listener)
            jmDns?.addServiceListener(TYPE_SMB_WORKGROUP, listener)

            Thread.sleep(DISCOVERY_TIMEOUT_MS)

            jmDns?.removeServiceListener(TYPE_SMB, listener)
            jmDns?.removeServiceListener(TYPE_SMB_WORKGROUP, listener)

            mainHandler?.post {
                callback?.onDiscoveryFinished(discoveredServers.values.toList())
            }
        } catch (e: Exception) {
            mainHandler?.post {
                callback?.onDiscoveryError("发现失败: ${e.message}")
            }
        } finally {
            stop()
        }
    }

    fun stop() {
        try {
            jmDns?.close()
        } catch (_: Exception) {}
        jmDns = null
        releaseMulticastLock()
        discoveredServers.clear()
        callback = null
        mainHandler = null
    }

    private fun getWifiInetAddress(): InetAddress? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiIp = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (wifiIp != 0) {
                val byteAddress = byteArrayOf(
                    (wifiIp and 0xFF).toByte(),
                    (wifiIp shr 8 and 0xFF).toByte(),
                    (wifiIp shr 16 and 0xFF).toByte(),
                    (wifiIp shr 24 and 0xFF).toByte()
                )
                return InetAddress.getByAddress(byteAddress)
            }
        } catch (_: Exception) {}

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces?.hasMoreElements() == true) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                if (name.contains("wlan") || name.contains("eth") || name.contains("rmnet")) {
                    val addrs = intf.inetAddresses
                    while (addrs?.hasMoreElements() == true) {
                        val addr = addrs.nextElement()
                        if (addr is InetAddress && !addr.isLoopbackAddress && addr.hostAddress?.contains(':') == false) {
                            return addr
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    private fun acquireMulticastLock() {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createMulticastLock("SwiftPlayer_LanDiscovery").apply {
            setReferenceCounted(true)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        try {
            wifiLock?.release()
        } catch (_: Exception) {}
        wifiLock = null
    }

    companion object {
        private const val TYPE_SMB = "_smb._tcp.local."
        private const val TYPE_SMB_WORKGROUP = "_workstation._tcp.local."
        private const val DISCOVERY_TIMEOUT_MS = 5000L
    }
}
