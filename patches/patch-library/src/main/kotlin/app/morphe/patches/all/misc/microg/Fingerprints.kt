package app.morphe.patches.all.misc.microg

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string

/**
 * Every PackageManager.getPackageInfo call outside the microG extension.
 */
internal object MicroGGetPackageInfoFingerprint : Fingerprint(
    filters = listOf(
        methodCall(
            definingClass = "Landroid/content/pm/PackageManager;",
            name = "getPackageInfo",
            returnType = "Landroid/content/pm/PackageInfo;",
        )
    ),
    custom = { _, classDef -> !classDef.type.startsWith(MICROG_EXTENSION_PACKAGE) }
)

/**
 * GooglePlayServicesUtilLight.isGooglePlayServicesAvailable(Context, int): int
 * (play-services-basement 18.x). Log strings survive R8 renaming; access flags do not
 * (R8 class merging turns it into an instance method), so they are not matched.
 */
internal object IsGooglePlayServicesAvailableFingerprint : Fingerprint(
    returnType = "I",
    parameters = listOf("Landroid/content/Context;", "I"),
    filters = listOf(
        string(" requires Google Play services, but their signature is invalid."),
    )
)

/**
 * Older play-services-basement builds and Google's own apps (the variant Morphe targets for YouTube).
 */
internal object LegacyIsGooglePlayServicesAvailableFingerprint : Fingerprint(
    returnType = "I",
    parameters = listOf("L", "I"),
    filters = listOf(
        string("MetadataValueReader"),
        string("This should never happen."),
    )
)

/**
 * GooglePlayServicesUtilLight.ensurePlayServicesAvailable(Context, int), which throws when unavailable.
 */
internal object EnsurePlayServicesAvailableFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "I"),
    filters = listOf(
        string("Google Play Services not available"),
    )
)
