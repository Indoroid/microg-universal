package app.morphe.patches.all.misc.microg

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.packageMetadata
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.replaceStrings
import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.string
import app.morphe.patcher.util.matchAllMethodIndicesForEach
import app.morphe.patcher.util.proxy.mutableTypes.encodedValue.MutableStringEncodedValue
import app.morphe.patcher.util.returnEarly
import app.morphe.patches.all.misc.gms.gmsCorePackageQueriesPatch
import app.morphe.util.getEndEntityCertificate
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.security.MessageDigest

internal const val MICROG_EXTENSION_PACKAGE = "Lapp/morphe/extension/microg/"
internal const val MICROG_BRIDGE_CLASS = "${MICROG_EXTENSION_PACKAGE}MicroGBridge;"
private const val MICROG_INIT_PROVIDER = "app.morphe.extension.microg.MicroGInitProvider"

/** GmsCompat-Stub app package (see gmscompat-stub/). */
const val GMSCOMPAT_STUB_PACKAGE = "app.morphe.gmscompat"

private const val GOOGLE_PREFIX = "com.google"
private const val GMS_PACKAGE = "com.google.android.gms"
private const val PACKAGE_INFO_FLAGS = "Landroid/content/pm/PackageManager\$PackageInfoFlags;"

/** v0 is only free at method entry if the method has at least one non-parameter register. */
private fun Method.hasFreeRegister(): Boolean {
    val registers = implementation?.registerCount ?: return false
    val parameterRegisters = parameterTypes.map { it.toString() }.sumOf { if (it == "J" || it == "D") 2L else 1L }.toInt() +
        if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1
    return registers > parameterRegisters
}

/** Default vendor prefix: MicroG-RE / ReVanced GmsCore (app.revanced.android.gms). */
const val MICROG_DEFAULT_VENDOR = "app.revanced"

/** Vendor prefixes offered to users. com.google means microG installed under Google's own name. */
val MICROG_VENDORS = mapOf(
    "MicroG-RE / ReVanced GmsCore (app.revanced.android.gms)" to MICROG_DEFAULT_VENDOR,
    "Vanced microG (com.mgoogle.android.gms)" to "com.mgoogle",
    "microG as com.google.android.gms (needs signature spoofing ROM)" to GOOGLE_PREFIX,
)

/**
 * Settings of [universalMicroGSupportPatch], read when the patch executes so a wrapping patch can
 * back them with its own options.
 */
class MicroGSupportSettings(
    val vendor: () -> String = { MICROG_DEFAULT_VENDOR },
    val spoofIdentity: () -> Boolean = { true },
    val forceAvailable: () -> Boolean = { true },
    val useStub: () -> Boolean = { true },
    val prompts: () -> Boolean = { true },
)

/**
 * Makes any app that uses Google Play services work with a user-installed microG, without root and
 * without app-specific fingerprints. See docs/PLAN.md of the microg-universal project.
 */
