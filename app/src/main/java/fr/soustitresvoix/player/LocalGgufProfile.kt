package fr.soustitresvoix.player

import java.util.Locale

enum class LocalGgufProfile(val id: String, val label: String) {
    AUTO("auto", "Reconnaître automatiquement le modèle"),
    TRANSLATE_GEMMA("translategemma", "TranslateGemma — profil de traduction"),
    HY_MT("hy-mt", "Hy-MT / Hy-MT2 — profil de traduction"),
    INDEX_TRANSLATE("index-translate", "Index-Translate — profil de traduction"),
    MODEL_TEMPLATE("model-template", "Gabarit intégré au GGUF — formats reconnus")
}

/** Bounded header hints, not a validation of the weights or their linguistic quality. */
data class LocalGgufMetadata(
    val architecture: String = "",
    val name: String = "",
    val basename: String = "",
    val finetune: String = "",
    val fileType: String = "",
    val translationTemplate: Boolean = false,
    val chatTemplateAvailable: Boolean = false
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


    // Publisher LFS SHA-256 values, pinned IndexTeam/Index-Translate-9B-GGUF
    // 645939fbab839a656f1bc39975a872bc8ad749f8. General.name is only "9b".
    const val OFFICIAL_INDEX_Q4 = "9cc6758b0007f4cbf42ea3400768cbff91ae74ef8d640e467158348426d33713"
    const val OFFICIAL_INDEX_Q5 = "46910038bdc6a1c95f18d4e87651ce8df408aa7759757bfac5347bbf69f64439"
    const val OFFICIAL_INDEX_Q6 = "5e47c636c75de6c5065f8550869a744e049451ac591a001968872d5c8b4b7898"
    const val OFFICIAL_INDEX_Q8 = "21c65d2f8402ee03cb90135b29d8b9eceba7d6ed4ef680c8fcede2def77b5184"
    private val verifiedIndex = setOf(OFFICIAL_INDEX_Q4, OFFICIAL_INDEX_Q5, OFFICIAL_INDEX_Q6, OFFICIAL_INDEX_Q8)

    /** Header absence is not runtime incompatibility: defer missing hints to the native open. */
    fun preflight(model: LocalGgufModel, requested: LocalGgufProfile): LocalGgufPrompts.Family? {
        val hints = model.metadata
        if (hints.architecture.isEmpty()) return null
        return when (requested) {
            LocalGgufProfile.TRANSLATE_GEMMA -> requireArchitecture(hints.architecture, "gemma3", LocalGgufPrompts.Family.TRANSLATE_GEMMA)
            LocalGgufProfile.HY_MT -> requireArchitecture(hints.architecture, "hunyuan-dense", LocalGgufPrompts.Family.HY_MT)
            LocalGgufProfile.INDEX_TRANSLATE -> requireArchitecture(hints.architecture, "qwen35", LocalGgufPrompts.Family.INDEX_TRANSLATE)
            LocalGgufProfile.MODEL_TEMPLATE -> if (hints.chatTemplateAvailable) LocalGgufPrompts.Family.MODEL_TEMPLATE else null
            LocalGgufProfile.AUTO -> specialized(hints.architecture, hints.name, model.sha256, hints)
                ?: if (hints.chatTemplateAvailable) LocalGgufPrompts.Family.MODEL_TEMPLATE else null
        }
    }

    /** The runtime's own template capability overrides any bounded header hint. */
    fun select(requested: LocalGgufProfile, architecture: String, runtimeName: String,
        sha256: String, hints: LocalGgufMetadata = LocalGgufMetadata(),
        chatTemplateAvailable: Boolean = hints.chatTemplateAvailable): LocalGgufPrompts.Family {
        if (architecture.isBlank()) throw TranslationFailure(TranslationFailure.Reason.LOCAL_PROFILE)
        val selected = when (requested) {
            LocalGgufProfile.TRANSLATE_GEMMA -> requireArchitecture(architecture, "gemma3", LocalGgufPrompts.Family.TRANSLATE_GEMMA)
            LocalGgufProfile.HY_MT -> requireArchitecture(architecture, "hunyuan-dense", LocalGgufPrompts.Family.HY_MT)
            LocalGgufProfile.INDEX_TRANSLATE -> requireArchitecture(architecture, "qwen35", LocalGgufPrompts.Family.INDEX_TRANSLATE)
            LocalGgufProfile.MODEL_TEMPLATE -> LocalGgufPrompts.Family.MODEL_TEMPLATE
            LocalGgufProfile.AUTO -> specialized(architecture, runtimeName, sha256, hints)
                ?: LocalGgufPrompts.Family.MODEL_TEMPLATE
        }
        if (selected.usesChatTemplate && !chatTemplateAvailable)
            throw TranslationFailure(TranslationFailure.Reason.LOCAL_TEMPLATE)
        return selected
    }

    private fun requireArchitecture(actual: String, expected: String,
        family: LocalGgufPrompts.Family): LocalGgufPrompts.Family {
        if (actual != expected) throw TranslationFailure(TranslationFailure.Reason.LOCAL_PROFILE)
        return family
    }

    private fun specialized(architecture: String, runtimeName: String, sha256: String,
        hints: LocalGgufMetadata): LocalGgufPrompts.Family? {
        // Preserve the established specialized paths before offering a generic template.
        if (sha256 in verifiedHyMt2)
            return requireArchitecture(architecture, "hunyuan-dense", LocalGgufPrompts.Family.HY_MT)
        val names = listOf(runtimeName, hints.name, hints.basename, hints.finetune).map {
            it.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
        }
        if (architecture == "gemma3" && (names.any { "translategemma" in it } || hints.translationTemplate))
            return LocalGgufPrompts.Family.TRANSLATE_GEMMA
        if (architecture == "hunyuan-dense" && names.any { "hymt" in it || "hunyuanmt" in it })
            return LocalGgufPrompts.Family.HY_MT
        if (sha256 in verifiedIndex)
            return requireArchitecture(architecture, "qwen35", LocalGgufPrompts.Family.INDEX_TRANSLATE)
        if (architecture == "qwen35" && names.any { "indextranslate" in it })
            return LocalGgufPrompts.Family.INDEX_TRANSLATE
        return null
    }
}
