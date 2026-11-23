/*
 * SPDX-FileCopyrightText: 2026 microg-universal
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.safetynet

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val ENDPOINT = "https://safebrowsing.googleapis.com/v4/fullHashes:find?key="

/**
 * Safe Browsing v4 client used by SafetyNet lookupUri and the verify-apps scan.
 *
 * It never sends a URL: the URL's host/path expressions are hashed on the device and only
 * 4-byte prefixes go to Google; full hashes in the reply are compared locally and cached for their
 * cacheDuration.
 */
object SafeBrowsing {
    /** SafetyNet threat ids (2 is an alias of 5) to Safe Browsing v4 threat types. */
    private val THREAT_TYPES = mapOf(
        1 to "MALWARE",
        2 to "SOCIAL_ENGINEERING",
        3 to "UNWANTED_SOFTWARE",
        4 to "POTENTIALLY_HARMFUL_APPLICATION",
        5 to "SOCIAL_ENGINEERING",
    )

    fun isSupported(threatType: Int) = threatType in THREAT_TYPES

    private class Cached(val types: Set<String>, val expiresAt: Long)

    private val positive = ConcurrentHashMap<String, Cached>()
    private val negative = ConcurrentHashMap<String, Long>()

    /**
     * @return the subset of [threatTypes] the URL matches.
     * @throws java.io.IOException when the service could not be reached.
     */
    fun lookup(apiKey: String, url: String, threatTypes: Collection<Int>): List<Int> =
        lookupExpressions(apiKey, expressions(url), threatTypes)

