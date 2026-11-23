/*
 * SPDX-FileCopyrightText: 2026 microg-universal
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.common

import android.content.Context
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Process-wide policy for microG's own connections to Google servers ("Allow connecting to Google
 * servers" / "Connect via Tor" in the privacy settings). Installed as the default [ProxySelector], so it
 * covers HttpURLConnection, Volley and OkHttp clients in every microG process:
 * - blocked: Google hosts get an unreachable proxy and fail immediately;
 * - Tor: Google hosts go through Orbot's SOCKS port (127.0.0.1:9050).
 * Other hosts (e.g. location services) are untouched.
 */
object GoogleNetworkPolicy {
    private const val TAG = "GmsNetworkPolicy"
    private const val PREFS = "network_policy"
    const val KEY_ALLOW_GOOGLE = "network_google_enabled"
    const val KEY_USE_TOR = "network_google_use_tor"

    private val GOOGLE_SUFFIXES = listOf(
        "google.com", "googleapis.com", "gstatic.com", "googleusercontent.com", "ggpht.com",
        "gvt1.com", "gvt2.com", "gvt3.com", "googlevideo.com", "google-analytics.com", "doubleclick.net",
        "app-measurement.com", "firebaseio.com", "googlesyndication.com", "youtube.com", "android.com",
        "mtalk.google.com"
    )

    private val BLOCKED = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", 1))
    private val TOR = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", 9050))

    @Volatile
    private var installed = false

    @Suppress("DEPRECATION")
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS)

    fun isGoogleAllowed(context: Context) = prefs(context).getBoolean(KEY_ALLOW_GOOGLE, true)
    fun isTorEnabled(context: Context) = prefs(context).getBoolean(KEY_USE_TOR, false)
    fun setGoogleAllowed(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_ALLOW_GOOGLE, value).commit()
    fun setTorEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_USE_TOR, value).commit()

    fun isGoogleHost(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return GOOGLE_SUFFIXES.any { h == it || h.endsWith(".$it") }
    }

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        val app = context.applicationContext
        val fallback = ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> {
                if (uri != null && isGoogleHost(uri.host)) {
                    if (!isGoogleAllowed(app)) return listOf(BLOCKED)
                    if (isTorEnabled(app)) return listOf(TOR)
                }
                return fallback?.select(uri) ?: listOf(Proxy.NO_PROXY)
            }

            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
                if (uri != null && isGoogleHost(uri.host) && (!isGoogleAllowed(app) || isTorEnabled(app))) {
                    Log.d(TAG, "Connection to ${uri.host} not made: ${if (!isGoogleAllowed(app)) "blocked by settings" else "Tor unreachable"}")
                    return
                }
                fallback?.connectFailed(uri, sa, ioe)
            }
        })
        installed = true
    }
}
