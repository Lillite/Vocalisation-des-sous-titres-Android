package fr.soustitresvoix.player

import java.util.Locale

enum class LocalGgufProfile(val id: String, val label: String) {
    AUTO("auto", "Reconnaître automatiquement le modèle"),
    TRANSLATE_GEMMA("translategemma", "TranslateGemma — profil de traduction"),
    HY_MT("hy-mt", "Hy-MT / Hy-MT2 — profil de traduction")
}

/** Bounded header hints, not a validation of the weights or their linguistic quality. */
data class LocalGgufMetadata(
    val architecture: String = "",
    val name: String = "",
    val basename: String = "",
    val finetune: String = "",
    val fileType: String = "",
    val translationTemplate: Boolean = false
) {
    override fun toString(): String = "LocalGgufMetadata(architecture=$architecture, names=hidden)"
}

internal object LocalGgufProfiles {
    // Public publisher SHA-256 of HY-MT2-7B-Q8_0.gguf. This official release's
    // actual general.name is Global_Step_560, not the repository's model name.
    const val OFFICIAL_HY_MT2_Q8 = "58b3ad55dd6f6fa08c695cddc34fb5f8f708a844f78ae10508071914b0ed67c0"
    const val OFFICIAL_HY_MT2_Q6 = "88ef0aba59952a4cfe4be36cb5baf797dbb370bc60e9dcbd7297036021e52831"
    const val OFFICIAL_HY_MT2_Q4 = "9f96256500f3fc1ab4d64336b58f52a949a95ad7516b0c229476eef782f9f77b"
    private val verifiedHyMt2 = setOf(OFFICIAL_HY_MT2_Q8, OFFICIAL_HY_MT2_Q6, OFFICIAL_HY_MT2_Q4)

    fun preflight(model: LocalGgufModel, requested: LocalGgufProfile): LocalGgufPrompts.Family? =
        if (model.metadata.architecture.isEmpty()) null
        else select(requested, model.metadata.architecture, model.metadata.name, model.sha256, model.metadata)

    fun select(requested: LocalGgufProfile, architecture: String, runtimeName: String,
        sha256: String, hints: LocalGgufMetadata = LocalGgufMetadata()): LocalGgufPrompts.Family {
        fun requireArchitecture(expected: String, family: LocalGgufPrompts.Family): LocalGgufPrompts.Family {
            if (architecture != expected) throw TranslationFailure(TranslationFailure.Reason.LOCAL_PROFILE)
            return family
        }
        when (requested) {
            LocalGgufProfile.TRANSLATE_GEMMA -> return requireArchitecture("gemma3", LocalGgufPrompts.Family.TRANSLATE_GEMMA)
            LocalGgufProfile.HY_MT -> return requireArchitecture("hunyuan-dense", LocalGgufPrompts.Family.HY_MT)
            LocalGgufProfile.AUTO -> Unit
        }
        if (sha256 in verifiedHyMt2)
            return requireArchitecture("hunyuan-dense", LocalGgufPrompts.Family.HY_MT)
        val names = listOf(runtimeName, hints.name, hints.basename, hints.finetune).map {
            it.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
        }
        if (architecture == "gemma3" && (names.any { "translategemma" in it } || hints.translationTemplate))
            return LocalGgufPrompts.Family.TRANSLATE_GEMMA
        if (architecture == "hunyuan-dense" && names.any { "hymt" in it || "hunyuanmt" in it })
            return LocalGgufPrompts.Family.HY_MT
        throw TranslationFailure(TranslationFailure.Reason.LOCAL_PROFILE)
    }
}
