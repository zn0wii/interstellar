package com.interstellar.proxy.bg

import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import io.nekohasekai.libbox.InterfaceUpdateListener
import com.interstellar.proxy.InterstellarApplication
import java.net.NetworkInterface

object DefaultNetworkMonitor {

    var defaultNetwork: Network? = null
    private var listener: InterfaceUpdateListener? = null

    /**
     * The tracked network must always be the PHYSICAL one. Best-matching /
     * default-network callbacks deliver our own VPN once it is established —
     * binding dns-local (LocalResolver) or the auto-detect interface to it
     * loops every query back into the tunnel, killing resolution of node
     * server domains (all proxied traffic then dies with it).
     */
    private fun isVpn(network: Network): Boolean =
        InterstellarApplication.connectivity.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    private fun physicalNetwork(): Network? {
        val cm = InterstellarApplication.connectivity
        cm.activeNetwork?.let { if (!isVpn(it)) return it }
        return cm.allNetworks.firstOrNull { network ->
            !isVpn(network) &&
                cm.getNetworkCapabilities(network)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
    }

    suspend fun start() {
        DefaultNetworkListener.start(this) { network ->
            if (network != null && isVpn(network)) return@start
            defaultNetwork = network ?: physicalNetwork()
            checkDefaultInterfaceUpdate(defaultNetwork)
        }
        defaultNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            physicalNetwork()
        } else {
            DefaultNetworkListener.get()
        }
    }

    suspend fun stop() {
        DefaultNetworkListener.stop(this)
    }

    suspend fun require(): Network {
        val network = defaultNetwork
        if (network != null) {
            return network
        }
        return DefaultNetworkListener.get()
    }

    fun setListener(listener: InterfaceUpdateListener?) {
        this.listener = listener
        checkDefaultInterfaceUpdate(defaultNetwork)
    }

    private fun checkDefaultInterfaceUpdate(newNetwork: Network?) {
        val listener = listener ?: return
        if (newNetwork != null) {
            for (times in 0 until 10) {
                val linkProperties =
                    InterstellarApplication.connectivity.getLinkProperties(newNetwork)
                if (linkProperties == null) {
                    Thread.sleep(100)
                    continue
                }
                val interfaceIndex: Int
                try {
                    interfaceIndex = NetworkInterface.getByName(linkProperties.interfaceName).index
                } catch (e: Exception) {
                    Thread.sleep(100)
                    continue
                }
                listener.updateDefaultInterface(linkProperties.interfaceName, interfaceIndex, false, false)
            }
        } else {
            listener.updateDefaultInterface("", -1, false, false)
        }
    }
}
