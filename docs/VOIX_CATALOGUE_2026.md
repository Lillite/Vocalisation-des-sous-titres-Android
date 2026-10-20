# Catalogue des moteurs vocaux Android — vérification documentaire du 20 octobre 2026

Catalogue documentaire étendu, non exhaustif. Provenance primaire consultée ; moteurs externes non essayés sur le OnePlus. Les rubriques historiques, passerelles, sources et modèles ne sont pas comptées comme moteurs directement compatibles.

## Installation dans le lecteur

Installer le moteur depuis sa source officielle, ouvrir le moteur, installer ses voix, puis revenir dans **Voix / écran → Choisir le moteur vocal de CE lecteur → Choisir une voix locale**. Aucun changement du moteur de TalkBack n’est nécessaire. Une voix dite locale est une déclaration de son moteur, pas une preuve de son absence de trafic réseau. Ne pas désactiver Play Protect. Ne pas acheter avant un essai compatible.

## 1. SherpaTTS

**Type :** Moteur Android — local. **Langues :** Français et autres langues selon modèle.

**Distribution :** APK F-Droid ; modèles à télécharger dans Manage Languages. **Taille :** APK F-Droid 3.4 : 86 Mio ; modèles supplémentaires variables.

Piper/Coqui via sherpa-onnx. Télécharger une voix dans le moteur, puis sélectionner ce moteur dans CE lecteur. La qualité dépend du modèle ; non testé ici sur OnePlus.

Source primaire : https://f-droid.org/packages/org.woheller69.ttsengine/

## 2. Sherpa-ONNX TTS Engine — Miro high

**Type :** Moteur Android avec voix intégrée. **Langues :** Français (Miro).

**Distribution :** La page distingue explicitement Android TTS Engine APK et archive du modèle. **Taille :** Taille variable : consulter le téléchargement arm64-v8a.

Choisir Android APK, arm64-v8a, Download. Le .tar.bz2 est un modèle, pas une application. Des APK différents peuvent partager un identifiant : ne pas présumer leur coexistence.

Source primaire : https://k2-fsa.github.io/sherpa/onnx/tts/all/French/vits-piper-fr_FR-miro-high.html

## 3. Sherpa-ONNX — catalogue de moteurs APK

**Type :** Famille de moteurs Android — local. **Langues :** Nombreuses langues et modèles, dont français.

**Distribution :** Index officiel de moteurs APK par modèle. **Taille :** Dépend du modèle ; vérifier chaque fichier.

Utiliser les APK de moteur TTS, pas les démonstrations ASR ou TTS. La famille n’est pas comptée comme des centaines de moteurs indépendants. Un changement d’APK peut remplacer le modèle précédent.

Source primaire : https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine.html

## 4. Synthèse vocale Google

**Type :** Moteur Android — local ou réseau selon voix. **Langues :** Français et autres langues.

**Distribution :** Distribution Google Play ; disponibilité selon appareil. **Taille :** Taille et packs déterminés par Google Play.

Installer les données vocales françaises dans les réglages du moteur. Le lecteur ne sélectionne que les voix déclarées installées et utilisables sans réseau. Ce filtre n’est pas un audit du moteur externe.

Source primaire : https://play.google.com/store/apps/details?id=com.google.android.tts

## 5. Acapela TTS Voices

**Type :** Moteur Android — voix locales payantes. **Langues :** Français et autres langues du catalogue.

**Distribution :** Google Play ; essais et achats intégrés selon offre. **Taille :** Application et données variables selon voix.

Écouter la voix Android avant achat. Une démonstration Web peut différer de la version Android. Aucun achat automatique ; compatibilité réelle à vérifier sur le téléphone.

Source primaire : https://play.google.com/store/apps/details?id=com.acapelagroup.android.tts

## 6. Vocalizer TTS — Code Factory

**Type :** Moteur Android — voix locales payantes. **Langues :** Français France/Canada et autres langues.

**Distribution :** Google Play ; vérifier l’essai et la compatibilité affichés. **Taille :** Packs variables selon voix.

Choisir une voix française et tester avant paiement. La présence sur Google Play ne prouve pas le fonctionnement de toutes les versions sur Android 16.

Source primaire : https://play.google.com/store/apps/details?id=es.codefactory.vocalizertts

## 7. eSpeak NG / eSpeak Android

**Type :** Moteur Android — local. **Langues :** Français et nombreuses autres langues.

