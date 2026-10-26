package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import fr.soustitresvoix.core.BlackBars
import fr.soustitresvoix.core.NarrationLedger
import fr.soustitresvoix.core.OfflineSpeechCue
import fr.soustitresvoix.core.SpeechTools
import fr.soustitresvoix.core.SubtitleTimeline
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

/** Coordinates durable user choices; no network requests and no implicit playback. */
@UnstableApi
internal class PlaybackFeatures(
    private val context: Context,
    private val session: PlaybackSession,
    private val changed: () -> Unit,
    private val message: (String, Boolean) -> Unit,
    writer: ExecutorService? = null
) {
    private val store = MediaSessionStore(context)
    private val journals = NarrationJournalStore(context)
    private data class PendingWrite(val record: MediaSessionSnapshot, val contextHash: String,
        val journal: String?, val revision: String, val epoch: Long)
    /** Process-local handoff between Activity owners. The one durable writer keeps
     * close/reopen and forget operations ordered without blocking the UI. */
    private class WriteCoordinator(val writer: ExecutorService = Executors.newSingleThreadExecutor()) {
        val lock = Any()
        val pendingWrites = LinkedHashMap<String, PendingWrite>()
        val latestSessionRecords = LinkedHashMap<String, MediaSessionSnapshot>()
        val uriEpochs = HashMap<String, Long>()
        val writtenEpochs = HashMap<String, Long>()
        val writtenJournalEpochs = HashMap<String, Long>()
        val forgottenUris = HashSet<String>()
        val removedRecords = HashSet<String>()
        val pendingJournalRestores = HashMap<String, String>()
        val removedJournalContexts = HashSet<String>()
        val completedJournalRevisions = LinkedHashMap<String, String>()
        // At most four queued contexts plus the one currently being written.
        val uncommittedJournals = LinkedHashMap<String, Pair<String, String>>()
        var writerRunning = false
    }
    private val injectedWriter = writer != null
    private val coordinator = if (writer != null) WriteCoordinator(writer) else synchronized(COORDINATORS) {
        COORDINATORS.getOrPut(context.filesDir.canonicalPath) { WriteCoordinator() }
    }
    private val writer = coordinator.writer
    private val writeLock get() = coordinator.lock
    private val pendingWrites get() = coordinator.pendingWrites
    private val latestSessionRecords get() = coordinator.latestSessionRecords
    private val uriEpochs get() = coordinator.uriEpochs
    private val writtenEpochs get() = coordinator.writtenEpochs
    private val writtenJournalEpochs get() = coordinator.writtenJournalEpochs
    private val forgottenUris get() = coordinator.forgottenUris
    private val removedRecords get() = coordinator.removedRecords
    private val pendingJournalRestores get() = coordinator.pendingJournalRestores
    private val removedJournalContexts get() = coordinator.removedJournalContexts
    private val completedJournalRevisions get() = coordinator.completedJournalRevisions
    private var writerRunning: Boolean
        get() = coordinator.writerRunning
        set(value) { coordinator.writerRunning = value }
    private var closed = false
    private var sourceUri = ""
    private var sourceMetadataFingerprint = ""
    private var pending: MediaSessionSnapshot? = null
    private var restoring = false
    private var journalContext = ""
    private var pendingJournal: String? = null
    private var restoredTranslation = false
    private var restoringTranslationMetadata: String? = null
    private var restoringTranslationUri: String? = null
    private var restoringTranslationTrack: String? = null
    private var translationRestoreSequence = 0L
    private var offlineConfiguration = ""
    private val timelinesByTrack = LinkedHashMap<String, SubtitleTimeline>()
    private var timelineTrack: String? = null
    var timeline = SubtitleTimeline(); private set
    val sessions get() = store.list()

    fun opening(saved: SavedMedia, restore: Boolean): SavedMedia {
        translationRestoreSequence++
        restoringTranslationMetadata = null; restoringTranslationUri = null; restoringTranslationTrack = null
        synchronized(writeLock) {
            if (forgottenUris.remove(saved.uri)) uriEpochs[saved.uri] = nextEpoch(uriEpochs[saved.uri] ?: 0)
        }
        pending = if (restore) sessionRecord(saved.uri) else null
        sourceUri = saved.uri
        sourceMetadataFingerprint = sourceFingerprint(saved.uri)
        restoredTranslation = false; journalContext = ""; pendingJournal = null
        offlineConfiguration = ""
        timelinesByTrack.clear(); timelineTrack = null
        val previousFingerprint = pending?.fingerprint.orEmpty()
        if (previousFingerprint.isNotEmpty() && sourceMetadataFingerprint.isNotEmpty() && previousFingerprint != sourceMetadataFingerprint) {
            pending = null
            timeline = SubtitleTimeline()
            message("Les métadonnées de cette vidéo ont changé. Les anciens choix de pistes, de traduction et de synchronisation ne sont pas restaurés. Vérifie l’édition ; la vidéo s’ouvre en pause au début.", true)
            return saved.copy(positionMs = 0, subtitlePath = null, subtitleMime = null)
        }
        pending?.timelines?.let(timelinesByTrack::putAll)
        restoringTranslationMetadata = pending?.translationIdentity
        restoringTranslationUri = if (restoringTranslationMetadata != null) saved.uri else null
        timeline = pending?.timeline ?: SubtitleTimeline()
        val previous = pending ?: return saved
        restoring = true
        session.prefs.restoreSettings(previous.settings)
        // The order matters: setting an engine clears the old selected voice.
        session.prefs.enginePackage = previous.enginePackage
        session.prefs.voiceName = previous.voiceName
        runCatching { fr.soustitresvoix.core.NarrationMachine.Mode.valueOf(previous.narrationMode) }
            .getOrNull()?.let(session.prefs::setMode)
        restoring = false
        restoreImage(previous.image)
        return saved.copy(positionMs = if (saved.positionMs > 0) saved.positionMs else previous.positionMs,
            subtitlePath = saved.subtitlePath ?: previous.subtitlePath,
            subtitleMime = saved.subtitleMime ?: previous.subtitleMime)
    }

    private fun restoreImage(image: Map<String, Any>) {
        session.imageViewport = image.filterKeys { it in setOf("zoom", "panX", "panY", "frameMode", "protectFrame") }
        fun number(key: String, default: Float) = (image[key] as? Number)?.toFloat()?.takeIf { it.isFinite() } ?: default
        session.visual.adjust(number("contrast", 0f).coerceIn(-.3f, .3f), number("brightness", 0f).coerceIn(-.2f, .2f))
        val margins = BlackBars.Margins(left = number("left", 0f), top = number("top", 0f),
            right = number("right", 0f), bottom = number("bottom", 0f))
        if (margins.safe()) session.visual.crop(margins)
        session.visual.setDetailedScaling(image["detailedScaling"] as? Boolean ?: false)
        if (image["bypass"] == true && !session.visual.bypass) session.visual.toggleComparison()
    }

    fun tracksAvailable(tracks: Tracks) {
        val saved = pending ?: return
        if (tracks.groups.isEmpty()) return
        val builder = session.player.trackSelectionParameters.buildUpon()
        var needsUpdate = false
        var waitingForSelection = false
        var missingText = false
        for ((type, id, language) in listOf(Triple(C.TRACK_TYPE_AUDIO, saved.audioTrackId, saved.audioLanguage),
            Triple(C.TRACK_TYPE_TEXT, saved.textTrackId, saved.textLanguage))) {
            if (id == "__disabled__") {
                if (!session.player.trackSelectionParameters.disabledTrackTypes.contains(type)) {
                    builder.clearOverridesOfType(type).setTrackTypeDisabled(type, true); needsUpdate = true
                }
                if (session.player.currentTracks.groups.any { group -> group.type == type &&
                    (0 until group.length).any { group.isTrackSelected(it) } }) waitingForSelection = true
                continue
            }
            val options = tracks.groups.filter { it.type == type }.flatMap { group ->
                (0 until group.length).filter { group.isTrackSupported(it) }.map { index -> group to index }
            }
            val exact = options.firstOrNull { (group, index) -> group.getTrackFormat(index).id == id }
            val fallback = options.filter { (group, index) -> language != null && group.getTrackFormat(index).language == language }
                .singleOrNull()
            val desired = exact ?: fallback
            if (desired != null) {
                val (group, index) = desired
                val selected = session.player.currentTracks.groups.any { actual ->
                    actual.mediaTrackGroup == group.mediaTrackGroup && index < actual.length && actual.isTrackSelected(index)
                } && !session.player.trackSelectionParameters.disabledTrackTypes.contains(type)
                if (!selected) {
                    builder.clearOverridesOfType(type).setTrackTypeDisabled(type, false)
                        .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, index))
                    needsUpdate = true; waitingForSelection = true
                }
            } else if (type == C.TRACK_TYPE_TEXT && (id != null || language != null)) {
                missingText = true
            }
        }
        if (needsUpdate) {
            val requested = builder.build()
            if (requested != session.player.trackSelectionParameters) session.player.trackSelectionParameters = requested
        }
        // Setting an override does not synchronously update currentTracks.
        // A saved EN translation must never index the old auto-selected FR track.
        if (waitingForSelection) return
        pending = null
        session.speech.applyPreferences()
        updateJournalContext()
        if (!restoredTranslation && restoringTranslationMetadata != null && !missingText && session.selectedTextId() != null) {
            restoredTranslation = true
            val metadata = requireNotNull(restoringTranslationMetadata)
            restoringTranslationTrack = session.selectedTextId()
            val ticket = ++translationRestoreSequence
            val generation = session.generation
            val uri = session.media?.uri
            var finished = false
            fun completeRestoration(success: Boolean) {
                if (finished || translationRestoreSequence != ticket) return
                finished = true
                restoringTranslationMetadata = null; restoringTranslationUri = null; restoringTranslationTrack = null
                if (session.generation != generation || session.media?.uri != uri) return
                session.savePosition()
                if (!success) message("La traduction mémorisée doit être préparée ou réimportée. La séance reste en pause.", false)
                notifySessionRestored()
            }
            if (!session.translation.restoreSelection(metadata, ::completeRestoration)) completeRestoration(false)
            return
        }
        if (missingText && saved.translationIdentity != null)
            message("La piste de sous-titres mémorisée n’est plus disponible. Sa traduction n’a pas été appliquée à une autre piste ; choisis explicitement une piste et sa traduction.", true)
        restoringTranslationMetadata = null; restoringTranslationUri = null; restoringTranslationTrack = null
        session.savePosition()
        notifySessionRestored()
    }

    private fun notifySessionRestored() {
        if (!timeline.identity) session.ensureCompleteIndex { session.rearmCurrentSubtitles() }
        message("Séance restaurée en pause. Utilise Reprendre ou Relire la dernière réplique avant de reprendre.", false)
        val epoch = session.generation
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (session.generation == epoch) session.onSessionRestored?.invoke()
        }
    }

    fun updateJournalContext() {
        val saved = session.media ?: return
        val track = session.selectedTextId() ?: return
        if (timelineTrack != track) {
            timelineTrack?.let { timelinesByTrack[it] = timeline }
            timeline = timelinesByTrack[track] ?: if (timelineTrack == null) timeline else SubtitleTimeline()
            timelineTrack = track
        }
        val identity = SpeechTools.key("journal-v2", saved.uri + "\n" + sourceMetadataFingerprint + "\n" + saved.subtitlePath.orEmpty() + "\n" + track)
        if (identity == journalContext) return
        journalContext = identity
        session.machine.setJournalContext(identity)
        val hash = session.machine.journalSnapshot().contextHash
        pendingJournal = synchronized(writeLock) { pendingJournalRestores.remove(hash) ?: coordinator.uncommittedJournals[hash]?.first }
            ?: if (synchronized(writeLock) { hash in removedJournalContexts }) null else journals.read(hash)
        pendingJournal?.let { session.machine.restoreJournalMetadata(it) }
        pendingJournal = null
    }

    fun register(items: List<CueAheadIndex.Item>) {
        updateJournalContext()
        session.machine.registerOccurrences(items.map { NarrationLedger.Occurrence(it.identity, it.startUs, it.endUs) })
        val prepared = items.filter { item ->
            val text = session.translation.textFor(item)?.let { SpeechTools.filter(it, session.prefs.filters) } ?: return@filter false
            val segments = SpeechTools.segments(text, session.speech.language)
            segments.isNotEmpty() && segments.all { session.speech.measuredDurationMs(it) != null }
        }
        session.machine.markPrepared(prepared.map { it.identity })
    }

    fun save(saved: SavedMedia) {
        if (restoring) return
        val epoch = synchronized(writeLock) {
            if (closed || saved.uri in forgottenUris || writer.isShutdown) return
            uriEpochs[saved.uri] ?: 0
        }
        updateJournalContext()
        fun track(type: Int) = session.player.currentTracks.groups.firstNotNullOfOrNull { group ->
            if (group.type != type) null else (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { group.getTrackFormat(it) }
        }
        val audio = track(C.TRACK_TYPE_AUDIO); val text = track(C.TRACK_TYPE_TEXT)
        val awaitingSelection = pending?.takeIf { it.uri == saved.uri }
        val snapshot = session.machine.journalSnapshot()
        fun occurrence(entry: NarrationLedger.Entry?) = entry?.let {
            val start = it.startUs?.div(1000)?.coerceAtLeast(0) ?: return@let null
            val end = it.endUs?.div(1000)?.coerceAtLeast(start + 1) ?: return@let null
            OccurrenceResume(it.identityHash, start, end)
        }
        val image = session.visual
        val record = MediaSessionSnapshot(saved.uri, saved.title, saved.positionMs,
            fingerprint = if (sourceUri == saved.uri) sourceMetadataFingerprint else sourceFingerprint(saved.uri),
            audioTrackId = if (awaitingSelection != null) awaitingSelection.audioTrackId else
                if (session.player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO)) "__disabled__" else audio?.id,
            textTrackId = if (awaitingSelection != null) awaitingSelection.textTrackId else
                if (session.player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) "__disabled__" else text?.id,
            audioLanguage = if (awaitingSelection != null) awaitingSelection.audioLanguage else audio?.language,
            textLanguage = if (awaitingSelection != null) awaitingSelection.textLanguage else text?.language,
            subtitlePath = saved.subtitlePath, subtitleMime = saved.subtitleMime,
            translationIdentity = session.translation.selectionMetadata()
                ?: restoringTranslationMetadata.takeIf { restoringTranslationUri == saved.uri &&
                    (awaitingSelection != null || restoringTranslationTrack == session.selectedTextId()) },
            translationReady = session.translation.complete,
            enginePackage = session.prefs.enginePackage, voiceName = session.prefs.voiceName,
            narrationMode = session.prefs.mode.name, settings = session.prefs.settingsSnapshot(),
            image = mapOf("contrast" to image.contrast, "brightness" to image.brightness,
                "left" to image.margins.left, "right" to image.margins.right, "top" to image.margins.top,
                "bottom" to image.margins.bottom, "bypass" to image.bypass, "detailedScaling" to image.detailedScaling) + session.imageViewport,
            timeline = timeline, lastCompleted = occurrence(snapshot.entries.lastOrNull { it.completions > 0 }),
            lastInterrupted = occurrence(snapshot.lastInterrupted),
            timelines = timelinesByTrack.toMutableMap().apply { session.selectedTextId()?.let { put(it, timeline) } })
        val revision = snapshot.contextHash + ":" + snapshot.generation + ":" + snapshot.revision
        val needsJournal = synchronized(writeLock) { completedJournalRevisions[snapshot.contextHash] != revision }
        val journal = if (needsJournal) session.machine.exportJournalMetadata().takeIf { it.isNotEmpty() } else null
        val write = PendingWrite(record, snapshot.contextHash, journal, revision, epoch)
        synchronized(writeLock) {
            if (writer.isShutdown || saved.uri in forgottenUris || (uriEpochs[saved.uri] ?: 0) != epoch) return
            latestSessionRecords.remove(saved.uri)
            latestSessionRecords[saved.uri] = record
            if (latestSessionRecords.size > 32) latestSessionRecords.remove(latestSessionRecords.keys.first())
            val key = saved.uri + "\n" + snapshot.contextHash
            if (key !in pendingWrites && pendingWrites.size >= 4) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    message("Le stockage des séances est momentanément trop lent. Attends quelques instants avant de changer de vidéo ; la dernière position courante sera réessayée.", true)
                }
                return
            }
            pendingWrites.remove(key)
            pendingWrites[key] = write
            journal?.let {
                coordinator.uncommittedJournals.remove(snapshot.contextHash)
                coordinator.uncommittedJournals[snapshot.contextHash] = it to revision
                if (coordinator.uncommittedJournals.size > 5)
                    coordinator.uncommittedJournals.remove(coordinator.uncommittedJournals.keys.first())
            }
            if (!writerRunning) {
                writerRunning = true
                writer.execute { drainWrites() }
            }
        }
    }

    /** A slow filesystem retains only the newest snapshot for a media/track,
     * while a switch to another video cannot discard the previous video's save. */
    private fun drainWrites() {
        while (true) {
            val task = synchronized(writeLock) {
                val first = pendingWrites.entries.firstOrNull()
                if (first == null) { writerRunning = false; return }
                pendingWrites.remove(first.key)
                first.value
            }
            try {
                if (synchronized(writeLock) { (uriEpochs[task.record.uri] ?: 0) != task.epoch || task.record.uri in forgottenUris }) continue
                store.save(task.record)
                synchronized(writeLock) {
                    writtenEpochs[task.record.uri] = task.epoch
                    if ((uriEpochs[task.record.uri] ?: 0) == task.epoch && task.record.uri !in forgottenUris)
                        removedRecords.remove(task.record.uri)
                }
                if (task.journal != null) {
                    journals.save(task.contextHash, task.journal)
                    synchronized(writeLock) {
                        completedJournalRevisions[task.contextHash] = task.revision
                        if (coordinator.uncommittedJournals[task.contextHash]?.second == task.revision)
                            coordinator.uncommittedJournals.remove(task.contextHash)
                        writtenJournalEpochs[task.contextHash] = task.epoch
                        if ((uriEpochs[task.record.uri] ?: 0) == task.epoch && task.record.uri !in forgottenUris)
                            removedJournalContexts.remove(task.contextHash)
                        if (completedJournalRevisions.size > 256) completedJournalRevisions.remove(completedJournalRevisions.keys.first())
                    }
                }
            } catch (_: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    message("La séance n’a pas pu être conservée. Vérifie l’espace disponible dans Stockage.", true)
                }
            }
        }
    }

    /** Partial metadata identity only, deliberately independent of the URI.
     * Equality does not prove byte-identical media. A repaired URI still needs
     * explicit user confirmation. No video bytes are read or hashed here. */
    fun sourceFingerprint(uri: String): String {
        return try {
            val parsed = Uri.parse(uri)
            var size: Long? = null
            var modified: Long? = null
            val columns = arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            try {
                context.contentResolver.query(parsed, columns, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex).takeIf { it >= 0 }
                        if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) modified = cursor.getLong(modifiedIndex).takeIf { it > 0 }
                    }
                }
            } catch (_: Exception) {
                // Some SAF providers reject last_modified rather than omitting it.
                context.contentResolver.query(parsed, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) size = cursor.getLong(0).takeIf { it >= 0 }
                }
            }
            if (size == null) context.contentResolver.openAssetFileDescriptor(parsed, "r")?.use { descriptor ->
                size = descriptor.length.takeIf { it >= 0 }
            }
            if (size == null && modified == null) "" else SpeechTools.key("metadata-v1", listOf(
                size?.toString() ?: "unknown-size", modified?.toString() ?: "unknown-modified",
                runCatching { context.contentResolver.getType(parsed) }.getOrNull().orEmpty()).joinToString("\n"))
                .let { "metadata-v1:$it" }
        } catch (_: Exception) { "" }
    }

    fun applyTimeline(value: SubtitleTimeline) {
        session.pause(); timeline = value
        (timelineTrack ?: session.selectedTextId())?.let { timelinesByTrack[it] = value; timelineTrack = it }
        session.invalidateTimedNarration()
        session.savePosition(); changed()
    }

    fun showSynchronization(activity: Activity) {
        if (session.media == null) { message("Ouvre d’abord une vidéo.", false); return }
        session.pause()
        val epoch = session.generation
        val track = session.selectedTextId()
        session.ensureCompleteIndex {
            if (session.generation != epoch || session.selectedTextId() != track || activity.isDestroyed || activity.isFinishing) return@ensureCompleteIndex
            SyncControls(activity, { timeline }, ::applyTimeline,
                { timeline.sourceTimeMs(session.player.currentPosition) },
                { session.previewSynchronizedCaption() },
                { session.generation == epoch && session.selectedTextId() == track }).show()
        }
    }

    fun report(): String {
        val result = session.machine.journalSnapshot()
        return "${result.completedCount} occurrences terminées sur ${result.detectedCount} détectées.\n" +
            "${result.interruptedCount} interrompues ; ${result.errorCount} en erreur ; ${result.pendingCount} en attente ; ${result.filteredCount} exclues explicitement.\n" +
            "Le suivi porte sur les occurrences extraites de la piste choisie. Commencée signifie commande vocale émise, sans mesure acoustique. Les identifiants sont hachés ; le journal ne conserve pas le texte.\n" +
            if (result.capacityExceeded) "La limite du journal est atteinte : le bilan est incomplet." else "Une répétition manuelle ne gonfle pas le nombre d’occurrences distinctes terminées."
    }
    fun showNarrationReport(activity: Activity) {
        AlertDialog.Builder(activity).setTitle("Bilan de vocalisation")
            .setMessage(report()).setPositiveButton("Fermer", null)
            .setNeutralButton("Relire l’interruption") { _, _ -> replayInterrupted() }.show()
    }
    private fun replayInterrupted(fallbackLast: Boolean = false) {
        session.pause()
        val snapshot = session.machine.journalSnapshot()
        val target = snapshot.lastInterrupted ?: if (fallbackLast) snapshot.entries.lastOrNull { it.completions > 0 } else null
        if (target == null) { message("Aucune réplique interrompue disponible pour cette piste.", false); return }
        session.ensureCompleteIndex {
            val occurrence = session.ahead.selected(session.selectedTextId()).firstOrNull {
                NarrationLedger.hashIdentity(it.identity) == target.identityHash
            }
            val text = occurrence?.let { session.translation.textFor(it) }
            if (occurrence == null || text == null) { message("La réplique mémorisée n’est pas disponible dans cette source. Vérifie la piste et sa traduction.", true); return@ensureCompleteIndex }
            register(listOf(occurrence))
            session.applyNarration(session.machine.replayOccurrence(occurrence.identity, SpeechTools.filter(text, session.prefs.filters)))
            message("Réplique relue. La vidéo reste en pause ; appuie ensuite sur Reprendre.", false)
        }
    }
    fun showResumeSession(activity: Activity) {
        session.pause()
        AlertDialog.Builder(activity).setTitle("Reprendre la séance")
            .setMessage("La vidéo est en pause à la position mémorisée.\n\n${report()}")
            .setPositiveButton("Reprendre") { _, _ -> session.play() }
            .setNeutralButton("Relire la dernière réplique avant de reprendre") { _, _ -> replayInterrupted(true) }
            .setNegativeButton("Rester en pause", null).show()
    }

    private fun offlineMediaKey(): String? = session.media?.let { saved ->
        SpeechTools.key("offline-media-v1", saved.uri + "\n" + session.selectedTextId() + "\n" + session.translation.fingerprint)
    }
    fun configureOffline(): Boolean {
        val saved = session.media ?: return false
        val items = session.ahead.selected(session.selectedTextId())
        if (items.isEmpty()) return false
        val cues = items.mapNotNull { item -> session.translation.textFor(item)?.let {
            val interval = timeline.mapInterval(item.startUs / 1000,
                (item.endUs / 1000).coerceAtLeast(item.startUs / 1000 + 1))
            OfflineSpeechCue(item.identity, interval.startMs, interval.endMs,
                SpeechTools.filter(it, session.prefs.filters))
        } }
        val complete = session.hasCompleteSubtitleIndex && !session.ahead.truncated && cues.size == items.size &&
            (!session.translation.required || session.translation.complete)
        val duration = maxOf(session.player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0,
            cues.maxOfOrNull { it.endMs } ?: 1)
        val key = offlineMediaKey() ?: return false
        val revision = "$key:${session.speech.cacheConfigurationFingerprint}:${items.size}:${cues.hashCode()}:$complete:$timeline"
        if (revision != offlineConfiguration) {
            offlineConfiguration = revision
            session.speech.configureOfflineWork(key, cues, duration, complete, saved.title)
        } else session.speech.refreshOfflineStatus()
        return true
    }
    /** Opens and closes only a read-only descriptor on the I/O worker; never reads video bytes. */
    internal fun checkOfflineMediaAccess(uri: String, checked: (Boolean) -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        try {
            writer.execute {
                val accessible = runCatching {
                    context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
                }.getOrDefault(false)
                main.post { checked(accessible) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { main.post { checked(false) } }
    }
    fun showOfflineStorage(activity: Activity) {
        val savedMedia = session.media ?: run { showWorks(activity); return }
        session.pause()
        val epoch = session.generation
        val track = session.selectedTextId()
        session.ensureCompleteIndex {
            if (activity.isDestroyed || activity.isFinishing || epoch != session.generation || track != session.selectedTextId()) return@ensureCompleteIndex
            if (!configureOffline()) {
                session.speech.clearOfflineSelection()
                message("Cette piste ne fournit aucune occurrence textuelle à conserver. Choisis une piste de sous-titres textuelle.", true)
                return@ensureCompleteIndex
            }
            session.speech.estimateOffline()
            val expectedWorkKey = offlineMediaKey()?.let { fr.soustitresvoix.core.OfflineSpeechLibrary.workKey(it) } ?: return@ensureCompleteIndex
            fun valid() = !activity.isDestroyed && !activity.isFinishing && epoch == session.generation && track == session.selectedTextId() &&
                offlineMediaKey()?.let { fr.soustitresvoix.core.OfflineSpeechLibrary.workKey(it) } == expectedWorkKey
            var mediaAccessible: Boolean? = null
            var accessPending = false
            var lastSpeechReady = session.speech.offlineStatus.let { it.mediaKey == expectedWorkKey && it.ready }
            fun verifyAccess() {
                accessPending = true; mediaAccessible = null
                checkOfflineMediaAccess(savedMedia.uri) { accessible ->
                    if (valid()) { mediaAccessible = accessible; accessPending = false }
                }
            }
            val dialog = AlertDialog.Builder(activity).setTitle("Stockage de cette œuvre")
                .setMessage("Estimation des ressources et vérification des fichiers en cours…")
                .setPositiveButton("Conserver pour une écoute hors ligne") { _, _ ->
                    if (valid()) {
                        fun prepare() {
                            if (valid()) {
                                if (!configureOffline()) {
                                    session.speech.clearOfflineSelection()
                                    message("La piste sélectionnée ne peut pas être conservée.", true); return
                                }
                                session.speech.prepareOffline()
                                message("Préparation durable demandée. Consulte son état dans Stockage ; elle reste reprenable et annulable.", false)
                            }
                        }
                        if (session.translation.required) {
                            val workKey = offlineMediaKey()?.let { fr.soustitresvoix.core.OfflineSpeechLibrary.workKey(it) }
                            if (workKey == null) message("L’œuvre sélectionnée n’est plus disponible.", true)
                            else session.translation.retainCurrentForOffline(workKey) { saved ->
                                if (saved) prepare() else message("La traduction complète doit être conservée avant de préparer une écoute intégrale hors ligne.", true)
                            }
                        } else prepare()
                    }
                }
                .setNeutralButton("Gérer les œuvres conservées") { _, _ -> showWorks(activity) }
                .setNegativeButton("Fermer", null).create()
            val refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
            val refresh = object : Runnable {
                override fun run() {
                    if (!dialog.isShowing || !valid()) return
                    val state = session.speech.offlineStatus.takeIf { it.mediaKey == expectedWorkKey }
                        ?: fr.soustitresvoix.core.OfflineSpeechStatus(message = "Estimation des ressources de cette œuvre en cours…")
                    val speechReady = state.ready
                    // Recheck once when final audio verification completes, not on every UI tick.
                    if (speechReady && !lastSpeechReady && !accessPending) verifyAccess()
                    lastSpeechReady = speechReady
                    val ready = speechReady && mediaAccessible == true && !accessPending
                    val accessMessage = when (mediaAccessible) {
                        true -> "Accès à la vidéo vérifié sans lire son contenu."
                        false -> "Accès à la vidéo indisponible : utilise Réparer l’accès à la vidéo pour la sélectionner de nouveau. L’œuvre n’est pas prête hors ligne."
                        null -> "Vérification de l’autorisation d’accès à la vidéo en cours ; disponibilité hors ligne non confirmée."
                    }
                    dialog.setTitle(if (ready) "Prêt hors ligne" else "Stockage de cette œuvre")
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text = if (mediaAccessible == false) "Réparer l’accès à la vidéo" else "Fermer"
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = mediaAccessible == true && !accessPending
                    val resources = if (state.ready && !ready) "Toutes les voix préparées sont vérifiées." else state.message
                    dialog.setMessage(resources + "\n\n" + accessMessage + "\n\n${state.readySegments} segments prêts sur ${state.expectedSegments}. " +
                        "${state.readyThroughMs / 60_000} minutes vérifiées sur ${state.totalDurationMs / 60_000}.\n" +
                        "Estimation totale : ${state.estimatedBytes / 1_048_576} Mio ; place supplémentaire : ${state.additionalBytes / 1_048_576} Mio ; disponibles : ${state.availableBytes / 1_048_576} Mio.\n" +
                        "La vidéo doit rester accessible. Une traduction complète et tous les segments vocaux vérifiés sont nécessaires pour déclarer l’œuvre prête hors ligne.")
                    refreshHandler.postDelayed(this, 500)
                }
            }
            dialog.setOnDismissListener { refreshHandler.removeCallbacksAndMessages(null) }
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                dialog.dismiss()
                if (mediaAccessible == false && valid()) session.onMediaAccessFailure?.invoke(savedMedia)
            }
            verifyAccess(); refreshHandler.post(refresh)
        }
    }
    private fun showWorks(activity: Activity) {
        val works = session.speech.offlineWorks()
        if (works.isEmpty()) { message("Aucune œuvre conservée, ou lecture du catalogue en cours. Rouvre Stockage après quelques instants.", false); return }
        AlertDialog.Builder(activity).setTitle("Œuvres conservées")
            .setItems(works.map { "${it.title} — ${it.bytes / 1_048_576} Mio" }.toTypedArray()) { _, index ->
                val work = works[index]
                AlertDialog.Builder(activity).setTitle(work.title)
                    .setItems(arrayOf("Supprimer les voix préparées", "Supprimer la traduction conservée", "Supprimer les textes narrés", "Supprimer l’index conservé")) { _, choice ->
                        when (choice) {
                            0 -> session.speech.removeOfflineVoices(work.mediaKey)
                            1 -> session.translation.forgetWorkSavedTranslation(work.mediaKey) { removed ->
                                // The prepared text file also contains a copy of the translated cues.
                                // Removing it invalidates readiness while preserving voices and the index.
                                if (removed) session.speech.removeOfflineTexts(work.mediaKey)
                            }
                            2 -> session.speech.removeOfflineTexts(work.mediaKey)
                            else -> session.speech.removeOfflineIndex(work.mediaKey)
                        }
                        message("Suppression demandée pour les données choisies. La vidéo et les sous-titres d’origine sont conservés.", false)
                    }.setNegativeButton("Annuler", null).show()
            }.setNegativeButton("Fermer", null).show()
    }
    fun close() {
        closed = true
        // The process writer must finish queued snapshots and accept a new
        // Activity's work. Tests may inject an independently owned executor.
        if (injectedWriter) writer.shutdown()
    }
    fun sessionRecord(uri: String): MediaSessionSnapshot? {
        val memory = synchronized(writeLock) {
            if (uri in forgottenUris) return null
            latestSessionRecords[uri]
        }
        if (memory != null) return memory
        if (synchronized(writeLock) { uri in removedRecords }) return null
        return store.get(uri)
    }
    fun forget(uri: String) {
        val record = sessionRecord(uri)
        val tracks = record?.timelines?.keys.orEmpty() + listOfNotNull(record?.textTrackId)
        val hashes = if (record == null) emptySet() else tracks.map {
            journalHash(uri, record.fingerprint, record.subtitlePath, it)
        }.toSet()
        val epoch = synchronized(writeLock) {
            val next = nextEpoch(uriEpochs[uri] ?: 0)
            uriEpochs[uri] = next; forgottenUris += uri; removedRecords += uri
            latestSessionRecords.remove(uri)
            pendingWrites.entries.removeAll { it.value.record.uri == uri }
            hashes.forEach { pendingJournalRestores.remove(it); coordinator.uncommittedJournals.remove(it); completedJournalRevisions.remove(it); removedJournalContexts += it }
            next
        }
        if (!writer.isShutdown) writer.execute {
            try {
                if (synchronized(writeLock) { (writtenEpochs[uri] ?: -1) <= epoch }) store.remove(uri)
                hashes.forEach { if (synchronized(writeLock) { (writtenJournalEpochs[it] ?: -1) <= epoch }) journals.remove(it) }
            } catch (_: Exception) { postStorageProblem() }
        }
    }
    fun relocate(uri: String, title: String) {
        val previous = session.media ?: return
        session.pause()
        val record = sessionRecord(previous.uri)
        if (record == null) { session.open(SavedMedia(uri, title)); return }
        val fingerprint = sourceFingerprint(uri)
        if (record.fingerprint.isNotEmpty() && fingerprint.isNotEmpty() && record.fingerprint != fingerprint) {
            message("Les métadonnées du fichier sélectionné diffèrent de la séance. Les anciens minutages, pistes et traduction ne sont pas transférés ; ouverture en pause au début.", true)
            session.open(SavedMedia(uri, title), restoreSession = false)
            return
        }
        val moved = record.copy(uri = uri, title = title, fingerprint = fingerprint, updatedAtMs = System.currentTimeMillis())
        val tracks = (record.timelines.keys + listOfNotNull(record.textTrackId, session.selectedTextId())).filter { it != "__disabled__" }.toSet()
        val currentTrack = session.selectedTextId()
        val liveMetadata = currentTrack?.let { session.machine.exportJournalMetadata() }
        val migrations = tracks.map { track ->
            val oldHash = journalHash(previous.uri, record.fingerprint, record.subtitlePath, track)
            val newHash = journalHash(uri, fingerprint, record.subtitlePath, track)
            Triple(oldHash, newHash, if (track == currentTrack) liveMetadata?.let { rebaseJournal(it, newHash) } else null)
        }
        val epoch = synchronized(writeLock) {
            if (uri != previous.uri) {
                uriEpochs[previous.uri] = nextEpoch(uriEpochs[previous.uri] ?: 0)
                forgottenUris += previous.uri; removedRecords += previous.uri
                latestSessionRecords.remove(previous.uri)
                pendingWrites.entries.removeAll { it.value.record.uri == previous.uri }
            }
            val next = nextEpoch(uriEpochs[uri] ?: 0)
            uriEpochs[uri] = next; forgottenUris.remove(uri)
            latestSessionRecords[uri] = moved
            migrations.forEach { (_, hash, encoded) -> if (encoded != null) pendingJournalRestores[hash] = encoded }
            next
        }
        if (!writer.isShutdown) writer.execute {
            try {
                if (synchronized(writeLock) { (uriEpochs[uri] ?: 0) == epoch && uri !in forgottenUris }) {
                    val newest = synchronized(writeLock) { latestSessionRecords[uri] } ?: moved
                    store.save(newest)
                    synchronized(writeLock) {
                        writtenEpochs[uri] = epoch
                        if ((uriEpochs[uri] ?: 0) == epoch && uri !in forgottenUris) removedRecords.remove(uri)
                    }
                    migrations.forEach { (oldHash, newHash, cached) ->
                        if (synchronized(writeLock) { newHash !in completedJournalRevisions }) {
                            val encoded = cached ?: journals.read(oldHash)?.let { rebaseJournal(it, newHash) }
                            if (encoded != null) {
                                journals.save(newHash, encoded)
                                synchronized(writeLock) { writtenJournalEpochs[newHash] = epoch }
                            }
                        }
                    }
                    if (uri != previous.uri) store.remove(previous.uri)
                }
            } catch (_: Exception) { postStorageProblem() }
        }
        session.open(SavedMedia(uri, title, moved.positionMs, moved.subtitlePath, moved.subtitleMime), restoreSession = true)
    }
    private fun postStorageProblem() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            message("La réparation ou la suppression de la séance n’a pas pu être enregistrée. Vérifie l’espace disponible dans Stockage.", true)
        }
    }
    private fun journalHash(uri: String, fingerprint: String, subtitle: String?, track: String) =
        NarrationLedger.hashIdentity(SpeechTools.key("journal-v2", uri + "\n" + fingerprint + "\n" + subtitle.orEmpty() + "\n" + track))
    private fun rebaseJournal(encoded: String, newHash: String): String {
        val first = encoded.indexOf('\n')
        val second = encoded.indexOf('\n', first + 1)
        require(first >= 0 && second > first)
        return encoded.substring(0, first + 1) + newHash + encoded.substring(second)
    }
    private fun nextEpoch(value: Long) = if (value == Long.MAX_VALUE) 0 else value + 1
    companion object {
        private val COORDINATORS = HashMap<String, WriteCoordinator>()
    }
}
