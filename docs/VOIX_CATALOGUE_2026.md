# Catalogue guidé des moteurs vocaux Android

[Accueil](../README.md) · [Documentation](README.md) · [Essais sur téléphone](TESTS_TELEPHONE.md)

Le catalogue vocal est conservé dans la 0.5.1. La date d’audit ci-dessous décrit la vérification documentaire des moteurs ; elle n’est pas une nouvelle mesure de leurs voix sur téléphone.

Vérification documentaire : **20 octobre 2026**. Le catalogue embarqué comprend **41 notices**, avec des liens vers les éditeurs, auteurs, projets ou canaux de distribution qu’ils indiquent. Il vise une couverture large, y compris les moteurs régionaux et les projets récents. Il ne garantit pas de recenser tous les produits privés, variantes constructeur, anciens moteurs ou distributions régionales.

La vérification porte sur les descriptions et les modes de distribution. **Aucun de ces moteurs externes n’a été installé, écouté, mesuré ou validé sur le OnePlus dans cet audit.** Les déclarations des auteurs sur les langues, le calcul local et les performances restent des déclarations documentaires. L’application n’installe rien automatiquement et le catalogue ne demande aucune permission Internet supplémentaire au lecteur.

## Comprendre ce qui peut être installé

| Catégorie | Notices | Ce que cela représente |
|---|---:|---|
| Moteurs Android | 24 | Produits ou distributions décrivant une intégration Android TTS ; certains restent expérimentaux ou soumis à conditions d’accès. |
| Variante avec modèle et index d’une famille | 2 | APK Sherpa avec Miro et index Sherpa ; ce ne sont pas deux nouvelles technologies de synthèse. |
| Passerelles Android | 3 | Services TTS qui utilisent un autre moteur ou fournisseur. Le traitement peut être local ou distant. |
| Moteur constructeur | 1 | Samsung, dont la distribution ne prouve aucune compatibilité OnePlus. |
| Projets sources | 4 | Intégration documentée, mais disponibilité actuelle d’un APK prêt à installer non confirmée ici. |
| Références historiques | 3 | Anciens moteurs ou sources ; compatibilité récente non établie. |
| Application en fin de maintenance | 1 | CerePlay général, à distinguer des autres offres CereProc. |
| Offre éditeur à préciser | 1 | AlfaNum : proposition Android sans téléchargement public de moteur établi dans cet audit. |
| Démonstration | 1 | Interface de synthèse dont l’exposition comme moteur système n’est pas établie. |
| Modèles | 1 | Familles de fichiers de poids, qui nécessitent un moteur compatible. |

**41 notices ne signifie donc ni 41 moteurs indépendants, ni 41 installations vérifiées.** Deux ports eSpeak partagent la même famille de synthèse ; les APK Sherpa par modèle partagent aussi une infrastructure. Un modèle français ne rend pas toutes les autres voix du moteur francophones.

## Points qui changent concrètement le choix

