import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patches.all.misc.microg.MicroGSupportSettings
import app.morphe.patches.all.misc.microg.universalMicroGSupportPatch
import kotlinx.coroutines.runBlocking
import java.io.File

/** usage: <input.apk> <out dir> [vendor] — writes patched classes*.dex and resources.apk. */
fun main(args: Array<String>) {
    val apk = File(args[0])
    val out = File(args[1]).apply { deleteRecursively(); mkdirs() }
    val vendor = args.getOrElse(2) { "app.revanced" }
    val patch = universalMicroGSupportPatch(MicroGSupportSettings(vendor = { vendor }))
    var failed = false
    Patcher(PatcherConfig(apk, File(out, "tmp"))).use { patcher ->
        patcher += setOf(patch)
        runBlocking {
            patcher().collect { r ->
                println("PATCH ${r.patch} -> ${r.exception?.let { "FAILED: ${it.stackTraceToString()}" } ?: "OK"}")
                if (r.exception != null) failed = true
            }
        }
        val result = patcher.get()
        result.dexFiles.forEach { dex -> File(out, dex.name).outputStream().use { dex.stream.copyTo(it) } }
        result.resources.resourcesApk?.copyTo(File(out, "resources.apk"), overwrite = true)
        println("dex=${result.dexFiles.map { it.name }} resources=${result.resources.resourcesApk} other=${result.resources.otherResources} delete=${result.resources.deleteResources}")
    }
    if (failed) kotlin.system.exitProcess(1)
}
