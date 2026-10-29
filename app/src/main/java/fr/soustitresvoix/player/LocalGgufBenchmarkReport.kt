package fr.soustitresvoix.player

import java.util.Locale

/** A complete bounded synthetic report; no subtitle file, URI or private context is accepted. */
internal object LocalGgufBenchmarkReport {
    private fun rate(tokens: Long, milliseconds: Long): String = if (milliseconds > 0)
        String.format(Locale.FRANCE, "%.2f tokens/s", tokens * 1000.0 / milliseconds) else "indisponible (durée nulle)"
    private fun yes(value: Boolean) = if (value) "oui" else "non"
    private fun fresh(value: LocalGgufBenchmark.PlaybackObservation?, readAtMs: Long): Boolean =
        value != null && value.observedAtMs > 0 && readAtMs >= value.observedAtMs && readAtMs - value.observedAtMs <= 2_000

    fun render(state: LocalGgufBenchmark.State): String = buildString {
        val varied = state.options.scenario != LocalGgufBenchmark.Scenario.SHORT
        val contextComparison = state.options.scenario == LocalGgufBenchmark.Scenario.CONTEXT_POLICIES
        append(when {
            contextComparison -> "Comparatif local GPU — stratégies de contexte\n"
            varied -> "Comparatif local CPU / GPU — série synthétique de sous-titres\n"
            else -> "Comparatif local CPU / GPU — texte synthétique intégré\n"
        })
        append(state.status)
        if (state.modelSha256.isEmpty()) { append("\nAucun modèle n’a été exécuté."); return@buildString }
        state.platform?.let { append('\n'); append(it.render()) }
        append("\nModèle : "); append(state.modelBytes); append(" octets ; SHA-256 "); append(state.modelSha256)
        append("\nProfil demandé : "); append(state.profile.label); append(" ; threads CPU demandés : "); append(state.threads)
        if (varied) {
            val cues = LocalGgufBenchmark.cues(state.options)
            append("\nCorpus : "); append(if (contextComparison && state.options.qualityCorpus) LocalGgufQualityCorpus.VERSION
                else LocalGgufBenchmarkCorpus.VERSION)
            append(" ; "); append(cues.size); append(" occurrences ; "); append(cues.map { it.source }.distinct().size)
            append(" textes distincts. Identifiants et minutages synthétiques conservés, répétitions et chevauchements inclus.")
            if (contextComparison) append("\nTrois stratégies explicites sont comparées : deux échanges précédents, occurrence seule, puis blocs de six occurrences. Chaque stratégie recommence avec une session distincte et uniquement ses propres traductions terminées. Aucune stratégie n’est sélectionnée automatiquement comme meilleure.")
            else { append("\nStratégie de contexte demandée : "); append(state.options.contextPolicy.label) }
            append("\nLe contexte est reconstruit uniquement à partir de paires source/traduction complètes de la même session. Des sorties différentes modifient aussi les contextes suivants ; les entrées en tokens ne sont donc pas supposées identiques. Les limites natives peuvent retirer les paires anciennes entières sans tronquer la source courante.")
        } else { append("\nSource anglaise fixe : "); append(LocalGgufBenchmark.SOURCE) }
        append("\nCible : français ; contexte personnel vide ; mêmes poids, profil et limites de génération pour tous les passages.")
        if (contextComparison) append("\nOrdre fixe des stratégies : deux échanges précédents, occurrence seule, blocs de six. GPU OpenCL strict pour chacune ; aucun passage CPU ni réouverture supplémentaire. Cet ordre, le cache système et la chauffe peuvent influencer la comparaison.")
        else append(if (state.options.order == LocalGgufBenchmark.Order.CPU_THEN_GPU)
            "\nOrdre fixe : CPU puis GPU OpenCL strict." else "\nOrdre fixe : GPU OpenCL strict puis CPU.")
        append(if (varied) " Une session et un chargement par série ; chaque occurrence est traitée dans son ordre."
            else " Deux passages identiques par mode : premier passage puis réutilisation éventuelle du préfixe.")
        if (state.options.reopenForLoad && !contextComparison) append("\nRéouverture demandée : après chaque série libérée, une nouvelle session traduit seulement la première occurrence. Les caches ne sont pas effacés ; ceci n’est pas une mesure garantie à cache froid.")
        append("\nLes durées d’appel et de mode comprennent déjà le chargement éventuel ; sa mesure séparée détaille le total.")
        append("\nLes modèles ne sont jamais chargés simultanément. L’ordre, le cache système, la chauffe et les autres applications peuvent influencer ces mesures. Ces mesures ne prouvent pas la qualité sur un film.")
        append("\nLes réponses validées le sont structurellement : une réponse reçue et terminée par occurrence. La conservation des identifiants et minutages ne prouve pas la fidélité sémantique ; relire les sources et sorties.")
        state.currentPerformance?.let {
            append("\n\nDernières mesures de l’essai en cours :\n"); append(it.render())
        }
        state.runs.forEach { run ->
            append("\n\nMode demandé : "); append(run.requestedMode)
            append(if (run.reopening) " — session de réouverture, une occurrence" else " — session principale")
            if (varied) { append("\nStratégie de contexte : "); append(run.contextPolicy.label) }
            append("\nAvant : "); append(run.before.render())
            append("\nChargement du modèle : "); append(when {
                run.loadMs > 0 -> "${run.loadMs} ms"
                run.loadPerformance != null -> "0 ms (résolution milliseconde)"
                else -> "aucune mesure positive de chargement réussi reçue ; un chargement a pu être tenté"
            })
            run.loadPerformance?.let { append("\nMesures reçues à l’ouverture :\n"); append(it.render()) }
            append("\nOccurrences terminées : "); append(run.samples.size); append(" / "); append(run.plannedSamples)
            run.samples.forEach { sample ->
                append("\n\nPassage "); append(sample.number)
                if (varied || run.reopening) {
                    sample.cue?.let { cue ->
                        append(" — occurrence "); append(cue.id); append(" ; "); append(cue.startMs)
                        append(" → "); append(cue.endMs); append(" ms ; "); append(cue.kind)
                        append("\nSource synthétique :\n"); append(cue.source)
                    }
                } else append(if (sample.number == 1) " — premier appel" else " — même texte dans la même session")
                append("\nDurée de l’appel complet observée par l’application : "); append(sample.wallMs); append(" ms")
                append("\n"); append(sample.performance?.render() ?: "Mesures natives indisponibles ; aucun moteur réel déduit.")
                if (sample.nativeCalls.size > 1) {
                    append("\nCette occurrence comporte "); append(sample.nativeCalls.size)
                    append(" appels natifs terminés et mesurés ; les lignes précédentes décrivent seulement le dernier.")
                    appendNativeTotals(sample.nativeCalls, sample.wallMs)
                }
                sample.environment?.let { append("\nRelevé après l’occurrence : "); append(it.render()) }
                if (varied) appendPlayback("Lecture au relevé de cette occurrence", sample.playback, sample.finishedAtMs)
                append("\nSortie sur le texte synthétique uniquement :\n"); append(sample.output)
            }
            if (varied) appendSummary(run)
            append("\n\nDurée du mode, chargement et libération inclus : "); append(run.wallMs); append(" ms")
            append("\nAprès : "); append(run.after.render())
            if (varied) {
                appendPlayback("Lecture avant la session", run.playbackBefore, run.startedAtMs)
                appendPlayback("Lecture après la session", run.playbackAfter, run.finishedAtMs)
                val before = run.playbackBefore; val after = run.playbackAfter
                if (fresh(before, run.startedAtMs) && fresh(after, run.finishedAtMs) && before != null && after != null &&
                    before.mediaOpened && after.mediaOpened && before.mediaGeneration == after.mediaGeneration && after.droppedFrames >= before.droppedFrames) {
                    append("\nDelta du compteur de session d’images abandonnées : "); append(after.droppedFrames - before.droppedFrames)
                    append(". Compteur cumulatif du lecteur, sans attribution certaine au média ni au calcul local.")
                } else append("\nDelta du compteur de session d’images abandonnées : non comparable (relevé absent/périmé, changement de média ou compteur réinitialisé).")
            }
            append("\nLibération avant le mode suivant : "); append(if (run.cleanupConfirmed) "confirmée" else "non confirmée ; aucun autre mode lancé")
            run.failure?.let { append("\nRésultat : "); append(it) }
            if (run.failure != null && run.lastPerformance != null) {
                append("\nDernières mesures reçues avant l’arrêt :\n"); append(run.lastPerformance.render())
            }
            if (run.failure == null) append(if (!varied && !run.reopening) "\nRésultat : deux réponses complètes validées."
                else "\nRésultat : ${run.samples.size} réponses terminées, sans classement automatique de fidélité.")
        }
        val mainRuns = state.runs.filterNot { it.reopening }
        if (!contextComparison && mainRuns.size == 2 && mainRuns.all { it.failure == null && it.samples.size == it.plannedSamples }) {
            val same = mainRuns[0].samples.map { it.output } == mainRuns[1].samples.map { it.output }
            append(if (same) "\n\nLes sorties CPU et GPU sont identiques sur ces passages ; cela ne prouve pas leur fidélité linguistique."
                else "\n\nLes sorties CPU et GPU diffèrent : examine les textes avant d’interpréter les durées. Aucun classement de qualité automatique.")
        }
        if (contextComparison && mainRuns.size == LocalGgufContextPolicy.entries.size &&
            mainRuns.all { it.failure == null && it.samples.size == it.plannedSamples }) {
            val same = mainRuns.map { it.samples.map { sample -> sample.output } }.distinct().size == 1
            append(if (same) "\n\nLes trois stratégies ont produit les mêmes textes sur ce corpus ; cela ne prouve pas leur fidélité linguistique."
                else "\n\nLes stratégies produisent des textes différents : comparer chaque source et traduction. Aucun score de fidélité ni choix automatique n’est établi.")
        }
        if (varied) append("\nLes relevés du lecteur sont ponctuels et ne prouvent ni continuité, ni fluidité, ni vocalisation complète. Ils n’actionnent jamais Lire, Pause ou la voix. Aucune identité, URI ou phrase du média ouvert ne figure dans ce rapport.")
        append("\nLa mémoire résidente et son pic concernent tout le processus de calcul, sans comptabilité complète des allocations GPU. Les relevés RAM et thermiques sont ponctuels. Aucun test NPU ni classement automatique de fidélité linguistique.")
    }

