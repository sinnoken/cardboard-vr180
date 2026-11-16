package com.example.vr180.smb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress

/**
 * 用 mDNS（DNS-SD）找區網裡廣播 SMB 的主機（_smb._tcp）。Synology、QNAP 預設通常會廣播；
 * Windows 分享多半不會，只能手動輸入。結果在主執行緒回呼，同一個 IP 只回報一次。
 *
 * 所有版本都用 discoverServices + resolveService（API 34 起標為 deprecated 但仍可用），
 * 不必為新舊系統各寫一套；舊系統同一時間只能解析一個，所以排隊一個一個解析。
 */
class SmbDiscovery(context: Context, private val onFound: (Found) -> Unit) {
    data class Found(val name: String, val ip: String)

    private val nsd = context.getSystemService(NsdManager::class.java)
    // 較舊的系統沒有 multicast lock 收不到 mDNS 回應；只在搜尋期間持有
    private val lock = context.applicationContext.getSystemService(WifiManager::class.java)
        ?.createMulticastLock("vr180-smb-discovery")?.apply { setReferenceCounted(false) }
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<NsdServiceInfo>()
    private val reported = HashSet<String>()
    private var resolving = false
    private var running = false

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) {}
        override fun onDiscoveryStopped(type: String) {}
        override fun onStartDiscoveryFailed(type: String, code: Int) { Log.w(TAG, "discovery failed: $code") }
        override fun onStopDiscoveryFailed(type: String, code: Int) {}
        override fun onServiceFound(info: NsdServiceInfo) { main.post { enqueue(info) } }
        override fun onServiceLost(info: NsdServiceInfo) {}
    }

    /** 主執行緒呼叫。 */
    fun start() {
        if (running) return
        running = true
        lock?.acquire()
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
    }

    /** 主執行緒呼叫；重複呼叫沒關係。 */
    fun stop() {
        if (!running) return
        running = false
        queue.clear()
        runCatching { nsd.stopServiceDiscovery(discovery) }
        lock?.release()
    }

    private fun enqueue(info: NsdServiceInfo) {
        if (!running) return
        queue.addLast(info)
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving || !running) return
        val info = queue.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(s: NsdServiceInfo, code: Int) {
                Log.w(TAG, "resolve ${s.serviceName} failed: $code")
                main.post { resolving = false; resolveNext() }
            }

            override fun onServiceResolved(s: NsdServiceInfo) {
                val addrs: List<InetAddress> = if (Build.VERSION.SDK_INT >= 34) s.hostAddresses else listOfNotNull(s.host)
                // 只用 IPv4：IPv6 鏈路本地位址需要介面編號，SMB 連線用不了
                val ip = addrs.firstOrNull { it is Inet4Address }?.hostAddress
                if (ip == null) Log.w(TAG, "${s.serviceName}: no IPv4 address, skipped")
                main.post {
                    resolving = false
                    if (running && ip != null && reported.add(ip)) onFound(Found(s.serviceName, ip))
                    resolveNext()
                }
            }
        })
    }

    private companion object {
        const val TAG = "SmbDiscovery"
        const val SERVICE_TYPE = "_smb._tcp"
    }
}
