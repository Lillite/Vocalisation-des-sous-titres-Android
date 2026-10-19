# Sous-titres en voix — édition Cinéma 0.2

Cette branche `cinema-0.2` est une édition de test distincte. La branche `main` et l’application 0.1 restent intactes. L’identifiant `fr.soustitresvoix.player.cinema` permet l’installation à côté de la version initiale ; ses préférences sont séparées.

## Installer et utiliser

Le workflow **Cinema - verifier et compiler** publie `SousTitresVoix-Cinema-APK` uniquement après réussite des contrôles, du lint, de la compilation et des tests instrumentés. Consulter l’exécution correspondante dans Actions. Extraire l’APK de l’artefact puis l’installer sur Android. Ne pas désactiver Play Protect.

Dans l’application : ouvrir une vidéo, choisir ses sous-titres textuels, choisir une voix de même langue, puis Lire et **Plein écran cinéma**. Un toucher affiche les commandes superposées. Retour déverrouille d’abord, puis quitte le plein écran ; il ne ferme pas immédiatement la vidéo.

## Fonctionnalités de cette branche

- Plein écran immersif, commandes superposées, masquage réglable (0 = jamais), orientation système/paysage/verrouillée et zones sûres pour les boutons et sous-titres.
- Adapter (image entière), Remplir (recadrage), Étirer (déformation), zoom et recentrage. Gestes facultatifs et boutons équivalents ; désactivation des gestes personnalisés avec l’exploration tactile TalkBack.
- Réglages rapides des volumes sans pause de la vidéo. La voix en cours garde son volume jusqu’au prochain segment.
- Moteur de synthèse vocale choisi uniquement pour cette application, sans changement des paramètres de TalkBack ; choix parmi les voix locales installées.
- Modes Confort et Continu conservés ; mode Adaptatif avec accélération plafonnée lorsque durée audio et intervalle du sous-titre sont connus. Sinon, vitesse normale et pause de confort. Aucun résumé ni suppression automatique du dialogue.
- Préparation par `synthesizeToFile` des sous-titres déjà chargés, cache local de 16 à 256 Mio, 1 à 12 segments anticipés. Synthèse directe prioritaire et repli direct si le moteur ne fournit pas un WAV compatible. Il ne s’agit pas d’une préparation garantie de tout le film.
- Reprise de la réplique entière, du segment interrompu ou par commande explicite. Répétition sans conserver un doublon dans la file.
- Baisse du son du film : profondeur, durée de baisse, remontée et délai entre répliques réglables.
- Filtres facultatifs des crochets, étiquettes de locuteur en majuscules, lignes musicales explicitement marquées, notes de traduction explicites. Aperçu original/texte vocalisé ; le texte affiché reste intact.
- Identités temporelles de sous-titres lorsque disponibles, pour distinguer la présentation répétée de la même occurrence et une réplique identique plus tard. Limites explicites de mémoire pour l’index anticipé.
- Diagnostic local : décodeur choisi, images abandonnées, CPU et mémoire du processus du lecteur, température de batterie et état thermique Android, temps de synthèse et cache. Les mesures n’incluent pas automatiquement un moteur vocal externe. Aucun accès NPU/GPU vocal n’est présumé.

## Voix et téléchargements

SherpaTTS : https://f-droid.org/packages/org.woheller69.ttsengine/

Acapela TTS Voices (éditeur) : https://www.acapela-group.com/fr/solutions/acapela-tts-voices-for-google-play/

Synthèse Google : https://play.google.com/store/apps/details?id=com.google.android.tts

Installer le moteur, ouvrir celui-ci et télécharger une voix française, puis revenir dans **Réglages de la voix et du confort → Choisir le moteur vocal de CE lecteur → Actualiser les moteurs et les voix → Choisir une voix hors ligne**. SherpaTTS propose la gestion de langues et des modèles Piper/Coqui. Un modèle ONNX isolé n’est pas directement importable par ce lecteur. La qualité sonore doit être comparée à l’écoute sur le téléphone cible.

## Tests et limites

Les tests de régression du noyau initial sont conservés. La suite Cinéma ajoute 31 contrôles : segmentation/reprise, filtres, identités, plafonnement adaptatif, WAV et éviction du cache. Les tests Android ajoutent la géométrie réelle du plein écran, les barres système en paysage, la conservation du lecteur, la prélecture MKV, les réglages rapides et un service TTS de test qui produit un PCM synthétique. Ce dernier prouve le chemin synthèse/cache/lecture, pas la qualité d’une voix naturelle. Les résultats effectivement exécutés sont dans les rapports de chaque workflow.

Ni OCR, ni traduction, ni LibVLC, ni envoi de films sur Internet. Pas de lecture en arrière-plan dans cette version. Les sous-titres en images/incrustés et les codecs non disponibles restent des limites. Le fonctionnement complet avec TalkBack, la consommation et la qualité des moteurs vocaux du OnePlus doivent être évalués sur ce téléphone, pas déduits d’un émulateur.

Signature debug pour essais privés : aucune clé privée n’est versionnée. La clé peut changer entre builds. Ne pas présenter cette édition comme une version de production. Prévoir une signature stable hors du dépôt avant des mises à jour suivies.
