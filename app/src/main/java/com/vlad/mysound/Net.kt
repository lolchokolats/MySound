package com.vlad.mysound

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response as NpResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.net.InetSocketAddress
import java.net.Proxy

object Net {
    /** true, если активное подключение идёт через VPN */
    fun vpnActive(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /** Прокси: "host:port" (HTTP) или "socks5://host:port". Пусто = напрямую. При VPN — тоже напрямую. */
    fun client(ctx: Context, proxy: String): OkHttpClient {
        val b = OkHttpClient.Builder()
        val p = proxy.trim()
        if (p.isNotEmpty() && !vpnActive(ctx)) {
            val socks = p.startsWith("socks5://")
            val hp = p.removePrefix("socks5://").removePrefix("http://")
            val port = hp.substringAfterLast(":").toIntOrNull()
            if (port != null) {
                val type = if (socks) Proxy.Type.SOCKS else Proxy.Type.HTTP
                b.proxy(Proxy(type, InetSocketAddress.createUnresolved(hp.substringBeforeLast(":"), port)))
            }
        }
        return b.build()
    }
}

class OkDownloader(private val ctx: Context, private val proxy: () -> String) : Downloader() {
    override fun execute(request: NpRequest): NpResponse {
        val method = request.httpMethod()
        val data = request.dataToSend()
        val body = when {
            data != null -> data.toRequestBody()
            method == "POST" -> ByteArray(0).toRequestBody()
            else -> null
        }
        val rb = okhttp3.Request.Builder().url(request.url()).method(method, body)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0")
        for ((k, vs) in request.headers()) {
            rb.removeHeader(k)
            vs.forEach { rb.addHeader(k, it) }
        }
        Net.client(ctx, proxy()).newCall(rb.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("429", request.url())
            return NpResponse(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
        }
    }
}
