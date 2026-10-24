package fr.soustitresvoix.player

/**
 * Explanations of the existing film-audio controls. No playback or preference
 * access belongs here. The application-specific semantics are defined by
 * PlaybackSession, PlayerPreferences and the unchanged SpeechBandProcessor.
 */
internal object AudioSettingsDescriptions {
    const val INTRO = "Ces six réglages concernent le son du film. Ils sont conservés pour les prochaines vidéos " +
        "et les prochaines ouvertures de l’application. Dans « Volumes rapides », leurs commandes ne mettent pas " +
        "la lecture en pause et ne relancent pas la voix. Le panneau général « Voix et confort » conserve sa mise " +
        "en pause habituelle. Modifie un réglage à la fois, puis compare sur le même passage. " +
        "Une modification réelle interrompt un banc d’essai en cours pour conserver des mesures comparables."

    const val MOVIE_VOLUME = "Règle le volume normal du film de 0 à 100 %, avec 100 % par défaut. " +
        "Zéro rend le film silencieux. Ce réglage ne change ni le volume de la voix ni celui du téléphone. " +
        "Dans « Volumes rapides », les boutons arrondissent d’abord le pourcentage à l’entier le plus proche, puis ajoutent ou retirent " +
        "5 points, avec arrêt aux bornes : " +
        "53 % plus 5 points donne 58 % ; 37,5 % devient 38 %, puis 43 % après une augmentation. " +
        "Saisir permet d’entrer directement un pourcentage décimal, par exemple 37,5. La valeur retenue par le lecteur est affichée. " +
        "Une modification applique immédiatement la nouvelle cible, réduite si la voix " +
        "est signalée active, et remplace le fondu ou l’attente de remontée en cours. " +
        "Le bouton de réinitialisation du panneau de réglages audio rétablit 100 %. Dans « Voix et confort », utilise le curseur. " +
        "Ce pourcentage est une consigne, pas une mesure du son entendu."

    const val DUCK_VOLUME = "Règle la part du volume normal conservée pendant la voix, de 0 à 100 %, avec 25 % par défaut. " +
        "Ce pourcentage multiplie le volume normal : un film à 60 % avec ce réglage à 25 % reçoit une cible de 15 %. " +
        "Zéro coupe le film pendant la voix ; 100 % supprime la baisse uniforme, mais un filtre spectral activé peut " +
        "encore agir. Dans « Volumes rapides », les boutons arrondissent d’abord à l’entier le plus proche, puis ajoutent ou retirent " +
        "5 points, avec arrêt aux bornes. Saisir permet un pourcentage décimal, par exemple 19,125. " +
        "La valeur retenue par le lecteur est affichée. " +
        "Une modification recalcule immédiatement le niveau selon l’état de la voix et remplace le fondu ou " +
        "l’attente en cours. Le bouton de réinitialisation du panneau de réglages audio rétablit 25 %. " +
        "Dans « Voix et confort », utilise le curseur. La voix narrée garde son propre volume."

    const val ATTACK = "Règle la durée demandée pour baisser progressivement le film lorsque la narration signale " +
        "une voix active. Plage : de 0 à 2 000 millisecondes ; valeur par défaut : 160 ms. " +
        "Les boutons ajoutent ou retirent 40 ms, avec arrêt aux bornes. Saisir permet toute durée entière de cette plage, par exemple 275 ms. " +
        "Une valeur plus longue rend la baisse " +
        "plus progressive ; zéro demande l’application de la cible au prochain passage du lecteur. " +
        "Le changement concerne la prochaine baisse, sans recommencer le fondu déjà planifié. " +
        "Le bouton de réinitialisation rétablit 160 ms. Cette durée ne règle ni le débit ni le démarrage de la voix."

    const val RELEASE = "Règle la durée demandée pour remonter progressivement au volume normal, après l’attente " +
        "avant remontée. Plage : de 0 à 2 000 millisecondes ; valeur par défaut : 500 ms. " +
        "Les boutons ajoutent ou retirent 100 ms, avec arrêt aux bornes. Saisir permet toute durée entière de cette plage, par exemple 875 ms. " +
        "Une valeur plus longue ralentit le retour " +
        "du film ; zéro supprime le fondu de remontée, mais ne supprime pas l’attente qui le précède. " +
        "Le changement concerne la prochaine remontée planifiée et ne modifie pas celle déjà en cours ou en attente. " +
        "Le bouton de réinitialisation rétablit 500 ms. Une voix terminée ne signifie donc pas un volume déjà rétabli."

    const val HOLD = "Règle l’attente minimale demandée avant de remonter le film lorsque la voix n’est plus " +
        "signalée active. Plage : de 0 à 2 000 millisecondes ; valeur par défaut : 350 ms. " +
        "Les boutons ajoutent ou retirent 50 ms, avec arrêt aux bornes. Saisir permet toute durée entière de cette plage, par exemple 425 ms. " +
        "Si la lecture est toujours demandée " +
        "et qu’un prochain sous-titre de la piste choisie est connu dans les 1 200 ms, l’attente peut être prolongée " +
        "jusqu’à ce début plus 150 ms. Zéro retire l’attente minimale, sans désactiver cette anticipation. " +
        "Le changement concerne la prochaine remontée planifiée, sans modifier une attente déjà programmée. " +
        "Le bouton de réinitialisation rétablit 350 ms. Ce réglage ne retarde pas volontairement la voix."

    const val SPECTRAL = "Coche pour demander, pendant la voix, une atténuation expérimentale de la bande " +
        "250 à 3 200 Hz du film. Ce filtre est désactivé par défaut ; décoche ou réinitialise pour le désactiver. " +
        "Il ne sépare pas les dialogues des autres sons et peut modifier la musique. Il s’ajoute à la baisse " +
        "uniforme et ne traite pas la voix narrée. Il agit seulement lorsque le son traverse le processeur PCM16 " +
        "du lecteur ; son activation ne garantit pas une atténuation audible sur tous les parcours audio. " +
        "La commande change l’activation du processeur existant, qui conserve son lissage ; elle ne redémarre " +
        "ni le film ni la voix. Les fréquences et l’intensité internes ne sont pas réglables dans cette étape."

    const val OBSERVATION = "Les valeurs demandées sont les réglages enregistrés. Le volume configuré est celui " +
        "rapporté par le lecteur à cet instant ; il peut se trouver entre deux niveaux pendant un fondu. " +
        "Le signal de parole appartient à la narration et ne se règle pas ici. Le compteur du processeur " +
        "cumule les échantillons de tous les canaux depuis le début de la session, y compris sans atténuation : " +
        "une trame stéréo compte pour deux échantillons. Il ne repart pas à zéro à chaque vidéo. " +
        "Ces informations ne mesurent pas le volume acoustique " +
        "ni l’atténuation réellement entendue. Actualiser l’état consulte ces données sans changer les réglages."
}
