package app.morphe.patches.all.misc.microg

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.packageMetadata
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.replaceStrings
import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.patch.resourceXmlOrNull
import app.morphe.patcher.string

const val MICROG_CLONE_DEFAULT_SUFFIX = ".morphe"

/**
 * Installs an app under `<package><suffix>`, next to an original (often preinstalled, Google-signed)
 * copy: renames the package, shared UID, own permissions and provider authorities, including
 * authorities hard-coded in bytecode. Pair it with [universalMicroGSupportPatch]: SPOOFED_PACKAGE_NAME
 * keeps the original name towards microG.
 */
fun microGClonePatch(suffix: () -> String = { MICROG_CLONE_DEFAULT_SUFFIX }) = patch(
    description = "Installs the app as a separate clone package next to the original.",
    default = false,
) {
    // Hard-coded own provider authorities in code must follow the manifest rename, otherwise a clone
    // queries the original app's (protected) provider.
    execute {
        val old = packageMetadata.packageName
        val new = old + suffix()
        val authorities = resourceXml("AndroidManifest.xml").use { m ->
            m.elements("provider").flatMap { it.attribute("android:authorities").orEmpty().split(';') }
                .filter { it.isNotEmpty() && !it.startsWith("@") }.toSet()
        }
        val map = authorities.associateWith { renameAuthority(it, old, new) } +
            // The bare package literal: apps derive authorities from it (`PACKAGE + ".storage"`).
            mapOf(old to new)
        val filters = map.keys.map { string(it) } + string("content://", StringComparisonType.STARTS_WITH)
        val n = replaceStrings(filters) { method, _, value ->
            if (method.definingClass.startsWith("Lapp/morphe/extension/")) return@replaceStrings null
            map[value] ?: map.keys.firstOrNull { value.startsWith("content://$it") }
                ?.let { "content://" + map[it] + value.removePrefix("content://$it") }
        }
        val fields = rewriteStaticStringFields { value ->
            map[value] ?: map.keys.firstOrNull { value.startsWith("content://$it") }
                ?.let { "content://" + map[it] + value.removePrefix("content://$it") }
        }
        println("clone: ${authorities.size} authorities, $n strings and $fields static fields rewritten")
    }
    finalize {
        val old = packageMetadata.packageName
        val new = old + suffix()
        // Authorities and content URIs kept in string resources (Gmail: authority_email_provider,
        // accountprovider_*_uri) must follow the rename too.
        val authorities = resourceXml("AndroidManifest.xml").use { m ->
            m.elements("provider").flatMap { it.attribute("android:authorities").orEmpty().split(';') }
                .filter { it.isNotEmpty() && !it.startsWith("@") }.toSet()
        }
        val strings = resourceXmlOrNull("res/values/strings.xml")?.use { res ->
            res.root.children("string").count { e ->
                val v = e.text
                val auth = authorities.firstOrNull { v == it || v.startsWith("content://$it/") || v == "content://$it" }
                    ?: return@count false
                e.text = v.replaceFirst(auth, renameAuthority(auth, old, new)); true
            }
        } ?: 0
        println("clone: $strings string resources rewritten")
        resourceXml("AndroidManifest.xml").use { manifest ->
            manifest.root.setAttribute("package", new)
            // A re-signed clone can not join the original's shared UID.
            manifest.root.attribute("android:sharedUserId")?.let { manifest.root.setAttribute("android:sharedUserId", "$it" + suffix()) }
            val uses = manifest.elements("uses-permission").toList()
            manifest.elements("permission").forEach {
                val name = it.attribute("android:name").orEmpty()
                if (name.startsWith('.')) return@forEach
                val renamed = if (name.startsWith("$old.")) name.replaceFirst(old, new) else "${new}_$name"
                it.setAttribute("android:name", renamed)
                uses.firstOrNull { u -> u.attribute("android:name") == name }?.setAttribute("android:name", renamed)
            }
            manifest.elements("provider").forEach { p ->
                val auth = p.attribute("android:authorities").orEmpty().split(';').joinToString(";") {
                    if (it.startsWith("@")) it else renameAuthority(it, old, new)
                }
                p.setAttribute("android:authorities", auth)
            }
        }
    }
}

/** `<pkg>` → `<new>`, `<pkg>.x` → `<new>.x`, anything else → `<new>_<authority>` (unique per clone). */
internal fun renameAuthority(authority: String, old: String, new: String) = when {
    authority == old -> new
    authority.startsWith("$old.") -> new + authority.removePrefix(old)
    else -> "${new}_$authority"
}