    /** Lookup of raw `host/path` expressions (the verify-apps APK digest convention). */
    fun lookupExpressions(apiKey: String, expressions: List<String>, threatTypes: Collection<Int>): List<Int> {
        val wanted = threatTypes.mapNotNull { THREAT_TYPES[it] }.toSet()
        if (wanted.isEmpty() || expressions.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val hashes = expressions.map { sha256(it) }
        val matched = mutableSetOf<String>()
        val prefixesToQuery = mutableSetOf<String>()
        for (hash in hashes) {
            val key = hash.b64()
            val cached = positive[key]
            if (cached != null && cached.expiresAt > now) {
                matched += cached.types
                continue
            }
            val prefix = hash.copyOf(4).b64()
            if ((negative[prefix] ?: 0) <= now) prefixesToQuery += prefix
        }
        if (prefixesToQuery.isNotEmpty()) {
            val response = query(apiKey, wanted, prefixesToQuery)
            val fullHashes = hashes.map { it.b64() }.toSet()
            val negativeUntil = now + parseDuration(response.optString("negativeCacheDuration"), 300_000)
            prefixesToQuery.forEach { negative[it] = negativeUntil }
            val matches = response.optJSONArray("matches") ?: JSONArray()
            for (i in 0 until matches.length()) {
                val m = matches.getJSONObject(i)
                val full = m.optJSONObject("threat")?.optString("hash") ?: continue
                val type = m.optString("threatType")
                val until = now + parseDuration(m.optString("cacheDuration"), 300_000)
                positive.merge(full, Cached(setOf(type), until)) { a, b -> Cached(a.types + b.types, minOf(a.expiresAt, b.expiresAt)) }
                if (full in fullHashes) matched += type
            }
        }
        return threatTypes.filter { THREAT_TYPES[it] in matched }.distinct()
    }

    private fun query(apiKey: String, threatTypes: Set<String>, prefixes: Set<String>): JSONObject {
        val body = JSONObject()
            .put("client", JSONObject().put("clientId", "microg").put("clientVersion", "1"))
            .put("clientStates", JSONArray())
            .put("threatInfo", JSONObject()
                .put("threatTypes", JSONArray(threatTypes.toList()))
                .put("platformTypes", JSONArray(listOf("ANDROID")))
                .put("threatEntryTypes", JSONArray(listOf("URL")))
                .put("threatEntries", JSONArray(prefixes.map { JSONObject().put("hash", it) })))
        val connection = URL(ENDPOINT + apiKey).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw java.io.IOException("Safe Browsing HTTP $code: ${text.take(200)}")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    /** "300.5s" -> millis */
    private fun parseDuration(value: String?, default: Long): Long =
        value?.removeSuffix("s")?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: default

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
    private fun ByteArray.b64() = Base64.encodeToString(this, Base64.NO_WRAP)

    // ---- URL canonicalization and expressions (Safe Browsing v4 "Hashing" rules) ----

    internal fun expressions(rawUrl: String): List<String> {
        val (host, path, query) = canonicalize(rawUrl) ?: return emptyList()
        val hosts = linkedSetOf(host)
        if (!host.isIpv4()) {
            val parts = host.split('.')
            val start = maxOf(1, parts.size - 5)
            for (i in start until parts.size - 1) hosts += parts.subList(i, parts.size).joinToString(".")
        }
        val paths = linkedSetOf<String>()
        if (query != null) paths += "$path?$query"
        paths += path
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        // Directory prefixes only: the last component of a file path is never turned into "x/".
        val dirs = if (path.endsWith("/")) segments else segments.dropLast(1)
        var prefix = "/"
        paths += prefix
        for (segment in dirs.take(3)) {
            prefix += "$segment/"
            if (paths.size >= 6) break
            if (prefix != path) paths += prefix
        }
        return hosts.take(5).flatMap { h -> paths.map { p -> h + p } }.distinct()
    }

    private fun String.isIpv4() = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(this)

    private fun unescape(s: String): String {
        var current = s
        repeat(10) {
            val next = runCatching { URLDecoder.decode(current.replace("+", "%2B"), "ISO-8859-1") }.getOrDefault(current)
            if (next == current) return current
            current = next
        }
        return current
    }

    private fun escape(s: String) = buildString {
        for (b in s.toByteArray(Charsets.ISO_8859_1)) {
            val c = b.toInt() and 0xff
            if (c <= 0x20 || c >= 0x7f || c == '#'.code || c == '%'.code) append(String.format(Locale.ROOT, "%%%02X", c))
            else append(c.toChar())
        }
    }

    private fun canonicalize(rawUrl: String): Triple<String, String, String?>? {
        var url = rawUrl.trim().replace(Regex("[\\t\\r\\n]"), "")
        url = url.substringBefore('#')
        url = unescape(url)
        if (!url.contains("://")) url = "http://$url"
        val afterScheme = url.substringAfter("://")
        val authority = afterScheme.substringBefore('/').substringBefore('?')
        var rest = afterScheme.removePrefix(authority)
        var host = authority.substringAfterLast('@').substringBefore(':').lowercase(Locale.ROOT)
            .trim('.').replace(Regex("\\.{2,}"), ".")
        if (host.isEmpty()) return null
        host = normalizeIp(host) ?: host
        val query = if ('?' in rest) rest.substringAfter('?') else null
        rest = rest.substringBefore('?')
        val stack = ArrayDeque<String>()
        for (segment in rest.split('/')) {
            when (segment) {
                "", "." -> {}
                ".." -> stack.removeLastOrNull()
                else -> stack.addLast(segment)
            }
        }
        var path = "/" + stack.joinToString("/")
        if ((rest.endsWith("/") || rest.endsWith("/.") || rest.endsWith("/..")) && path != "/") path += "/"
        return Triple(escape(host), escape(path), query?.let { escape(it) })
    }

    /** Decimal, octal and hex IPv4 forms ("0x7f.1", "2130706433") -> dotted quad. */
    private fun normalizeIp(host: String): String? {
        val parts = host.split('.')
        if (parts.size > 4) return null
        val values = parts.map { p ->
            when {
                p.startsWith("0x", true) -> p.drop(2).toLongOrNull(16)
                p.length > 1 && p.startsWith("0") -> p.drop(1).toLongOrNull(8)
                else -> p.toLongOrNull()
            } ?: return null
        }
        var ip = 0L
        for ((i, v) in values.withIndex()) {
            if (i < values.size - 1) {
                if (v > 255) return null
                ip = ip or (v shl (8 * (3 - i)))
            } else {
                if (v >= (1L shl (8 * (4 - i)))) return null
                ip = ip or v
            }
        }
        return (3 downTo 0).joinToString(".") { ((ip shr (8 * it)) and 0xff).toString() }
    }

    fun matchesJson(types: List<Int>): String = JSONObject().apply {
        if (types.isNotEmpty()) put("matches", JSONArray(types.map { JSONObject().put("threat_type", it.toString()) }))
    }.toString()
}
