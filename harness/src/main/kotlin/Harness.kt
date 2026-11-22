import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.patch.packageMetadata
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml
import app.morphe.patches.all.misc.microg.MicroGSupportSettings
import app.morphe.patches.all.misc.microg.universalMicroGSupportPatch
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Test-only clone (same manifest logic as Morphe's cloneAppPatch), so an app can be installed next to a
 * preinstalled Google-signed system copy of itself.
 */
private val testClonePatch = patch {
    finalize {
        val old = packageMetadata.packageName
        val new = "$old.morphe"
        resourceXml("AndroidManifest.xml").use { manifest ->
            manifest.root.setAttribute("package", new)
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