- **Miro est une voix Piper.** La page sherpa-onnx propose séparément une archive de modèle et un véritable APK de moteur Android. Pour un usage direct, choisir la section Android TTS Engine APK et l’architecture compatible. [Page officielle Miro](https://k2-fsa.github.io/sherpa/onnx/tts/all/French/vits-piper-fr_FR-miro-high.html).
- **RHVoice ne propose pas de français dans la liste officielle consultée.** La présence d’une documentation traduite ne change pas les langues de synthèse. [Langues RHVoice](https://rhvoice.org/languages/).
- **Eloquence possède une nouvelle édition Android 64 bits.** Le fournisseur a annoncé son retour en décembre 2025 ; la fiche actuelle exige abonnement et Internet. Ce n’est donc pas un choix garanti pour un usage entièrement local. [Annonce Code Factory](https://codefactoryglobal.com/eloquence-for-android-is-coming-back-2/) et [fiche actuelle](https://play.google.com/store/apps/details?id=com.codefactoryglobal.eloquencetts).
- **CerePlay général annonce sa fin de maintenance.** Le programme CereProc Scottish Education a sa propre distribution et ses conditions. [CerePlay](https://play.google.com/store/apps/details?id=com.cereproc.cerevoicesapp) et [programme écossais](https://www.thescottishvoice.org.uk/download/android/).
- **Hear2Read NG remplace la référence trop vague à Hear2Read.** Les langues et les prérequis se lisent sur sa propre fiche, sans supposer que tous les anciens packs conviennent. [Hear2Read NG](https://play.google.com/store/apps/details?id=org.hear2read.h2rng).
- **Un moteur installé peut conserver ses propres besoins réseau.** Une passerelle peut exposer une voix Android tout en appelant un fournisseur distant. Les permissions du lecteur et de ce moteur externe sont distinctes : la 0.4.0 dispose de la permission Internet pour la traduction, et les communications du moteur vocal dépendent de ses propres permissions et réglages. [TTS Server Android](https://github.com/jing332/tts-server-android) et [TalkifyTTS](https://github.com/LonePheasantWarrior/TalkifyTTS).

Pour le français local, les notices Google, SherpaTTS, Sherpa/Miro, Kokoly, Acapela et Vocalizer fournissent des voies d’installation identifiées ; les autres projets multilingues ajoutent des possibilités selon leurs modèles. Le catalogue ne classe pas leur naturalité ni leur rapidité : cela demande la même phrase, le même débit et des mesures sur l’appareil.

## Lire les tailles et les performances

La taille d’un APK ne couvre pas toujours le modèle téléchargé ensuite, son extraction ou le cache audio du lecteur. Les anciennes tailles fixes non revérifiées ont été retirées. Quand une estimation est conservée, elle est attribuée à l’auteur ; le fichier effectivement proposé fait foi. Le seul fait qu’un fichier soit au format ONNX ou TensorFlow Lite ne démontre pas l’utilisation du GPU ou du NPU.

NekoSpeak documente actuellement un chemin CPU et les obstacles rencontrés avec NNAPI/QNN. L’expérience Kokoro/QNN cible un matériel précis différent du OnePlus ; CloneTTS annonce un chemin NPU sans mesure indépendante réalisée ici. Ces trois constats ne permettent aucun classement matériel de ce téléphone. [NekoSpeak](https://github.com/siva-sub/NekoSpeak), [expérience Kokoro/QNN](https://github.com/cedgeremek/kokoro-offline-tts-android), [CloneTTS](https://github.com/sipeter/CloneTTS).

## Notices et installation

Les champs détaillés, les distinctions de compatibilité et les sources sont aussi embarqués dans `app/src/main/assets/voice-catalogue.json`. Les identifiants de paquet ci-dessous sont conservés uniquement lorsqu’une fiche de distribution ou le projet les établit ; l’absence d’identifiant ne signifie pas l’absence d’application.


### 1. Speech Recognition & Synthesis — Google

**Moteur Android — local ou réseau selon voix.** Français et autres langues selon les données installées.

Distribution : Application Google Play ; disponibilité selon appareil. Taille : Application et packs variables, affichés par Google Play.

Installation : Installer ou mettre à jour le moteur, télécharger les données françaises dans ses réglages, puis choisir le moteur et la voix dans ce lecteur.

Paquet identifié : `com.google.android.tts`.

Sources : [Page principale](https://play.google.com/store/apps/details?id=com.google.android.tts).


### 2. SherpaTTS

**Moteur Android — local.** Français et autres langues selon modèle Piper/Coqui compatible.

Distribution : Application F-Droid et projet de l’auteur ; voix à télécharger. Taille : APK et modèles variables ; consulter la fiche de la version choisie.

Installation : Installer SherpaTTS, ouvrir Manage Languages, télécharger une voix française, puis sélectionner le moteur et la voix dans ce lecteur.

Paquet identifié : `org.woheller69.ttsengine`.

Sources : [Page principale](https://f-droid.org/packages/org.woheller69.ttsengine/) · [Complément 1](https://github.com/woheller69/ttsEngine).


### 3. Sherpa-ONNX TTS Engine — Miro high

**Moteur Android avec voix française intégrée.** Français — modèle Piper Miro high.

Distribution : Page officielle avec Android TTS Engine APK et archive du modèle distincts. Taille : Dépend du fichier et de l’architecture ; lire la taille du téléchargement.

Installation : Sur la page du modèle, ouvrir Android TTS Engine APK et choisir arm64-v8a pour un téléphone ARM64 compatible. Le fichier .tar.bz2 est une archive de modèle, pas l’application.

Sources : [Page principale](https://k2-fsa.github.io/sherpa/onnx/tts/all/French/vits-piper-fr_FR-miro-high.html) · [Complément 1](https://github.com/k2-fsa/sherpa-onnx/tree/master/android/SherpaOnnxTtsEngine) · [Complément 2](https://huggingface.co/OpenVoiceOS/pipertts_fr-FR_miro).


### 4. Sherpa-ONNX — catalogue de moteurs APK

**Famille de moteurs Android — local.** Nombreuses langues, dont français ; dépend du modèle.

Distribution : Index officiel des APK de moteur par modèle. Taille : Très variable : moteur et modèle peuvent être réunis dans un gros APK.

Installation : Choisir une langue et un modèle, vérifier Android TTS Engine APK et l’architecture du téléphone. Installer l’APK, puis sélectionner ce moteur dans le lecteur.

Sources : [Page principale](https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine.html) · [Complément 1](https://k2-fsa.github.io/sherpa/onnx/tts/index.html).


### 5. eSpeak NG / eSpeak Android — Reece Dunn

**Moteur Android — local.** Français et nombreuses autres langues eSpeak.

Distribution : Distribution F-Droid ; moteur à formants. Taille : Taille variable selon version ; synthèse et données compactes.

Installation : Installer la version compatible avec l’appareil, choisir le français dans le moteur puis sélectionner sa voix dans ce lecteur.

Paquet identifié : `com.reecedunn.espeak`.

Sources : [Page principale](https://f-droid.org/packages/com.reecedunn.espeak/) · [Complément 1](https://github.com/espeak-ng/espeak-ng).


### 6. eSpeak NG Text-to-Speech — RedZoc

**Moteur Android — local ; autre distribution eSpeak NG.** Français et plus de cent langues annoncées.

Distribution : Port Android publié par RedZoc sur Google Play. Taille : Taille fournie par Google Play ; pas de taille fixe retenue.

Installation : Installer le port RedZoc, choisir sa langue et sa voix, puis sélectionner ce moteur dans ce lecteur.

Paquet identifié : `com.redzoc.ramees.tts.espeak`.

Sources : [Page principale](https://play.google.com/store/apps/details?id=com.redzoc.ramees.tts.espeak).


### 7. RHVoice

**Moteur Android — local.** Anglais, espagnol, portugais du Brésil, russe et autres langues ; français absent de la liste officielle consultée.

Distribution : Application Android officielle et packs de voix. Taille : Variable selon les voix téléchargées.

Installation : Suivre le guide Android officiel, installer les données d’une langue disponible, puis choisir cette voix dans le lecteur.

Paquet identifié : `com.github.olga_yakovleva.rhvoice.android`.

Sources : [Page principale](https://rhvoice.org/languages/) · [Complément 1](https://rhvoice.org/installation/) · [Complément 2](https://play.google.com/store/apps/details?id=com.github.olga_yakovleva.rhvoice.android) · [Complément 3](https://github.com/RHVoice/RHVoice).


### 8. Acapela TTS Voices

**Moteur Android — voix locales payantes.** Français et autres langues du catalogue Android.

Distribution : Application gratuite à télécharger ; achat de voix pour la synthèse locale. Taille : Application et données variables selon voix.

Installation : Installer l’application, écouter les exemples, acquérir et télécharger une voix si souhaité, puis choisir Acapela dans ce lecteur.

Paquet identifié : `com.acapelagroup.android.tts`.

Sources : [Page principale](https://play.google.com/store/apps/details?id=com.acapelagroup.android.tts) · [Complément 1](https://www.acapela-group.com/acapela-tts-voices-googleplay-instructions/).


### 9. Vocalizer TTS — Code Factory

**Moteur Android — voix locales payantes.** Français France/Canada et autres langues du catalogue.

Distribution : Google Play ; essai et achats de voix selon offre affichée. Taille : Application et packs variables selon voix.

Installation : Installer le moteur, télécharger une voix française et vérifier l’essai proposé avant achat ; choisir ensuite cette voix dans le lecteur.

Paquet identifié : `es.codefactory.vocalizertts`.

Sources : [Page principale](https://play.google.com/store/apps/details?id=es.codefactory.vocalizertts) · [Complément 1](https://codefactoryglobal.com/usecases/apps/vocalizer-expressive-for-android/).


### 10. Eloquence — Code Factory

**Moteur Android — abonnement et connexion nécessaires.** Français France/Canada ; anglais, espagnol, allemand, finnois, italien, portugais du Brésil.

Distribution : Nouvelle édition Android 64 bits annoncée en décembre 2025. Taille : Application et données variables selon la distribution.

Installation : Consulter les conditions et l’essai sur la fiche officielle. Une installation ne garantit pas que ses voix seront exposées comme locales au lecteur.

Paquet identifié : `com.codefactoryglobal.eloquencetts`.

Sources : [Page principale](https://play.google.com/store/apps/details?id=com.codefactoryglobal.eloquencetts) · [Complément 1](https://codefactoryglobal.com/eloquence-for-android-is-coming-back-2/).


### 11. NekoSpeak

**Moteur Android — modèles locaux.** Français selon Piper/Kokoro ; langues différentes pour KittenTTS et Pocket TTS.

Distribution : APK publiés par l’auteur ; projet communautaire. Taille : APK et modèles variables ; lire les tailles de chaque Release.

Installation : Installer l’APK de l’architecture correcte, télécharger une voix française compatible avec la famille choisie, puis sélectionner le moteur et la voix dans le lecteur.

Sources : [Page principale](https://github.com/siva-sub/NekoSpeak).


### 12. HayaiTTS

**Moteur Android — modèles locaux.** Français selon modèles ; catalogue multilingue.

Distribution : APK dans les Releases ; service TextToSpeech Android documenté. Taille : Variable selon APK et modèles choisis.

Installation : Installer l’APK, télécharger un modèle français dans le catalogue du moteur et vérifier la voix proposée au lecteur.

Sources : [Page principale](https://github.com/HayaiApp/HayaiTTS).


### 13. Kokoly

**Moteur Android — Kokoro local.** Français ; anglais, allemand, espagnol, italien, portugais du Brésil et hindi annoncés.

Distribution : APK de l’auteur ; Android 8 ou ultérieur et ARM64 selon le projet. Taille : Environ 450 Mio de modèles annoncés par l’auteur ; hors taille exacte de l’APK.

Installation : Installer l’APK ARM64 compatible, télécharger les données dans Kokoly, puis choisir sa voix française dans ce lecteur.

Sources : [Page principale](https://github.com/loom7/kokoly).


### 14. Supertonic for Android — DevGitPit

**Moteur Android — local.** Selon modèle et édition installés ; français à confirmer dans la liste effective des voix.

Distribution : Moteur système distribué via F-Droid et Google Play. Taille : Variable selon version et modèles.

Installation : Suivre un lien de distribution du dépôt, télécharger les données nécessaires, puis vérifier qu’une voix française est effectivement exposée avant de la sélectionner.

Paquet identifié : `com.brahmadeo.supertonic.tts`.

Sources : [Page principale](https://github.com/DevGitPit/supertonic-android) · [Complément 1](https://play.google.com/store/apps/details?id=com.brahmadeo.supertonic.tts).


### 15. VoxSherpa TTS

**Moteur Android — modèles locaux.** Français annoncé ; langues selon modèles Kokoro ou Piper/VITS.

Distribution : APK de l’auteur ; Android 11 ou ultérieur, ARM64 selon le projet. Taille : Variable selon modèles ; stockage supplémentaire nécessaire.

Installation : Installer le moteur compatible, télécharger un modèle et une voix française dans son gestionnaire, puis sélectionner cette voix dans le lecteur.

Sources : [Page principale](https://github.com/CodeBySonu95/VoxSherpa-TTS).


### 16. Marmalade TTS

**Moteur Android — local, avec fournisseurs distants facultatifs.** Anglais initial ; autres langues selon modèles Kokoro ou fournisseur, français à vérifier.

Distribution : Projet avec distribution F-Droid et Releases documentées. Taille : Variable selon modèles et édition.

Installation : Installer une édition proposée par l’auteur, choisir un moteur local et ses données, puis vérifier les voix déclarées au lecteur. Un fournisseur distant demande sa propre configuration.

Sources : [Page principale](https://github.com/maxwhipw/marmalade-tts-android).


### 17. Eloqium TTS

**Moteur Android indépendant — local.** Français France/Canada ; anglais US/GB, espagnol Espagne/Amérique latine, allemand, italien.

Distribution : Projet communautaire OpenEVV ; APK de l’auteur. Taille : Taille de l’APK variable ; consulter Releases.

Installation : Installer l’APK publié par le projet, choisir le français et le profil souhaité, puis sélectionner Eloqium dans le lecteur.

Sources : [Page principale](https://github.com/memon-ismail/Eloqium-TTS) · [Complément 1](https://github.com/memon-ismail/Eloqium-TTS/blob/main/PROVENANCE.md).


### 18. CloneTTS

**Moteur Android — local, expérimental.** Usage chinois/anglais documenté ; français non confirmé.

Distribution : APK de l’auteur ; bêta Google Play avec accès restreint. Taille : APK et données extraites variables selon version.

Installation : Utiliser la distribution de l’auteur, laisser terminer l’installation des modèles puis tester une voix dans le moteur. Le français doit être vérifié avant utilisation avec des sous-titres français.

Sources : [Page principale](https://github.com/sipeter/CloneTTS).


### 19. Pocket TTS Android Engine — The-unknown-Shadowman

**Moteur Android communautaire — local, expérimental.** Packs anglais, allemand, italien, portugais et espagnol ; français absent des packs prêts à l’emploi listés.

Distribution : APK ARM64 et packs ONNX séparés dans les Releases. Taille : APK sans poids ; taille variable selon pack FP32/INT8.

Installation : Installer l’APK, télécharger un pack de langue depuis la même Release, l’importer dans le moteur puis choisir une voix. Ne pas installer le ZIP comme une application.

Paquet identifié : `org.pockettts.android.engine`.

Sources : [Page principale](https://github.com/The-unknown-Shadowman/PocketTTS-Android-Engine).


### 20. Piper Android — piper-tts-apps

**Projet de moteur Android — local.** Langues Piper, dont français selon voix.

Distribution : Service TTS et canal Releases documentés ; binaire actuel non vérifié. Taille : Non confirmée ; APK et voix séparées selon distribution.

Installation : Consulter les Releases du projet. En l’absence d’APK publié pour l’appareil, une compilation est nécessaire ; une archive de sources ne s’installe pas directement.

Sources : [Page principale](https://github.com/piper-tts-apps/piper-app-android/).


### 21. Maise

**Projet de moteur Android — Kokoro local.** Français Siwis annoncé ; autres voix Kokoro selon données.

Distribution : Sources avec service Android TTS ; distribution APK prête à l’emploi non confirmée. Taille : Non confirmée ; modèle et données supplémentaires.

Installation : Consulter le dépôt et ses éventuelles Releases ; sans APK officiel, compiler le service avant de pouvoir le sélectionner dans Android.

Sources : [Page principale](https://github.com/Mobile-Artificial-Intelligence/maise).


### 22. Kokoro Offline TTS — expérimentation QNN

**Moteur Android expérimental — local.** Anglais ; français non proposé dans cette édition.

Distribution : APK et sources ; cible NPU précise testée par l’auteur. Taille : Édition avec contextes QNN volumineuse ; consulter le fichier exact.

Installation : Lire les contraintes matérielles de la Release avant installation. Tester le chemin effectivement utilisé ; ce moteur anglais ne remplace pas une voix française.

Sources : [Page principale](https://github.com/cedgeremek/kokoro-offline-tts-android).


### 23. Supertonic TTS — chukfinley

**Prototype de moteur Android — sources.** Selon modèle Supertonic ; français non confirmé dans cette intégration.

Distribution : Projet source ; APK actuel non confirmé. Taille : Dépend de la compilation et des modèles.

Installation : Examiner les instructions du projet et les fichiers de Release ; compiler si aucun APK compatible n’est distribué.

Sources : [Page principale](https://github.com/chukfinley/supertonic-tts).


### 24. ChineseTtsTflite

**Projet Android spécialisé — local.** Chinois ; français non annoncé.

Distribution : Sources et anciennes Releases ; compatibilité récente non confirmée. Taille : Variable selon modèle et compilation.

Installation : Vérifier l’existence d’un APK moteur compatible dans les Releases. Ce projet ne fournit pas de solution française confirmée.

Sources : [Page principale](https://github.com/benjaminwan/ChineseTtsTflite).


### 25. Hear2Read NG

**Moteur Android — local après téléchargement.** Assamais, gujarati, hindi, kannada, malayalam, marathi, népalais, odia, pendjabi, cinghalais, tamoul, télougou ; pas de français.

Distribution : Nouvelle génération publiée sur Google Play ; Android 12 ou ultérieur selon la fiche. Taille : Application et modèles neuronaux par langue.

Installation : Installer Hear2Read NG, télécharger la voix d’une langue proposée, puis sélectionner le moteur dans Android et dans le lecteur.

Paquet identifié : `org.hear2read.h2rng`.

Sources : [Page principale](https://play.google.com/store/apps/details?id=org.hear2read.h2rng) · [Complément 1](https://hear2read.org/ng-text-to-speech-demo) · [Complément 2](https://hear2read.org/solution-android).


### 26. AhoTTS — Aholab UPV/EHU

**Moteur Android — local après téléchargement.** Basque, espagnol, catalan et galicien ; pas de français.

Distribution : Moteur système officiel du groupe Aholab. Taille : Application et voix téléchargées séparément ; tailles variables.

Installation : Installer AhoTTS, télécharger une voix dans ses réglages, puis choisir cette langue dans le lecteur.

Paquet identifié : `com.aholab.ahottsandroid`.

Sources : [Page principale](https://play.google.com/store/apps/details?id=com.aholab.ahottsandroid) · [Complément 1](https://aholab.ehu.eus/aholab/for-end-users/).


### 27. Símarómur

**Moteur Android — local.** Islandais ; pas de français.

Distribution : Application Android présentée sur le site officiel. Taille : Taille dépendant de la distribution ; non figée ici.

Installation : Suivre le lien Android du site, installer l’application et sélectionner Símarómur comme moteur pour l’islandais.

Paquet identifié : `com.grammatek.simaromur`.

Sources : [Page principale](https://simaromur.is/) · [Complément 1](https://play.google.com/store/apps/details?id=com.grammatek.simaromur) · [Complément 2](https://github.com/grammatek/simaromur).


### 28. Techiaith-TTS

**Moteur Android — local.** Gallois et anglais ; pas de français.

Distribution : Application système publiée par Techiaith. Taille : Taille indiquée par Google Play.

Installation : Installer l’application, choisir l’une des voix proposées et sélectionner Techiaith-TTS dans le lecteur pour une langue prise en charge.

Paquet identifié : `cymru.techiaith.tts.android`.

Sources : [Page principale](https://play.google.com/store/apps/details?id=cymru.techiaith.tts.android).


### 29. TartuNLP Neurokõne

**Application et moteur TTS Android spécialisé.** Estonien ; pas de français.

Distribution : Le projet documente un APK de Release et un service système Android. Taille : Variable selon APK et modèles TensorFlow Lite.

Installation : Installer l’APK, ouvrir Système voix / Süsteemi hääl, choisir TartuNLP Neurokõne comme moteur et sa voix estonienne.

Sources : [Page principale](https://github.com/TartuNLP/neurokone_app).


### 30. Samsung Text-to-Speech

**Moteur constructeur — appareils Samsung.** Français et autres langues selon appareil et packs Samsung.

Distribution : Distribution Galaxy Store liée aux appareils pris en charge. Taille : Application et packs variables selon appareil.

Installation : Sur un appareil Samsung pris en charge, utiliser les réglages du moteur et Galaxy Store. Aucune installation sur OnePlus n’est validée.

Paquet identifié : `com.samsung.SMT`.

Sources : [Page principale](https://galaxystore.samsung.com/detail/com.samsung.SMT).


### 31. CereProc Scottish Education

**Moteur Android — distribution éducative particulière.** Voix écossaises du programme ; pas de français annoncé.

Distribution : Guide officiel d’installation du programme écossais. Taille : Variable selon application et voix.

Installation : Vérifier les conditions d’éligibilité du programme, suivre son guide Android, puis sélectionner le moteur et les voix disponibles.

Paquet identifié : `com.cereproc.cse`.

Sources : [Page principale](https://www.thescottishvoice.org.uk/download/android/) · [Complément 1](https://play.google.com/store/apps/details?id=com.cereproc.cse).


### 32. TTSLexx

**Passerelle Android — dictionnaire pour le moteur Google.** Langues du moteur Google, dont français selon voix.

Distribution : Application système complémentaire ; moteur Google nécessaire. Taille : Application légère, plus les données du moteur Google ; taille exacte non retenue.

Installation : Installer les données Google nécessaires et TTSLexx, configurer son dictionnaire puis tester la voix exposée. Ne pas compter TTSLexx comme un modèle autonome.

Paquet identifié : `sia.netttsengine.ttslexx`.

Sources : [Page principale](https://sites.google.com/view/netttsengine/main/ttslexx) · [Complément 1](https://play.google.com/store/apps/details?id=sia.netttsengine.ttslexx).


### 33. TTS Server Android — jing332

**Passerelle Android — fournisseurs locaux ou réseau.** Selon fournisseur ; français possible avec un fournisseur compatible.

Distribution : Application Android avec service TTS et fournisseurs configurables. Taille : Variable selon APK ; données ou abonnements du fournisseur en supplément.

Installation : Installer et configurer explicitement un fournisseur. Vérifier si le texte quitte l’appareil et si la voix est déclarée locale avant utilisation avec ce lecteur.

Sources : [Page principale](https://github.com/jing332/tts-server-android).


### 34. TalkifyTTS

**Passerelle / moteur hybride Android.** Français selon fournisseur distant ; ZipVoice local orienté chinois/anglais.

Distribution : Service système, fournisseurs distants et mode local documentés. Taille : APK et modèle local variables ; éventuels services payants.

Installation : Choisir explicitement le mode local ou un fournisseur distant dans TalkifyTTS, terminer sa configuration puis vérifier les voix exposées au lecteur.

Sources : [Page principale](https://github.com/LonePheasantWarrior/TalkifyTTS).


### 35. CerePlay / CereProc — édition générale

**Application vocale — fin de maintenance annoncée.** Selon catalogue CereProc ; intégration TTS système générale à confirmer.

Distribution : La fiche officielle indique l’arrêt des mises à jour ; utilisateurs existants encore pris en charge. Taille : Variable selon application et voix.

Installation : Lire l’avis de fin de maintenance sur la fiche officielle et vérifier l’intégration système avant tout achat de voix.

Paquet identifié : `com.cereproc.cerevoicesapp`.

Sources : [Page principale](https://play.google.com/store/apps/details?id=com.cereproc.cerevoicesapp) · [Complément 1](https://app.cereproc.com/cereplay/).


### 36. AlfaNum — synthèse Android

**Offre Android spécialisée — distribution à confirmer.** Serbe, croate et monténégrin ; pas de français annoncé.

Distribution : L’éditeur annonce Android ; APK moteur grand public actuel non confirmé. Taille : Selon offre et données ; non vérifié.

Installation : Demander à l’éditeur quelle offre fournit effectivement un service Android TextToSpeech et un APK compatible avant de prévoir son usage dans le lecteur.

Sources : [Page principale](https://www.alfanum.co.rs/en/an-sintetizator/).


### 37. Flite TTS Engine for Android

**Moteur historique / sources.** Principalement langues des voix Flite ; français non confirmé.

Distribution : Ancien projet ; compatibilité Android récent et ARM64 non confirmée. Taille : Variable selon compilation et voix.

Installation : Consulter l’état du projet et ses distributions. Aucune installation prête à l’emploi sur OnePlus 15 n’est confirmée.

Sources : [Page principale](https://github.com/happyalu/Flite-TTS-Engine-for-Android).


### 38. LIEPA / Laba Diena TTS

**Moteur spécialisé historique / sources.** Lituanien ; pas de français.

Distribution : Sources multiplateformes et ancien projet Android. Taille : Variable selon compilation et voix.

Installation : Vérifier l’état des distributions et les architectures supportées. Ne pas confondre les sources Android avec un APK actuel.

Sources : [Page principale](https://github.com/aleksas/laba-diena-tts) · [Complément 1](https://sourceforge.net/projects/liepa-tts-andoid/).


### 39. SVOX Pico — AOSP

**Moteur historique — sources Android.** Langues et données dépendant de l’intégration historique.

Distribution : Sources AOSP ; aucun APK universel moderne confirmé ici. Taille : Dépend de la compilation et des données.

Installation : Réservé à l’étude ou à une intégration compilée ; aucune installation directe actuelle n’est confirmée pour ce lecteur.

Sources : [Page principale](https://android.googlesource.com/platform/external/svox/).


### 40. Supertonic Android — ouor : démonstration

**Application de démonstration — moteur système non établi.** Selon modèle utilisé par la démonstration.

Distribution : Projet de saisie, génération et lecture audio. Taille : Dépend du modèle et de la compilation.

Installation : Utiliser la démonstration pour évaluer le modèle ; ne pas promettre son apparition dans le sélecteur des moteurs de ce lecteur.

Sources : [Page principale](https://github.com/ouor/supertonic-android).


### 41. Piper / Kokoro / Coqui / Supertonic : fichiers modèles

**Modèles — pas des applications Android.** Dépend du modèle exact et de ses voix.

Distribution : Poids à télécharger via un moteur compatible ou leur projet d’origine. Taille : De dizaines de Mo à plus d’un Go selon modèle ; taille exacte par fichier.

Installation : Choisir d’abord un moteur Android compatible, puis suivre son gestionnaire de modèles ou son format d’import. Miro est notamment une voix Piper, pas un moteur distinct.

Sources : [Page principale](https://k2-fsa.github.io/sherpa/onnx/tts/index.html).


## Noms recherchés qui ne deviennent pas des recommandations d’installation

**IVONA HQ, SVOX Classic, anciens paquets de voix CereProc, MultiTTS et SmartVoice** ne sont pas présentés ici comme des téléchargements actuels garantis : cet audit n’a pas établi, pour chaque produit, un canal de première main actuel et une compatibilité Android récente. Cela ne prouve ni leur inexistence ni l’arrêt de toutes leurs éditions. Les miroirs d’APK ne remplacent pas cette vérification. SVOX Pico reste documenté séparément comme code historique AOSP.

Les offres **Azure Speech/Microsoft Edge, Amazon Polly, Google Cloud TTS, OpenAI TTS, ElevenLabs, Yandex SpeechKit ou iFlytek** peuvent être utilisées par certains logiciels et passerelles. Le nom d’un service cloud ou la disponibilité d’un SDK Android ne prouve pas qu’il existe un moteur système Android directement installable. Deux exemples documentés : le [SDK Android Yandex historique, explicitement déprécié](https://github.com/yandexmobile/deprecated-yandex-speechkit-android) et la [documentation du SDK Android iFlytek](https://static.xfyun.cn/doc/tts/online_tts/SparkChain-Android-SDK.html). Le catalogue n’ajoute ni API distante, ni clé, ni abonnement au lecteur.

Les constructeurs peuvent distribuer d’autres moteurs ou voix dans leurs versions régionales d’Android. Un paquet extrait d’un appareil Huawei, Xiaomi ou d’une autre marque ne constitue pas, à lui seul, une distribution officielle utilisable sur OnePlus. Les variantes non vérifiées restent en dehors des notices d’installation.

## Entretien des données

Le schéma conserve les sept chaînes lues par l’interface : `name`, `kind`, `languages`, `status`, `size`, `url`, `notes`. Les métadonnées ajoutées sont `category`, `distribution`, `installation`, `verified`, `sources` et, lorsqu’il est établi, `packageName`. La source principale reste une URL HTTPS ; les sources complémentaires sont un tableau d’URL HTTPS.

Les catégories stables sont `engine`, `engine_variant`, `engine_family`, `gateway`, `oem`, `source`, `historical`, `discontinued`, `vendor_offer`, `demo`, `model`. Les catégories décrivent la nature de la notice, pas un résultat d’essai sur appareil. `distribution` et `installation` sont des textes explicatifs, pas des enums. `verificationLevel: documentation` au niveau racine explicite la portée de l’audit.

À chaque révision, vérifier les liens de l’auteur, le service Android réellement exposé, les langues des modèles distribués, les restrictions constructeur, l’état de maintenance et les téléchargements effectifs. Un essai réel pourra ensuite ajouter séparément le modèle exact, sa version, l’architecture, Android, le téléphone, le débit, les latences et les erreurs observées. Les résultats de ce lecteur et ceux du moteur externe doivent rester identifiables.
