package app.morphe.patches.all.misc.microg

import app.morphe.patcher.patch.classDefForEach
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.proxy.mutableTypes.encodedValue.MutableStringEncodedValue
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue

/**
 * Rewrites string initial values of static fields (`static String X = "..."` that d8/R8 kept as
 * encoded values instead of const-string instructions), which `replaceStrings` does not see.
 * Classes under [MICROG_EXTENSION_PACKAGE] are skipped.
 * @return the number of rewritten fields.
 */
internal fun rewriteStaticStringFields(transform: (String) -> String?): Int {
    val candidates = mutableListOf<ClassDef>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(MICROG_EXTENSION_PACKAGE)) return@classDefForEach
        if (classDef.staticFields.any { f ->
                (f.initialValue as? StringEncodedValue)?.value?.let { v -> transform(v)?.let { it != v } } == true
            }) candidates += classDef
    }
    var n = 0
    candidates.forEach { classDef ->
        mutableClassDefBy(classDef).staticFields.forEach { field ->
            val value = field.initialValue as? MutableStringEncodedValue ?: return@forEach
            transform(value.value)?.takeIf { it != value.value }?.let { value.value = it; n++ }
        }
    }
    return n
}