**Distribution :** APK F-Droid ; synthèse compacte, non comparable à une voix neuronale expressive. **Taille :** F-Droid 1.52.0 : 9 Mio.

Moteur de secours compact. Préférer une version arm64 récente plutôt que d’anciens APK uniquement 32 bits. Intelligibilité et naturalité sont deux critères distincts.

Source primaire : https://f-droid.org/packages/com.reecedunn.espeak/

## 8. RHVoice

**Type :** Moteur Android — local. **Langues :** Catalogue multilingue ; français à vérifier, ne pas le supposer.

**Distribution :** Projet officiel avec distribution Android. **Taille :** Variable selon voix.

Consulter les langues et le guide Android officiels. Installer le moteur puis les données de la langue. Ne pas choisir pour des sous-titres français sans voix française effectivement proposée.

Source primaire : https://github.com/RHVoice/RHVoice

## 9. NekoSpeak

**Type :** Moteur Android — modèles locaux. **Langues :** Dépend des modèles Piper, Kokoro, Kitten, Pocket.

**Distribution :** APK de l’auteur ; projet communautaire. **Taille :** L’auteur indique 88 Mo arm64 pour 1.4.2, hors modèles optionnels ; version à vérifier.

Utiliser les Releases du projet. Choisir une voix française compatible ; les familles de modèles ne parlent pas toutes les mêmes langues. Ni qualité ni accélération NPU garanties.

Source primaire : https://github.com/siva-sub/NekoSpeak

## 10. HayaiTTS

**Type :** Moteur Android — modèles locaux. **Langues :** Dépend du modèle ; catalogue multilingue.

**Distribution :** APK dans les Releases de l’auteur. **Taille :** Variable selon APK et modèles choisis.

Gestion de plusieurs familles de modèles. Télécharger une voix puis vérifier qu’elle apparaît dans la liste des voix locales du lecteur. Projet communautaire non certifié pour notre appareil.

Source primaire : https://github.com/HayaiApp/HayaiTTS

## 11. Kokoly

**Type :** Moteur Android — Kokoro local. **Langues :** Multilingue, dont français selon modèle fourni.

**Distribution :** APK de l’auteur ; arm64. **Taille :** L’auteur annonce environ 450 Mio de modèles ; téléchargement exact à vérifier.

Choisir le moteur après l’installation des données. Les limites du modèle subsistent ; ne pas supposer qu’un fichier ONNX arbitraire peut être importé.

Source primaire : https://github.com/loom7/kokoly

## 12. Supertonic for Android — DevGitPit

**Type :** Moteur Android — local. **Langues :** Selon la version du modèle et les langues proposées.

**Distribution :** L’auteur fournit des liens F-Droid et Google Play. **Taille :** Variable selon version et modèles.

Implémentation TTS système documentée. La distribution F-Droid est arm64. Vérifier une voix française dans la version installée avant de remplacer la voix habituelle.

Source primaire : https://github.com/DevGitPit/supertonic-android

## 13. Piper Android — piper-tts-apps

**Type :** Projet de moteur Android — local. **Langues :** Langues des modèles Piper, dont français selon voix.

**Distribution :** Projet et service TTS documentés ; vérifier les APK effectivement publiés. **Taille :** Non confirmé ; consulter Releases.

Ne pas confondre le projet Android avec la commande Piper pour ordinateur. Un dépôt source n’est pas une garantie qu’un APK prêt à installer existe pour chaque version.

Source primaire : https://github.com/piper-tts-apps/piper-app-android/

## 14. Maise

**Type :** Projet de moteur Android — Kokoro local. **Langues :** Catalogue comprenant une voix française selon le projet.

**Distribution :** Sources officielles ; vérifier la disponibilité des Releases. **Taille :** Non confirmé ; modèles supplémentaires.

Source identifiée ; installation prête à l’emploi non certifiée dans cet audit. Ne pas télécharger un APK d’un miroir inconnu pour compenser l’absence de Release.

Source primaire : https://github.com/Mobile-Artificial-Intelligence/maise

## 15. Kokoro Offline TTS — expérimentation QNN

**Type :** Moteur Android expérimental — local. **Langues :** Anglais ; ce n’est pas la recommandation pour le français.

**Distribution :** APK / sources de l’auteur ; accélération testée par l’auteur sur un autre téléphone. **Taille :** L’auteur annonce environ 1,4 Gio pour une édition avec contextes.

