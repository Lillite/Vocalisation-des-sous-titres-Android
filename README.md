# Vocalisation des sous-titres Android — Sous-titres en voix

Lecteur vidéo Android local destiné en priorité à une personne malvoyante : vocalisation des sous-titres textuels, commandes explicites, baisse réglable du son du film et mode confort qui attend la fin d’une phrase.

**Version de test 0.1.0.** Un projet compilé ou un test automatisé réussi ne garantit ni l’absence de bugs ni le confort réel sur chaque téléphone. L’état de la compilation et les rapports exécutés sont dans l’onglet **Actions** de ce dépôt privé.

## Installer depuis un téléphone

1. Ouvrir **Actions**, puis l’exécution **Verifier et compiler Android** réussie (coche verte).
2. Dans **Artifacts**, télécharger **SousTitresVoix-APK-test** en étant connecté au compte autorisé sur ce dépôt.
3. Extraire le ZIP, ouvrir `app-debug.apk`, puis suivre la demande d’installation Android. Autoriser uniquement l’application utilisée pour ouvrir ce fichier, sans désactiver globalement les protections du téléphone.
4. Ouvrir **Sous-titres en voix**, puis **Réglages de la voix et du confort**. Choisir une voix française hors ligne et écouter son échantillon.
5. Lancer **Essai intégré : vidéo sous-titrée** avant d’essayer un fichier personnel.

Le workflow ne publie l’APK qu’après la réussite des contrôles de compilation, signature, manifeste et tests instrumentés. Aucun APK n’est promis pour une exécution en échec.

## Fonctions prévues par le code

- Vidéos locales, notamment conteneurs MKV, MP4 et WebM ; les codecs effectivement lisibles dépendent des décodeurs du téléphone.
- Sous-titres textuels intégrés et fichiers externes SRT, VTT, ASS/SSA. Choix explicite de l’encodage pour les fichiers anciens.
- Synthèse vocale Android avec voix installées déclarées utilisables hors ligne. Pas de LLM ni de compte nécessaire pendant le visionnage.
- Mode confort activé par défaut : si la voix n’a pas terminé à la transition suivante, la vidéo attend.
- Mode continu facultatif : pas d’attente pour la voix, mais les phrases trop longues peuvent être interrompues.
- Volumes séparés de la voix et du film, baisse progressive du film pendant la parole, vitesse de la voix, taille des sous-titres, plein écran et reprise de position.
- Pause explicite, retour/avance de dix secondes, répétition de la dernière phrase et messages d’erreur visibles.

## Limites

Pas d’OCR pour les sous-titres en images PGS/VobSub ou incrustés dans la vidéo ; pas de traduction, de doublage humain ni d’audiodescription des scènes. Aucun accès aux contenus DRM d’autres applications. Pas de décodeurs FFmpeg intégrés au lecteur : FFmpeg sert uniquement à produire la vidéo synthétique de test pendant la compilation. Lecture en arrière-plan non prise en charge dans cette version.

L’application source ne demande aucune permission. Le workflow vérifie aussi le manifeste de l’APK compilé pour exclure notamment Internet, microphone, caméra, contacts et accès général au stockage. Le moteur vocal Android reste un composant externe dont les voix doivent être vérifiées sur appareil.

## Compilation et tests

Chaîne fixée : Java 17, Gradle 8.13, Android SDK 36, Build Tools 36.0.0, AGP 8.11.1, Kotlin 2.2.21, Media3 1.11.1. Minimum Android configuré : API 26 (Android 8). Pour une compilation locale, installer également FFmpeg avec les encodeurs libx264 et AAC sur le PATH.

```sh
gradle --no-daemon :core:test :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
# Avec un appareil ou émulateur lancé :
gradle --no-daemon :app:connectedDebugAndroidTest
```

La tâche `generateDemo` crée automatiquement un MKV H.264/AAC/SubRip avec les huit phrases françaises de `examples/demonstration.srt`. Aucun fichier vidéo personnel n’est utilisé ou transmis. L’APK se trouve ensuite dans `app/build/outputs/apk/debug/app-debug.apk`.

Le module `core` est indépendant d’Android. Sa suite comporte 54 contrôles et 10 000 transitions pseudo-aléatoires par graine. JUnit répète la suite avec les graines 42, 2026 et 7351. Les deux tests instrumentés vérifient une commande explicitement libellée et l’émission d’un sous-titre français par la lecture réelle du MKV sur Android. Ils ne valident pas le timbre d’une voix, TalkBack complet ou une séance de deux heures.

```sh
# Alternative locale sans Android/Gradle, avec kotlinc installé :
bash tools/check-core.sh 42
python3 tools/check-project.py
```

## Signature

L’APK livré par le workflow est une version **debug**, signée pour des essais privés. Aucune clé privée n’est versionnée. La clé de test peut différer entre les exécutions ; configurer une clé stable conservée en sécurité avant une distribution suivie. Ne pas publier cette version comme une version de production.

## Documents

- `docs/TESTS_TELEPHONE.md` : parcours de validation sur le téléphone cible.
- `docs/ARCHITECTURE.md` : séparation du moteur, des API Android et de l’interface.
- Les artefacts GitHub Actions contiennent les résultats JUnit, le rapport Android Lint, le manifeste extrait et l’empreinte SHA-256 de l’APK.
