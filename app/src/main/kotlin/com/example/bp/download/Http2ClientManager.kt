package com.example.bp.download

import android.util.Log
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager


// Správce HTTP/2 klienta s podporou self-signed certifikátů
// OkHttp automaticky používá HTTP/2 pokud je server podporuje

object Http2ClientManager {

    private const val TAG = "Http2ClientManager"


    // Trust manager pro self-signed certifikáty (pouze pro development!)

    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })


     // SSL Context pro důvěru všem certifikátům

    private val trustAllSslContext = SSLContext.getInstance("TLS").apply {
        init(null, trustAllCerts, SecureRandom())
    }


     // OkHttp klient s HTTP/2 supportem

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))

            // Connection pooling pro multiplexing
            .connectionPool(
                okhttp3.ConnectionPool(
                    maxIdleConnections = 5,
                    keepAliveDuration = 5,
                    timeUnit = TimeUnit.MINUTES
                )
            )

            // Timeouts
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)

            // Self-signed certificate support
            .sslSocketFactory(trustAllSslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }

            .connectionSpecs(
                listOf(
                    ConnectionSpec.MODERN_TLS,
                    ConnectionSpec.COMPATIBLE_TLS,
                    ConnectionSpec.CLEARTEXT
                )
            )

            .retryOnConnectionFailure(true)

            // Event listener pro debugging
            .eventListener(Http2EventListener())

            .build()
    }


    // Klient specificky pro chunky s agressivnějším connection poolingem

    val chunkClient: OkHttpClient by lazy {
        client.newBuilder()
            // Více paralelních připojení pro chunky
            .connectionPool(
                okhttp3.ConnectionPool(
                    maxIdleConnections = 10,
                    keepAliveDuration = 5,
                    timeUnit = TimeUnit.MINUTES
                )
            )
            .retryOnConnectionFailure(false)
            .build()
    }


     // Event listener pro monitoring HTTP/2 připojení

    private class Http2EventListener : okhttp3.EventListener() {
        override fun connectionAcquired(call: okhttp3.Call, connection: okhttp3.Connection) {
            Log.d(TAG, "Connection acquired: ${connection.protocol()}")
        }

        override fun connectStart(
            call: okhttp3.Call,
            inetSocketAddress: java.net.InetSocketAddress,
            proxy: java.net.Proxy
        ) {
            Log.d(TAG, "Connecting to: $inetSocketAddress")
        }

        override fun connectEnd(
            call: okhttp3.Call,
            inetSocketAddress: java.net.InetSocketAddress,
            proxy: java.net.Proxy,
            protocol: Protocol?
        ) {
            Log.d(TAG, "Connected via: ${protocol ?: "unknown"}")
            if (protocol == Protocol.HTTP_2) {
                Log.i(TAG, "HTTP/2 connection established!")
            }
        }
    }


     // Získá statistiky o použitých protokolech

    fun getConnectionStats(): String {
        val pool = client.connectionPool
        return buildString {
            append("Active connections: ${pool.connectionCount()}\n")
            append("Idle connections: ${pool.idleConnectionCount()}")
        }
    }
}
