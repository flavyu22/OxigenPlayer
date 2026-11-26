// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import android.content.Context
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.File
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Singleton providing a high-performance, ultra-optimized OkHttpClient.
 * Specifically tuned for high-bitrate streaming (4K/8K) and low-latency API calls.
 */
object NetworkClient {
    private const val CACHE_SIZE = 50L * 1024L * 1024L
    private const val MAX_IDLE_CONNECTIONS = 8
    private const val KEEP_ALIVE_DURATION_MIN = 5L

    @Volatile
    private var applicationCacheDir: File? = null

    @Volatile
    private var sharedClient: OkHttpClient? = null

    @Volatile
    private var playbackClient: OkHttpClient? = null

    @Volatile
    private var localApiClient: OkHttpClient? = null

    fun initialize(context: Context) {
        if (applicationCacheDir == null) {
            synchronized(this) {
                if (applicationCacheDir == null) {
                    applicationCacheDir = File(context.applicationContext.cacheDir, "http")
                }
            }
        }
    }

    val instance: OkHttpClient
        get() = sharedClient ?: synchronized(this) {
            sharedClient ?: buildClient().also { sharedClient = it }
        }

    /** Public API client: HTTPS-only and protected against private-network SSRF. */
    fun evictCacheIfInitialized() {
        sharedClient?.cache?.evictAll()
        playbackClient?.cache?.evictAll()
    }

    /**
     * Media client for addresses explicitly selected by the user. It respects system DNS/proxy
     * settings and therefore supports NAS/LAN hosts, `.local`, HTTP and custom ports.
     */
    val playback: OkHttpClient
        get() = playbackClient ?: synchronized(this) {
            playbackClient ?: buildPlaybackClient().also { playbackClient = it }
        }

    /**
     * Direct client for a user-configured LAN translation server. Redirects and proxies are disabled
     * so an API key or subtitle text cannot be forwarded to a different destination.
     */
    val localApi: OkHttpClient
        get() = localApiClient ?: synchronized(this) {
            localApiClient ?: buildLocalApiClient().also { localApiClient = it }
        }

    private fun buildLocalApiClient(): OkHttpClient = OkHttpClient.Builder()
        .dns(LocalOnlyDns)
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "OxigenPlayer/${BuildConfig.VERSION_NAME} (Android; local-api)")
                .build()
            chain.proceed(request)
        }
        .build()

    private fun buildPlaybackClient(): OkHttpClient {
        // Disable disk cache for media playback to prevent disk write thrashing when streaming large files (> 30 GB).
        return OkHttpClient.Builder()
            .cache(null)
            .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_DURATION_MIN, TimeUnit.MINUTES))
            .connectTimeout(12, TimeUnit.SECONDS)
            // Torrent-backed servers (TorrServer) can legitimately stall between bytes
            // while pieces are still downloading; give them room before timing out.
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val original = chain.request()
                if (original.url.scheme == "http" &&
                    (!BuildConfig.CLEARTEXT_LAN_ENABLED || !isExplicitLanHost(original.url.host))
                ) {
                    throw UnknownHostException("Cleartext media is restricted to explicit LAN targets")
                }
                val request = if (original.header("User-Agent") == null) {
                    original.newBuilder()
                        .header("User-Agent", "OxigenPlayer/${BuildConfig.VERSION_NAME} (Android; media)")
                        .build()
                } else original
                chain.proceed(request)
            }
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                if (request.url.scheme == "http") {
                    if (!BuildConfig.CLEARTEXT_LAN_ENABLED || !isExplicitLanHost(request.url.host)) {
                        throw UnknownHostException("Blocked public cleartext redirect")
                    }
                    val routeAddress = chain.connection()?.route()?.socketAddress?.address
                    if (routeAddress != null && !isLocalNetworkAddress(routeAddress)) {
                        throw UnknownHostException("Cleartext media resolved outside the local network")
                    }
                }
                chain.proceed(request)
            }
            .build()
    }

    private fun buildClient(): OkHttpClient {
        val cache = applicationCacheDir?.let { directory ->
            runCatching { Cache(File(directory, "api"), CACHE_SIZE) }.getOrNull()
        }
        return OkHttpClient.Builder()
            .cache(cache)
            .dns(PublicOnlyDns)
            // Route selection runs for direct requests, redirects and adaptive-media child
            // resources before OkHttp's numeric-IP shortcut. Returning NO_PROXY also ensures the
            // destination is resolved locally by PublicOnlyDns instead of by a remote proxy.
            .proxySelector(PublicOnlyProxySelector)
            .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_DURATION_MIN, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(SafeRequestInterceptor)
            .addNetworkInterceptor(SafeRequestInterceptor)
            .build()
    }

    private object LocalOnlyDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val localAddresses = Dns.SYSTEM.lookup(hostname).filter(::isLocalNetworkAddress)
            if (localAddresses.isEmpty()) {
                throw UnknownHostException("Local API host did not resolve to a local address")
            }
            return localAddresses
        }
    }

    private object PublicOnlyDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val publicAddresses = Dns.SYSTEM.lookup(hostname).filter(::isPublicNetworkAddress)
            if (publicAddresses.isEmpty()) {
                throw UnknownHostException("Host did not resolve to a public address")
            }
            return publicAddresses
        }
    }

    private object PublicOnlyProxySelector : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val url = uri?.toString()?.toHttpUrlOrNull()
            if (url == null || !isAllowedPublicHttpsUrl(url)) {
                throw SecurityException("Blocked non-public HTTPS destination")
            }
            return listOf(Proxy.NO_PROXY)
        }

        override fun connectFailed(uri: URI?, socketAddress: SocketAddress?, failure: java.io.IOException?) {
            // Direct connections are intentional; there is no upstream proxy to notify.
        }
    }

    private object SafeRequestInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val originalRequest = chain.request()
            if (!isAllowedPublicHttpsUrl(originalRequest.url)) {
                throw UnknownHostException("Blocked non-public HTTPS destination")
            }
            val request = if (originalRequest.header("User-Agent") == null) {
                originalRequest.newBuilder()
                    .header("User-Agent", "OxigenPlayer/${BuildConfig.VERSION_NAME} (Android)")
                    .build()
            } else {
                originalRequest
            }
            return chain.proceed(request)
        }
    }
}

