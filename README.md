# Sous-titres en voix — Cinéma 0.2

Lecteur Android local accessible, avec sous-titres textuels vocalisés. Media3/ExoPlayer est conservé ; l'interface de visionnage est inspirée des interactions de VLC, sans intégrer LibVLC ni reprendre son interface sous licence.

Cette version de test s'installe en parallèle de la 0.1 pour préserver l'application déjà essayée. Elle ne garantit pas l'absence de bug ni le confort de toutes les voix. Les résultats exécutés sont dans **Actions**.

## Installer

Télécharger l'artefact `SousTitresVoix-Cinema-APK` d'une exécution réussie. Extraire `app-debug.apk`, l'installer, choisir **Moteurs vocaux : choisir et télécharger**, puis une voix française dans **Réglages de la voix et du confort**. Lancer l'**Essai intégré**. Le bouton **Plein écran cinéma** masque les panneaux et les barres système ; toucher l'image fait apparaître les commandes. Retour déverrouille puis quitte le plein écran.

## Documents

`docs/V02-CINEMA-VOIX.md` détaille les fonctions, limites, téléchargements de moteurs, règles d'anticipation, mesures et essais à effectuer sur téléphone.

## Compiler et vérifier

Java 17, Gradle 8.13, SDK 36, AGP 8.11.1, Kotlin 2.2.21, Media3 1.11.1, FFmpeg pour la démonstration synthétique.

```
gradle --no-daemon :core:test :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
gradle --no-daemon :app:connectedDebugAndroidTest
```

Le pipeline conserve les rapports, les sources du commit réellement compilé, la signature et l'empreinte de l'APK. Il ne livre l'APK qu'après réussite des tests Android. Ni Internet, ni accès large au stockage, ni caméra/micro dans le lecteur. Les moteurs TTS restent des applications externes à choisir et vérifier.

Pas d'OCR, traduction, audiodescription, décodeurs LibVLC/FFmpeg intégrés ou accélérateur QNN embarqué. Le cache anticipatif ne couvre que les formats textuels indexables explicitement documentés ; la vocalisation directe reste disponible sans index.
