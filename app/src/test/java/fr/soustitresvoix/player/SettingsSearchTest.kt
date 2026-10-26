package fr.soustitresvoix.player

import org.junit.Assert.*
import org.junit.Test

class SettingsSearchTest {
    @Test fun searchIgnoresAccentsCaseAndTokenOrder() {
        assertTrue(SettingsSearch.matches("ACCELERATION vitesse", "Vitesse : accélération maximale"))
        assertTrue(SettingsSearch.matches("sous titres", "Sous-titres"))
        assertTrue(SettingsSearch.matches("coeur", "cœur"))
        assertFalse(SettingsSearch.matches("cache traduction", "Cache vocal"))
        assertTrue(SettingsSearch.matches("   ", "Voix"))
    }
}