    private fun StringBuilder.appendSummary(run: LocalGgufBenchmark.Run) {
        append("\n\nBilan de la session : "); append(run.samples.size); append(" / "); append(run.plannedSamples); append(" occurrences terminées")
        val warmed = run.samples.drop(1).map { it.wallMs }
        val distribution = LocalGgufBenchmarkStatistics.distribution(warmed)
        append("\nLatences d’occurrence après le premier appel, chargement initial exclu par exclusion du premier appel : ")
        if (distribution.count == 0) append("aucune observation") else {
            append("n="); append(distribution.count); append(" ; p50="); append(distribution.p50Ms)
            append(" ms ; p95="); append(distribution.p95Ms); append(" ms ; maximum="); append(distribution.maxMs); append(" ms")
        }
        appendNativeTotals(run.samples.flatMap { it.nativeCalls }, run.samples.sumOf { it.wallMs })
        val environments = listOf(run.before) + run.samples.mapNotNull { it.environment } + run.after
        val available = environments.map { it.availableRamBytes }.filter { it > 0 }
        val thermal = environments.mapNotNull { it.thermalStatus }
        val temperatures = environments.mapNotNull { it.batteryTenthsCelsius }
        if (available.isNotEmpty()) append("\nRAM disponible minimale des relevés : ${available.minOrNull()} octets")
        if (thermal.isNotEmpty()) append("\nÉtat thermique Android maximal des relevés : ${thermal.maxOrNull()} (valeur déclarée Android)")
        if (temperatures.isNotEmpty()) append("\nTempérature de batterie maximale des relevés : " +
            String.format(Locale.FRANCE, "%.1f °C", temperatures.maxOrNull()!! / 10.0) + " ; ne mesure pas la température CPU/GPU")
    }
    private fun StringBuilder.appendNativeTotals(calls: List<LocalTranslationPerformance>, wallMs: Long) {
        append("\nAppels natifs terminés et mesurés des occurrences terminées : "); append(calls.size)
        if (calls.isEmpty()) return
        val promptMs = calls.sumOf { it.promptMs }; val generationMs = calls.sumOf { it.generationMs }
        val tokens = calls.sumOf { it.generatedTokens.toLong() }
        append(" ; entrée cumulée : "); append(promptMs); append(" ms ; génération cumulée : "); append(generationMs); append(" ms")
        append("\nTokens d’entrée calculés cumulés : "); append(calls.sumOf { it.promptEvaluatedTokens.toLong() })
        append(" ; réutilisés : "); append(calls.sumOf { it.cachedPromptTokens.toLong() })
        append(" ; tokens générés cumulés : "); append(tokens)
        append("\nDébit agrégé de génération des appels mesurés des occurrences terminées : "); append(rate(tokens, generationMs))
        append("\nTokens générés mesurés des occurrences terminées / somme de leurs durées complètes : "); append(rate(tokens, wallMs))
        append(". Ce dénominateur inclut le chargement dans le premier appel, sans la libération finale.")
        append("\nLes tentatives natives échouées ne sont pas détaillées dans ces compteurs ; leurs délais restent inclus dans les durées complètes.")
        append(" Les travaux d’une occurrence interrompue sont exclus de ces agrégats et de cette somme de durées ; leur temps reste inclus dans la durée totale du mode.")
    }
    private fun StringBuilder.appendPlayback(label: String, value: LocalGgufBenchmark.PlaybackObservation?, readAtMs: Long) {
        append('\n'); append(label); append(" : ")
        if (value == null) { append("indisponible"); return }
        val age = if (readAtMs >= value.observedAtMs) readAtMs - value.observedAtMs else null
        if (!fresh(value, readAtMs)) {
            append("relevé périmé ou indisponible ; aucune activité de lecture déduite")
            age?.let { append(" ; âge : "); append(it); append(" ms") }; return
        }
        append("âge : "); append(age); append(" ms ; média sélectionné : "); append(yes(value.mediaOpened))
        append(" ; lecture observée : "); append(yes(value.playing)); append(" ; position : "); append(value.positionMs)
        append(" ms ; attente de données : "); append(yes(value.buffering)); append(" ; intention Lire : "); append(yes(value.wantsPlayback))
        append(" ; voix active : "); append(yes(value.speaking)); append(" ; compteur de session d’images abandonnées : "); append(value.droppedFrames)
    }
}