/**
 * Rejects cleartext URLs and numeric destinations that bypass OkHttp's [Dns] implementation.
 * Hostnames are resolved by [PublicOnlyDns], which independently rejects non-public answers.
 */
internal fun isAllowedPublicHttpsUrl(url: HttpUrl): Boolean {
    if (!url.isHttps) return false

    val host = url.host.lowercase()
    // Reject non-canonical integer/hex/octal host forms rather than relying on a
    // resolver or URL implementation to normalize them safely.
    if (host.startsWith("0x") || host.all(Char::isDigit) ||
        host.split('.').any { it.length > 1 && it.startsWith('0') && it.all(Char::isDigit) }
    ) return false

    val address = parseStrictNumericAddress(host)
    if (address != null) return isPublicNetworkAddress(address)

    // A host made only of digits and dots is an abbreviated IPv4 form such as
    // "127.1"; resolvers expand it to an address the strict parser refused, so
    // it must never reach the network layer.
    if (host.isNotEmpty() && host.all { it.isDigit() || it == '.' }) return false

    return ':' !in host
}

/** Prevents media manifests and redirects from turning the shared client into an SSRF primitive. */
internal fun isPublicNetworkAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress
    ) {
        return false
    }

    val bytes = address.address.map { it.toInt() and 0xff }
    if (bytes.size == 4) return !isReservedIpv4(bytes)
    if (bytes.size != 16) return false

    if (bytes[0] and 0xfe == 0xfc) return false // fc00::/7
    if (bytes.take(4) == listOf(0x20, 0x01, 0x0d, 0xb8)) return false // documentation

    // IPv4-mapped and well-known NAT64 forms can otherwise hide private IPv4 targets.
    val isMapped = bytes.take(10).all { it == 0 } && bytes[10] == 0xff && bytes[11] == 0xff
    val isNat64 = bytes.take(4) == listOf(0x00, 0x64, 0xff, 0x9b)
    if ((isMapped || isNat64) && isReservedIpv4(bytes.takeLast(4))) return false
    return true
}

private fun isReservedIpv4(bytes: List<Int>): Boolean {
    if (bytes.size != 4) return true
    val (first, second, third) = bytes
    return first == 0 || first == 10 || first == 127 || first >= 224 ||
        (first == 100 && second in 64..127) ||
        (first == 169 && second == 254) ||
        (first == 172 && second in 16..31) ||
        (first == 192 && second == 168) ||
        (first == 192 && second == 0 && third in setOf(0, 2)) ||
        (first == 192 && second == 88 && third == 99) ||
        (first == 198 && second in 18..19) ||
        (first == 198 && second == 51 && third == 100) ||
        (first == 203 && second == 0 && third == 113)
}

/** Cancellable OkHttp bridge shared by subtitle and translation requests. */
internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, res, _ ->
                res.close()
            }
        }
    })
}
