package app.dirthead.iptv.ui.player

import okhttp3.HttpUrl.Companion.defaultPort
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Many IPTV panels check [Referer] / [Accept]; VLC sends similar permissive headers.
 */
internal class IptvPlaybackHeadersInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val url = req.url
        if (url.host.isEmpty()) return chain.proceed(req)
        val builder = req.newBuilder()
        if (req.header("Accept") == null) {
            builder.header("Accept", "*/*")
        }
        if (req.header("Referer") == null) {
            val port = url.port
            val defaultP = defaultPort(url.scheme)
            val portPart = if (port == defaultP || port == -1) "" else ":$port"
            val origin = "${url.scheme}://${url.host}$portPart"
            builder.header("Referer", "$origin/")
        }
        return chain.proceed(builder.build())
    }
}
