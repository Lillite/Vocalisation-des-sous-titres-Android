package fr.soustitresvoix.player

import org.json.JSONObject

/** Coarse monotonic timings for one successful native open, including fallback attempts. */
data class LocalGgufLoadProfile(
    val backendInitMs: Long,
    val openClPrepareMs: Long,
    val modelLoadMs: Long,
    val contextInitMs: Long,
    val openTotalMs: Long,
    val loadAttempts: Int
) {
    init {
        val phases = listOf(backendInitMs, openClPrepareMs, modelLoadMs, contextInitMs)
        require(phases.all { it in 0..MAX_MS } && openTotalMs in 0..MAX_MS && loadAttempts in 1..2)
        require(phases.sum() <= openTotalMs)
    }

    fun render(): String = buildString {
        append("Profil natif d’ouverture — total : "); append(openTotalMs); append(" ms")
        append("\nInitialisation du moteur : "); append(backendInitMs); append(" ms")
        append("\nPréparation de l’accès OpenCL : "); append(openClPrepareMs); append(" ms")
        append("\nAppels de chargement du modèle cumulés : "); append(modelLoadMs); append(" ms")
        append("\nCréations du contexte cumulées : "); append(contextInitMs); append(" ms")
        append("\nTentatives de chargement : "); append(loadAttempts)
        append("\nLe total inclut ces étapes et les autres opérations d’ouverture ; ne pas lui additionner les étapes ni le chargement indiqué plus haut.")
        append(" Les transferts, allocations et compilations internes ne sont pas chronométrés séparément. Une durée de 0 ms peut correspondre à une étape absente ou inférieure à une milliseconde.")
    }

    companion object {
        private const val MAX_MS = 86_400_000L
        private val FIELDS = setOf("backendInitMs", "openClPrepareMs", "modelLoadMs", "contextInitMs", "openTotalMs", "loadAttempts")

        /** Absent means unmeasured; null, coercible strings and partial profiles are invalid. */
        internal fun fromResponse(response: JSONObject): LocalGgufLoadProfile? {
            if (!response.has("loadProfile")) return null
            val value = response.get("loadProfile") as? JSONObject ?: error("Invalid load profile")
            require(value.keys().asSequence().toSet() == FIELDS)
            fun integral(name: String): Long = when (val number = value.get(name)) {
                is Int -> number.toLong()
                is Long -> number
                else -> error("Non-integral load profile field")
            }
            val attempts = integral("loadAttempts")
            require(attempts in 1..2)
            return LocalGgufLoadProfile(integral("backendInitMs"), integral("openClPrepareMs"),
                integral("modelLoadMs"), integral("contextInitMs"), integral("openTotalMs"), attempts.toInt())
        }
    }
}
