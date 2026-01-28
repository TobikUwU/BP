package com.example.bp.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow


  // Monitoruje síťovou rychlost a typ připojení

class BandwidthMonitor(private val context: Context) {

    data class NetworkStats(
        val connectionType: ConnectionType,
        val averageSpeed: Long,        // bytes per second
        val currentSpeed: Long,
        val isMetered: Boolean,
        val recommendedParallelism: Int
    )

    enum class ConnectionType {
        WIFI,
        CELLULAR_5G,
        CELLULAR_4G,
        CELLULAR_3G,
        ETHERNET,
        OFFLINE
    }

    private val _networkStats = MutableStateFlow(
        NetworkStats(
            connectionType = ConnectionType.OFFLINE,
            averageSpeed = 0,
            currentSpeed = 0,
            isMetered = false,
            recommendedParallelism = 1
        )
    )
    val networkStats: StateFlow<NetworkStats> = _networkStats

    private val speedHistory = mutableListOf<Long>()
    private val maxHistorySize = 20

    fun getConnectionType(): ConnectionType {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager

        val network = connectivityManager.activeNetwork
        if (network == null) {
            Log.w("BandwidthMonitor", "No active network found")
            // 🆕 Fallback: zkus získat network info jinak
            try {
                val networkInfo = connectivityManager.activeNetworkInfo
                if (networkInfo?.isConnected == true) {
                    Log.d("BandwidthMonitor", "Network connected via fallback method")
                    return when (networkInfo.type) {
                        android.net.ConnectivityManager.TYPE_WIFI -> ConnectionType.WIFI
                        android.net.ConnectivityManager.TYPE_MOBILE -> ConnectionType.CELLULAR_4G
                        android.net.ConnectivityManager.TYPE_ETHERNET -> ConnectionType.ETHERNET
                        else -> ConnectionType.OFFLINE
                    }
                }
            } catch (e: Exception) {
                Log.e("BandwidthMonitor", "Error getting network info", e)
            }
            return ConnectionType.OFFLINE
        }

        val capabilities = connectivityManager.getNetworkCapabilities(network)
        if (capabilities == null) {
            Log.w("BandwidthMonitor", "No network capabilities")
            return ConnectionType.OFFLINE
        }

        Log.d("BandwidthMonitor", "Network capabilities available")

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                Log.d("BandwidthMonitor", "Detected: WIFI")
                ConnectionType.WIFI
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                Log.d("BandwidthMonitor", "Detected: ETHERNET")
                ConnectionType.ETHERNET
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val type = detectCellularGeneration(capabilities)
                Log.d("BandwidthMonitor", "Detected: $type")
                type
            }
            else -> {
                Log.w("BandwidthMonitor", "Unknown transport type")
                ConnectionType.OFFLINE
            }
        }
    }

    private fun detectCellularGeneration(capabilities: NetworkCapabilities): ConnectionType {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val downstreamBandwidth = capabilities.linkDownstreamBandwidthKbps

            return when {
                downstreamBandwidth > 50000 -> ConnectionType.CELLULAR_5G
                downstreamBandwidth > 5000 -> ConnectionType.CELLULAR_4G
                else -> ConnectionType.CELLULAR_3G
            }
        }

        return ConnectionType.CELLULAR_4G
    }

    fun isMeteredConnection(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager
        return connectivityManager.isActiveNetworkMetered
    }

    fun updateSpeed(bytesPerSecond: Long) {
        speedHistory.add(bytesPerSecond)
        if (speedHistory.size > maxHistorySize) {
            speedHistory.removeAt(0)
        }

        val avgSpeed = speedHistory.average().toLong()
        val connectionType = getConnectionType()
        val isMetered = isMeteredConnection()

        val parallelism = when {
            avgSpeed > 10_000_000 -> 8
            avgSpeed > 5_000_000 -> 5
            avgSpeed > 1_000_000 -> 3
            else -> 1
        }

        _networkStats.value = NetworkStats(
            connectionType = connectionType,
            averageSpeed = avgSpeed,
            currentSpeed = bytesPerSecond,
            isMetered = isMetered,
            recommendedParallelism = parallelism
        )
    }

    fun shouldWarnAboutMeteredConnection(fileSize: Long): Boolean {
        return _networkStats.value.isMetered &&
                _networkStats.value.connectionType != ConnectionType.WIFI &&
                fileSize > 50 * 1024 * 1024
    }
}