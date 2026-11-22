import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.patch.packageMetadata
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.patch.replaceStrings
import app.morphe.patcher.string
import app.morphe.patcher.StringComparisonType
import app.morphe.patches.all.misc.microg.MicroGSupportSettings
import app.morphe.patches.all.misc.microg.universalMicroGSupportPatch
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Test-only clone (same manifest logic as Morphe's cloneAppPatch), so an app can be installed next to a
 * preinstalled Google-signed system copy of itself.
 */
private val testClonePatch = patch {
    // Hard-coded own provider authorities in code must follow the manifest rename, otherwise a clone
    // queries the original app's (protected) provider.
    execute {
        val old = packageMetadata.packageName
        val new = "$old.morphe"
        val authorities = resourceXml("AndroidManifest.xml").use { m ->
            m.elements("provider").flatMap { it.attribute("android:authorities").orEmpty().split(';') }
                .filter { it.isNotEmpty() && !it.startsWith("@") }.toSet()
        }
        fun renamed(a: String) = if (a.startsWith("$old.")) a.replaceFirst(old, new) else "${new}_$a"
        val map = authorities.associateWith(::renamed)
        val filters = map.keys.map { string(it) } + string("content://", StringComparisonType.STARTS_WITH)
        val n = replaceStrings(filters) { method, _, value ->
            if (method.definingClass.startsWith("Lapp/morphe/extension/")) return@replaceStrings null
            map[value] ?: map.keys.firstOrNull { value.startsWith("content://$it") }
                ?.let { "content://" + map[it] + value.removePrefix("content://$it") }
        }
        println("clone: ${map.size} authorities, $n strings rewritten")
    }
    finalize {
        val old = packageMetadata.packageName
        val new = "$old.morphe"
        resourceXml("AndroidManifest.xml").use { manifest ->
            manifest.root.setAttribute("package", new)
            // A re-signed clone can not join the original's shared UID.
            manifest.root.attribute("android:sharedUserId")?.let { manifest.root.setAttribute("android:sharedUserId", "$it.morphe") }
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
                    when {
                        it.startsWith("$old.") -> it.replaceFirst(old, new)
                        it.startsWith("@") -> it
                        else -> "${new}_$it"
                    }
                }
                p.setAttribute("android:authorities", auth)
            }
        }
    }
}

/** usage: <input.apk> <out dir> [vendor] [clone] — writes patched classes*.dex and resources.apk. */
fun main(args: Array<String>) {
    val apk = File(args[0])
    val out = File(args[1]).apply { deleteRecursively(); mkdirs() }
    val vendor = args.getOrElse(2) { "app.revanced" }
    val clone = args.getOrNull(3) == "clone"
    val patches = buildSet {
        add(universalMicroGSupportPatch(MicroGSupportSettings(vendor = { vendor })))
        if (clone) add(testClonePatch)
    }
    var failed = false
    Patcher(PatcherConfig(apk, File(out, "tmp"))).use { patcher ->
        patcher += patches
        runBlocking {
            patcher().collect { r ->
                println("PATCH ${r.patch} -> ${r.exception?.let { "FAILED: ${it.stackTraceToString()}" } ?: "OK"}")
                if (r.exception != null) failed = true
            }
        }
        val result = patcher.get()
        result.dexFiles.forEach { dex -> File(out, dex.name).outputStream().use { dex.stream.copyTo(it) } }
        result.resources.resourcesApk?.copyTo(File(out, "resources.apk"), overwrite = true)
        println("dex=${result.dexFiles.map { it.name }} resources=${result.resources.resourcesApk}")
    }
    if (failed) kotlin.system.exitProcess(1)
}