QNN ciblé notamment sur Galaxy S24 Ultra / Snapdragon 8 Gen 3 / HTP v75. Cela ne valide PAS le NPU du OnePlus 15. Ne pas importer un contexte compilé pour un autre matériel en supposant sa compatibilité.

Source primaire : https://github.com/cedgeremek/kokoro-offline-tts-android

## 16. Supertonic TTS — chukfinley

**Type :** Prototype de moteur Android — sources. **Langues :** Selon modèle Supertonic.

**Distribution :** Projet source ; ne pas présenter comme un APK certifié. **Taille :** Dépend des modèles et de la compilation.

Alternative de développement, pas un moteur à recommander sans vérifier les Releases, signatures et la compatibilité. Ne pas confondre ses versions avec celles de DevGitPit.

Source primaire : https://github.com/chukfinley/supertonic-tts

## 17. Flite TTS Engine for Android

**Type :** Moteur historique / sources. **Langues :** Principalement voix du catalogue Flite ; français non confirmé.

**Distribution :** Ancien projet ; compatibilité Android récent et arm64 non confirmée. **Taille :** Variable / non confirmé.

Référence historique, pas un premier choix pour OnePlus 15. Éviter les APK reconditionnés et ne pas désactiver les protections du téléphone.

Source primaire : https://github.com/happyalu/Flite-TTS-Engine-for-Android

## 18. ChineseTtsTflite

**Type :** Projet Android spécialisé — local. **Langues :** Chinois ; pas de français annoncé ici.

**Distribution :** Sources et anciennes Releases ; compatibilité actuelle non confirmée. **Taille :** Variable / non confirmé.

Projet spécialisé, à distinguer d’un moteur multilingue. La présence de TensorFlow Lite ne prouve pas un calcul GPU ou NPU effectif.

Source primaire : https://github.com/benjaminwan/ChineseTtsTflite

## 19. Hear2Read

**Type :** Moteur Android spécialisé. **Langues :** Langues indiennes.

**Distribution :** Guide officiel Android ; disponibilité actuelle de la distribution à vérifier. **Taille :** Variable selon voix.

Moteur et packs de langues séparés. Non recommandé pour le français. Distribution actuelle non suffisamment confirmée pour promettre une installation immédiate.

Source primaire : https://hear2read.org/solution-android

## 20. TTS Server Android — jing332

**Type :** Passerelle Android, pas une voix autonome. **Langues :** Dépend du fournisseur et de la configuration.

**Distribution :** Projet Android ; peut appeler des services réseau. **Taille :** Variable ; modèles ou services externes.

Des sous-titres pourraient être envoyés à un fournisseur. Ne pas importer de script inconnu ni de clés dans le lecteur. Une passerelle déclarée locale n’est pas nécessairement réellement hors ligne.

Source primaire : https://github.com/jing332/tts-server-android

## 21. TalkifyTTS

**Type :** Passerelle / moteur hybride Android. **Langues :** Dépend des services et modèles configurés.

**Distribution :** Projet communautaire avec intégrations locales ou distantes. **Taille :** Variable ; éventuels services payants.

Distinguer traitement local et distant avant utilisation. Pas de clé ni d’abonnement fourni. Le lecteur reste sans permission Internet ; cela ne bloque pas le réseau d’un moteur externe.

Source primaire : https://github.com/LonePheasantWarrior/TalkifyTTS

## 22. CerePlay / CereProc

**Type :** Application vocale — intégration système à confirmer. **Langues :** Selon catalogue CereProc.

**Distribution :** Application officielle Android ; ne pas la présenter sans preuve comme un moteur système. **Taille :** Variable selon application et voix.

La page consultée confirme une application vocale, pas à elle seule sa disponibilité dans le sélecteur TTS Android. Les anciennes voix CereProc en applications séparées sont distinctes. Tester sans achat d’abord.

Source primaire : https://app.cereproc.com/cereplay/

## 23. Piper / Kokoro / Coqui / Supertonic : fichiers modèles

**Type :** Modèles — pas des APK installables. **Langues :** Dépend du modèle exact.

**Distribution :** À télécharger seulement via le moteur compatible ou le projet officiel. **Taille :** De quelques dizaines à plusieurs centaines de Mo, parfois davantage ; exact non établi.

Un .onnx, .bin, .tar.bz2 ou .zip de poids ne s’installe pas comme une application Android. Vérifier modèle, phonémiseur, tokens, licence et version du moteur. Le lecteur n’importe pas de modèle brut.

Source primaire : https://k2-fsa.github.io/sherpa/onnx/tts/index.html
