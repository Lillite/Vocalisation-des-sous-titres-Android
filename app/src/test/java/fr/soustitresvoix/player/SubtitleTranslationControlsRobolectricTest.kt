package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

/** Framework form checks. No account, translation endpoint or private media is used. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class SubtitleTranslationControlsRobolectricTest {
    @Before fun clearPreferences() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        for (name in listOf("player", "translation-choices-v1", TranslationCredentials.PREFERENCES))
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }
    private class Fixture : AutoCloseable {
        private val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val messages = mutableListOf<String>()
        val session = PlaybackSession(activity, PlayerPreferences(activity), { }, { message, _ -> messages += message }, { })
        val controls = SubtitleTranslationControls(activity, session)
        fun open(): AlertDialog {
            controls.show()
            shadowOf(Looper.getMainLooper()).idle()
            return requireNotNull(ShadowAlertDialog.getLatestAlertDialog()).also { assertTrue(it.isShowing) }
        }
        override fun close() {
            controls.close(); session.close()
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(descendants(view.getChildAt(i)))
    }
    private fun views(dialog: AlertDialog) = descendants(dialog.window!!.decorView)
    private fun text(dialog: AlertDialog) = views(dialog).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
    private fun field(dialog: AlertDialog, labelPrefix: String): EditText {
        val label = views(dialog).filterIsInstance<TextView>().single { it.text.toString().startsWith(labelPrefix) && it.labelFor != View.NO_ID }
        return requireNotNull(dialog.findViewById<EditText>(label.labelFor))
    }
    private fun button(dialog: AlertDialog, label: String) =
        views(dialog).filterIsInstance<Button>().single { it.text.toString() == label }

    @Test fun everyProviderHasSpecificSetupCostsPrivacyAndCapabilityDescriptions() {
        val descriptions = TranslationProvider.entries.associateWith { SubtitleTranslationControls.description(it) }
        assertEquals(7, descriptions.size)
        assertEquals(7, descriptions.values.distinct().size)
        val azure = descriptions.getValue(TranslationProvider.AZURE)
        assertTrue(azure.contains("Clés et point de terminaison"))
        assertTrue(azure.contains("2 millions de caractères/mois"))
        assertTrue(azure.contains("serbe latin et cyrillique"))
        assertTrue(azure.contains("vérifie les conditions actuelles"))
        val free = descriptions.getValue(TranslationProvider.DEEPL_FREE)
        assertTrue(free.contains("api-free.deepl.com"))
        assertTrue(free.contains("n’est plus ouvert"))
        assertTrue(free.contains("1 million de caractères au total, non renouvelable"))
        assertTrue(free.contains("quel endpoint utiliser"))
        val paid = descriptions.getValue(TranslationProvider.DEEPL_PRO)
        assertTrue(paid.contains("api.deepl.com"))
        assertTrue(paid.contains("limite de dépense"))
        assertTrue(paid.contains("contexte de scène partagé"))
        val libre = descriptions.getValue(TranslationProvider.LIBRE)
        assertTrue(libre.contains("langues installées"))
        assertTrue(libre.contains("politique de conservation"))
        assertTrue(libre.contains("refuse les redirections"))
        assertTrue(libre.contains("ne s’exécute pas sur le téléphone"))
    }

    @Test fun actualFormLabelsEveryFieldAndProtectsTheKeyFromDisplayAndAutofill() {
        Fixture().use { fixture ->
            val panel = fixture.open()
            val fields = views(panel).filterIsInstance<EditText>()
            assertEquals(14, fields.size)
            for (input in fields) {
                val labels = views(panel).filterIsInstance<TextView>().filter { it.labelFor == input.id }
                assertEquals("Each editor needs its own accessible label", 1, labels.size)
                assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, input.importantForAutofill)
                assertTrue(input.textSize + .1f >= 18f * fixture.activity.resources.displayMetrics.scaledDensity)
            }
            val key = field(panel, "Clé API personnelle")
            assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                key.inputType and InputType.TYPE_MASK_VARIATION)
            assertTrue(key.transformationMethod is PasswordTransformationMethod)
            val synthetic = "synthetic-private-value-not-real"
            key.setText(synthetic)
            // It is held in the password field for the explicit request, never copied into help/status.
            assertFalse(views(panel).filterIsInstance<TextView>().filter { it !is EditText }
                .any { it.text.toString().contains(synthetic) })
            val plainPreferences = fixture.activity.getSharedPreferences("translation-choices-v1", Context.MODE_PRIVATE).all
            assertFalse(plainPreferences.values.any { it.toString().contains(synthetic) })
            assertFalse(views(panel).filterIsInstance<CheckBox>().single { it.text.toString().startsWith("Mémoriser cette clé") }.isChecked)
            assertFalse(fixture.session.translation.busy)
            assertFalse(fixture.session.translation.required)
            assertFalse(fixture.session.player.playWhenReady)
        }
    }

    @Test fun providerSelectionShowsOnlyApplicableEndpointAndRegionWithMatchingHelp() {
        Fixture().use { fixture ->
            val panel = fixture.open()
            val spinner = views(panel).filterIsInstance<Spinner>().single { it.contentDescription == "Moteur de traduction" }
            assertEquals("Moteur de traduction", spinner.contentDescription.toString())
            assertEquals(7, spinner.count)
            val region = field(panel, "Région Azure")
            val endpoint = field(panel, "Adresse de base du serveur LibreTranslate")
            for ((index, provider) in TranslationProvider.entries.withIndex()) {
                spinner.setSelection(index)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(if (provider == TranslationProvider.AZURE) View.VISIBLE else View.GONE, region.visibility)
                assertEquals(if (provider == TranslationProvider.LIBRE) View.VISIBLE else View.GONE, endpoint.visibility)
                assertTrue(text(panel).contains(SubtitleTranslationControls.description(provider)))
                assertFalse(fixture.session.translation.busy)
                assertFalse(fixture.session.translation.required)
            }
        }
    }

    @Test fun providerCacheIdentitySeparatesServersButKeepsEquivalentEndpointsAndKeyChanges() {
        fun config(endpoint: String, key: String) = TranslationProviderConfig(TranslationProvider.LIBRE, key, endpoint = endpoint)
        val one = SubtitleTranslationControls.providerIdentity(config("https://one.example.test/prefix/", "key-one"))
        val equivalent = SubtitleTranslationControls.providerIdentity(config("https://one.example.test/prefix/translate", "key-two"))
        val other = SubtitleTranslationControls.providerIdentity(config("https://two.example.test/prefix", "key-one"))
        assertEquals(one, equivalent)
        assertNotEquals(one, other)
        assertFalse(one.contains("example.test"))
        assertFalse(one.contains("key-one"))
        assertNotEquals(SubtitleTranslationControls.providerIdentity(TranslationProviderConfig(TranslationProvider.DEEPL_FREE)),
            SubtitleTranslationControls.providerIdentity(TranslationProviderConfig(TranslationProvider.DEEPL_PRO)))
    }

    @Test fun googleIntervalSurvivesOtherProviderPreparationAndInvalidEditsNeverReplaceIt() {
        Fixture().use { fixture ->
            val storage = fixture.activity.getSharedPreferences("translation-choices-v1", Context.MODE_PRIVATE)
            storage.edit().putLong("google-interval-ms", 2_400L).commit()
            val panel = fixture.open()
            val spinner = views(panel).filterIsInstance<Spinner>().single { it.contentDescription == "Moteur de traduction" }
            val interval = field(panel, "Google en ligne : délai minimal")
            assertEquals("2400", interval.text.toString())
            button(panel, "Indexer la piste et estimer la traduction").performClick()
            assertEquals(2_400L, storage.getLong("google-interval-ms", 0L))

            spinner.setSelection(TranslationProvider.GOOGLE_WEB.ordinal)
            shadowOf(Looper.getMainLooper()).idle()
            interval.setText("3000")
            button(panel, "Indexer la piste et estimer la traduction").performClick()
            assertEquals(3_000L, storage.getLong("google-interval-ms", 0L))
            spinner.setSelection(TranslationProvider.GOOGLE_LOCAL.ordinal)
            shadowOf(Looper.getMainLooper()).idle()
            button(panel, "Indexer la piste et estimer la traduction").performClick()
            assertEquals(3_000L, storage.getLong("google-interval-ms", 0L))

            spinner.setSelection(TranslationProvider.GOOGLE_WEB.ordinal)
            shadowOf(Looper.getMainLooper()).idle()
            interval.setText("499")
            button(panel, "Indexer la piste et estimer la traduction").performClick()
            assertTrue(text(panel).contains("Options invalides"))
            assertEquals(3_000L, storage.getLong("google-interval-ms", 0L))
            assertEquals("3000", field(fixture.open(), "Google en ligne : délai minimal").text.toString())
            assertFalse(fixture.session.translation.busy)
            assertFalse(fixture.session.translation.required)
        }
    }

    @Test fun openingRefreshAndMissingMediaCannotStartTranslationOrPersistASecret() {
        Fixture().use { fixture ->
            val panel = fixture.open()
            val rendered = text(panel)
            assertTrue(rendered.contains("aucun envoi avant confirmation"))
            assertTrue(rendered.contains("Google ML Kit traduit sur ce téléphone"))
            assertTrue(rendered.contains("Une traduction conservée reste lisible hors connexion"))
            assertTrue(rendered.contains("Le choix d’affichage est indépendant de la voix"))
            field(panel, "Clé API personnelle").setText("synthetic-unsaved-key")
            button(panel, "Indexer la piste et estimer la traduction").performClick()
            fixture.controls.refresh()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(fixture.session.translation.busy)
            assertFalse(fixture.session.translation.required)
            assertTrue(fixture.messages.contains("Ouvre d’abord une vidéo."))
            assertFalse(fixture.session.player.playWhenReady)
            assertTrue(fixture.activity.getSharedPreferences(TranslationCredentials.PREFERENCES, Context.MODE_PRIVATE).all.isEmpty())
            assertFalse(fixture.activity.getSharedPreferences("translation-choices-v1", Context.MODE_PRIVATE).all.values
                .any { it.toString().contains("synthetic-unsaved-key") })
            assertEquals(panel, ShadowAlertDialog.getLatestAlertDialog())
        }
    }

    @Test fun changingLibreServerClearsThePreviousKeyAndLegacyUnscopedKeyIsNeverLoaded() {
        Fixture().use { fixture ->
            fixture.activity.getSharedPreferences(TranslationCredentials.PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString("libre", "synthetic-legacy-value-must-not-follow-server").commit()
            val panel = fixture.open()
            val spinner = views(panel).filterIsInstance<Spinner>().single { it.contentDescription == "Moteur de traduction" }
            spinner.setSelection(TranslationProvider.LIBRE.ordinal)
            shadowOf(Looper.getMainLooper()).idle()
            val endpoint = field(panel, "Adresse de base du serveur LibreTranslate")
            val key = field(panel, "Clé API personnelle")
            endpoint.setText("https://one.example.test/prefix")
            assertEquals("", key.text.toString())
            key.setText("synthetic-server-one-key")
            views(panel).filterIsInstance<CheckBox>().single { it.text.toString().startsWith("Mémoriser cette clé") }.isChecked = true
            endpoint.setText("https://two.example.test/prefix")
            assertEquals("", key.text.toString())
            assertFalse(views(panel).filterIsInstance<CheckBox>().single { it.text.toString().startsWith("Mémoriser cette clé") }.isChecked)
            key.setText("synthetic-server-two-key")
            endpoint.setText("http://invalid.example.test")
            assertEquals("", key.text.toString())
            button(panel, "Indexer la piste et estimer la traduction").performClick()
            repeat(3) { fixture.controls.refresh() }
            assertTrue("Local validation error must survive periodic refresh", text(panel).contains("Adresse LibreTranslate invalide"))
            assertFalse(text(panel).contains("synthetic-server-one-key"))
            assertFalse(fixture.session.translation.busy)
            assertFalse(fixture.session.translation.required)
        }
    }

    @Test fun guidesOpenExplicitOfficialAndProjectUrlsWithoutStartingTranslation() {
        Fixture().use { fixture ->
            val panel = fixture.open()
            button(panel, "Guide complet : moteurs, clés API et traduction sur téléphone").performClick()
            assertEquals("https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/docs/GUIDE_MOTEURS_TRADUCTION.md",
                requireNotNull(shadowOf(fixture.activity).nextStartedActivity).data.toString())
            val spinner = views(panel).filterIsInstance<Spinner>().single { it.contentDescription == "Moteur de traduction" }
            for (provider in TranslationProvider.entries) {
                spinner.setSelection(provider.ordinal)
                shadowOf(Looper.getMainLooper()).idle()
                button(panel, "Documentation officielle du moteur choisi").performClick()
                val url = requireNotNull(requireNotNull(shadowOf(fixture.activity).nextStartedActivity).data)
                assertEquals("https", url.scheme)
                assertEquals(when (provider) {
                    TranslationProvider.AZURE -> "learn.microsoft.com"
                    TranslationProvider.LIBRE -> "docs.libretranslate.com"
                    TranslationProvider.GOOGLE_WEB -> "translate.google.com"
                    TranslationProvider.GOOGLE_LOCAL -> "developers.google.com"
                    TranslationProvider.LOCAL_GGUF -> "github.com"
                    else -> "developers.deepl.com"
                }, url.host)
            }
            assertFalse(fixture.session.translation.required)
            assertFalse(fixture.session.translation.busy)
        }
    }

    @Test fun localGgufShowsModelAndComputeControlsWithoutCredentialsOrNetworkChoices() {
        Fixture().use { fixture ->
            val panel = fixture.open()
            val spinner = views(panel).filterIsInstance<Spinner>().single { it.contentDescription == "Moteur de traduction" }
            spinner.setSelection(TranslationProvider.LOCAL_GGUF.ordinal)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(View.GONE, field(panel, "Clé API personnelle").visibility)
            assertEquals(View.GONE, field(panel, "Région Azure").visibility)
            assertEquals(View.GONE, field(panel, "Adresse de base du serveur LibreTranslate").visibility)
            assertEquals(View.GONE, field(panel, "Google en ligne : délai minimal").visibility)
            assertEquals(View.GONE, views(panel).filterIsInstance<CheckBox>()
                .single { it.text.toString().startsWith("Google local : autoriser") }.visibility)
            assertTrue(field(panel, "GGUF local : threads de calcul").isShown)
            assertTrue(field(panel, "GGUF local : contexte de l’œuvre").isShown)
            assertEquals("4", field(panel, "GGUF local : threads de calcul").text.toString())
            assertTrue(button(panel, "Choisir un modèle GGUF local pour traduire").isShown)
            assertTrue(text(panel).contains("Aucun compte, clé API, serveur ou téléchargement automatique"))
            assertFalse(fixture.session.translation.busy)
            assertFalse(fixture.session.player.playWhenReady)

            spinner.setSelection(TranslationProvider.AZURE.ordinal)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(field(panel, "GGUF local : threads de calcul").isShown)
            assertEquals(View.VISIBLE, field(panel, "Clé API personnelle").visibility)
        }
    }

    @Test fun missingGgufCannotPrepareATrackAndNoModelIsLoadedByOpeningOrRefreshing() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        java.io.File(context.filesDir, "local-diagnostic").deleteRecursively()
        Fixture().use { fixture ->
            val panel = fixture.open()
            views(panel).filterIsInstance<Spinner>().single { it.contentDescription == "Moteur de traduction" }.setSelection(TranslationProvider.LOCAL_GGUF.ordinal)
            shadowOf(Looper.getMainLooper()).idle()
            button(panel, "Indexer la piste et estimer la traduction").performClick()
            fixture.controls.refresh()
            assertTrue(text(panel).contains("Choisis un modèle GGUF ou prépare celui déjà importé"))
            assertFalse(fixture.session.translation.busy)
            assertFalse(fixture.session.translation.required)
            assertTrue(fixture.messages.isEmpty())
            assertFalse(java.io.File(context.filesDir, "local-diagnostic/model.gguf").exists())
        }
    }
}