fun universalMicroGSupportPatch(settings: MicroGSupportSettings = MicroGSupportSettings()) = patch(
    default = false,
) {
    dependsOn(gmsCorePackageQueriesPatch)
    extendWith("generated/extensions/microg.dex")

    execute {
        val prefix = settings.vendor()
        val renaming = prefix != GOOGLE_PREFIX
        val gmsPackage = "$prefix.android.gms"
        fun rename(name: String) = prefix + name.removePrefix(GOOGLE_PREFIX)

        // region Bytecode strings (one pass over all classes).

        val renamed: Map<String, String> = if (renaming) buildMap {
            put(GOOGLE_PREFIX, prefix) // Account type.
            put(GMS_PACKAGE, gmsPackage)
            (MicroGConstants.ACTIONS + MicroGConstants.PERMISSIONS + MicroGConstants.AUTHORITIES)
                .forEach { put(it, rename(it)) }
        } else emptyMap()

        val contentUriPrefixes = MicroGConstants.AUTHORITIES.map { "content://$it" }
        val placeholders = mapOf(
            "MICROG_GMS_PACKAGE_PLACEHOLDER" to gmsPackage,
            "MICROG_STUB_ENABLED_PLACEHOLDER" to settings.useStub().toString(),
            "MICROG_PROMPTS_ENABLED_PLACEHOLDER" to settings.prompts().toString(),
        )

        val filters = buildList {
            (renamed.keys + placeholders.keys).forEach { add(string(it)) }
            if (renaming) add(string("content://$GOOGLE_PREFIX", StringComparisonType.STARTS_WITH))
        }
        replaceStrings(filters) { method, _, value ->
            if (method.definingClass.startsWith(MICROG_EXTENSION_PACKAGE)) return@replaceStrings placeholders[value]
            renamed[value] ?: contentUriPrefixes.firstOrNull { value.startsWith(it) }?.let { uriPrefix ->
                "content://" + rename(uriPrefix.removePrefix("content://")) + value.removePrefix(uriPrefix)
            }
        }

        // Static `String` fields keep their initial value outside the bytecode.
        if (renaming) rewriteStaticStringFields { value ->
            renamed[value] ?: contentUriPrefixes.firstOrNull { value.startsWith(it) }?.let { uriPrefix ->
                "content://" + rename(uriPrefix.removePrefix("content://")) + value.removePrefix(uriPrefix)
            }
        }

        // d8 may store the placeholders as static field initial values instead of const-strings.
        mutableClassDefBy(MICROG_BRIDGE_CLASS).staticFields.forEach { field ->
            val value = field.initialValue as? MutableStringEncodedValue ?: return@forEach
            placeholders[value.value]?.let { value.value = it }
        }

        // endregion

        // region GmsCore signature as seen by the client library (obfuscation-proof).

        MicroGGetPackageInfoFingerprint.matchAllMethodIndicesForEach(requireMatches = false) { index ->
            val instruction = getInstruction(index)
            val reference = (instruction as ReferenceInstruction).reference as MethodReference
            val params = reference.parameterTypes.map { it.toString() }
            if (params.size != 2 || params[0] != "Ljava/lang/String;" ||
                (params[1] != "I" && params[1] != PACKAGE_INFO_FLAGS)
            ) return@matchAllMethodIndicesForEach

            val target = "$MICROG_BRIDGE_CLASS->getPackageInfo(Landroid/content/pm/PackageManager;" +
                "${params[0]}${params[1]})Landroid/content/pm/PackageInfo;"
            val smali = when (instruction.opcode) {
                Opcode.INVOKE_VIRTUAL -> {
                    val r = instruction as FiveRegisterInstruction
                    "invoke-static { v${r.registerC}, v${r.registerD}, v${r.registerE} }, $target"
                }
                Opcode.INVOKE_VIRTUAL_RANGE -> {
                    val r = instruction as RegisterRangeInstruction
                    "invoke-static/range { v${r.startRegister} .. v${r.startRegister + r.registerCount - 1} }, $target"
                }
                else -> return@matchAllMethodIndicesForEach
            }
            replaceInstruction(index, smali)
        }

        // endregion

        // region Availability checks (belt and braces for libraries that bypass PackageManager calls).

        if (settings.forceAvailable()) {
            listOf(IsGooglePlayServicesAvailableFingerprint, LegacyIsGooglePlayServicesAvailableFingerprint)
                .flatMap { it.matchAllOrNull().orEmpty() }
                .map { it.method }
                .distinct()
                .filter { it.hasFreeRegister() }
                .forEach { method ->
                    method.addInstructionsWithLabels(
                        0,
                        """
                            invoke-static { }, $MICROG_BRIDGE_CLASS->forcePlayServicesAvailable()Z
                            move-result v0
                            if-eqz v0, :check_original
                            const/4 v0, 0x0
                            return v0
                            :check_original
                            nop
                        """
                    )
                }

            EnsurePlayServicesAvailableFingerprint.matchAllOrNull().orEmpty().map { it.method }.distinct()
                .filter { it.hasFreeRegister() }
                .forEach { method ->
                    method.addInstructionsWithLabels(
                        0,
                        """
                            invoke-static { }, $MICROG_BRIDGE_CLASS->forcePlayServicesAvailable()Z
                            move-result v0
                            if-eqz v0, :check_original
                            return-void
                            :check_original
                            nop
                        """
                    )
                }
        }

        // endregion

        // region Manifest.

        val originalPackage = packageMetadata.packageName
        val certSha1 = runCatching {
            MessageDigest.getInstance("SHA-1")
                .digest(getEndEntityCertificate(packageMetadata.signingCertificates).encoded)
                .joinToString("") { "%02x".format(it) }
        }.getOrNull()

        resourceXml("AndroidManifest.xml").use { manifest ->
            val manifestPackage = manifest.root.attribute("package") ?: originalPackage

            if (renaming) {
                manifest.elements().forEach { element ->
                    element.attributeNames.forEach { name ->
                        val value = element.attribute(name) ?: return@forEach
                        // Own manifest meta-data keys (com.google.android.gms.version, ...) stay as they are.
                        if (element.tag == "meta-data" && name == "android:name") return@forEach
                        val replaced = renamed[value]
                            ?: value.replace("$GOOGLE_PREFIX.android.c2dm", "$prefix.android.c2dm")
                        if (replaced != value) element.setAttribute(name, replaced)
                    }
                }
            }

            // Installing must not fail on devices without Google's shared libraries.
            manifest.elements("uses-library")
                .filter { it.attribute("android:name")?.startsWith("$GOOGLE_PREFIX.") == true }
                .forEach { it.setAttribute("android:required", "false") }

            val queries = manifest.elements("queries").firstOrNull() ?: manifest.root.appendChild("queries")
            listOf(gmsPackage, "com.android.vending", GMSCOMPAT_STUB_PACKAGE).forEach { pkg ->
                queries.appendChild("package") { setAttribute("android:name", pkg) }
            }

            val application = manifest.elements("application").first()
            if (settings.spoofIdentity()) {
                application.appendChild("meta-data") {
                    setAttribute("android:name", "$gmsPackage.SPOOFED_PACKAGE_NAME")
                    setAttribute("android:value", originalPackage)
                }
                if (certSha1 != null) application.appendChild("meta-data") {
                    setAttribute("android:name", "$gmsPackage.SPOOFED_PACKAGE_SIGNATURE")
                    setAttribute("android:value", certSha1)
                }
            }
            application.appendChild("meta-data") {
                setAttribute("android:name", "app.revanced.MICROG_PACKAGE_NAME")
                setAttribute("android:value", gmsPackage)
            }
            application.appendChild("provider") {
                setAttribute("android:name", MICROG_INIT_PROVIDER)
                setAttribute("android:authorities", "$manifestPackage.morphe-microg-init")
                setAttribute("android:exported", "false")
                setAttribute("android:initOrder", "1000")
            }
        }

        // endregion
    }
}
