# Validation et preuves

[Accueil](README.md) · [Documentation](docs/README.md) · [Contribution](CONTRIBUTING.md) · [Notes 1.1.1](docs/releases/1.1.1.md) · [Historique détaillé antérieur](docs/archives/verification-avant-1.0.0.md)

## État de la 1.1.1

Les sources déclarent **1.1.1 / code 25**, application `fr.soustitresvoix.player.translation`. La validation exige la campagne GitHub Actions sur l’APK release de test avant fusion, puis provenance, reconstruction, signature durable et contrôle public. Les résultats propres à cette distribution — commit, PR, runs, effectifs exécutés, empreintes et assets — sont à consulter dans le corps de la [release GitHub v1.1.1](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v1.1.1). Les [notes versionnées](docs/releases/1.1.1.md#validation-et-publication) distinguent les critères de livraison des contrôles locaux de préparation.

Les contrôles locaux de préparation ne remplacent pas cette campagne. Une compilation de l’APK de tests ne prouve pas son exécution. Une inférence hôte ne démontre ni le calcul d’un GPU/NPU physique, ni la vitesse sur téléphone, ni la qualité linguistique. Une capture ne valide que l’état et la portion visibles ; TalkBack demande un essai réel distinct.

## Environnement de compilation

| Composant | Configuration du dépôt |
| --- | --- |
| Gradle / plugin Android | Gradle 8.13 utilisé par la CI ; AGP 8.11.1 |
| Kotlin | 2.2.21 |
| Java de production | Toolchain 17 |
| JVM des tests Android/Robolectric | Java 21 ; distincte du niveau de compilation de l’application |
| Android | minSdk 26, compileSdk/targetSdk 36, Build Tools 36.0.0 |
| Natif | NDK 27.2.12479018 ; CMake 3.22.1 ; ARM64 et x86_64 |
| Démonstration | FFmpeg hôte, génération déterministe depuis `examples/demonstration.srt` |
| HTP | Assets de compilation vérifiés avant `preBuild`, préparés avec `tools/prepare-hexagon.py` et le SDK épinglé |

Les versions effectives restent celles des fichiers Gradle et workflows du commit. Ne pas installer FFmpeg ni le SDK Hexagon sur le téléphone. Les modèles miniatures des tests de traduction ne sont pas des modèles de production ; les fixtures de traduction réservées à l’instrumentation restent dans l’APK de tests.

## Contrôles locaux

Commencer par les contrôles ciblés pertinents puis effectuer la validation requise sur l’état final. Les commandes suivantes décrivent les tâches ; leur présence ici ne constitue pas un résultat :

```sh
gradle --no-daemon :core:test :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
gradle --no-daemon :app:testDebugUnitTest
gradle --no-daemon -PverifyRelease=true :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease :app:assembleReleaseAndroidTest
```

Pour une campagne fraîche, nettoyer les sorties de tests concernées ou utiliser les options de rejeu adaptées, puis contrôler les timestamps et empreintes des nouveaux XML. Un résultat ancien, une tâche `UP-TO-DATE` ou `FROM-CACHE` ne devient pas une nouvelle exécution. Ne pas compter deux fois une méthode rejouée dans une reprise ciblée ; garder les preuves initiales et finales séparées.

Les suites des outils s’exécutent depuis la racine :

```sh
python3 tools/test-pr-validation.py
python3 tools/test-publish-verified-release.py
python3 tools/test_sync_releases.py
python3 tools/test-documentation-cleanup.py
python3 tools/test-native-translation-results.py
python3 tools/test-hexagon-build.py
```

Pour le natif, utiliser `python3 tools/run-native-translation-tests.py --help`, puis les paramètres requis par le banc et son environnement. Ce banc effectue de vraies inférences hôte sur modèles miniatures ; conserver son résumé, ses logs et les empreintes des sources. Il ne certifie pas un backend physique. Le [README natif](app/src/main/cpp/README.md) décrit la chaîne et ses limites.

## Tests Android avant chaque fusion

Le workflow [Android — APK et tests avant fusion](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/workflows/android.yml) construit les APK de test et exécute JVM, lint et instrumentation avant toute fusion. Le changement final doit être testé sur son propre commit de proposition, avec inventaire source et XML frais rapprochés. L’inventaire source 1.1.1 attend **205 méthodes noyau, 702 JVM application et 122 Android**, soit **1 029 méthodes distinctes**. Cet effectif est une attente de couverture, pas une réussite d’exécution ; seuls les XML frais du passage final établissent les méthodes exécutées. Ne pas figer le nombre d’une ancienne version comme preuve actuelle.

Les tests instrumentés doivent s’exécuter sur l’APK **release de test** sous Android 17/API 37, conformément au workflow. Pour une exécution locale sur appareil/émulateur disponible :

```sh
gradle --no-daemon -PverifyRelease=true :app:connectedReleaseAndroidTest
```

Conserver les identités de chaque méthode et refuser les méthodes manquantes, inattendues, dupliquées, ignorées ou en échec. Archiver JUnit, lint, logs, captures, identité Android et SHA-256 des deux APK. Un APK CI signé éphémèrement sert à cette validation ; il n’est pas la distribution durable.

## Régressions prioritaires

| Domaine | Propriétés à vérifier |
| --- | --- |
| Narration | Pause utilisateur, déplacement, callback périmé, fin de vidéo et reprise ; aucun panneau/répétition/chevauchement perdu |
| Traduction progressive | Préfixes contigus validés ; attente au premier texte absent ; autorisation de reprise invalidée par les actions concurrentes |
| Contexte Index | Sources deux avant/deux après seulement ; suppression entière des voisins ; reprise identique ; refus des familles incompatibles |
| Cache exact | Modèle/configuration/langues et contexte passé/futur ; occurrence conservée sur hit ; corruption/limites/écriture atomique |
| Cache natif | Préfixe valide uniquement ; `WINDOW_EXPIRED` conservé ; bornes candidates diagnostiquées ; comparaison au recalcul frais |
| Mesures | Chargements et réponses sans double comptage ; tentatives distinctes ; horloge monotone ; tokens/cache/NPU/GPU/RSS/buffers séparés |
| Explorer | SAF sans autorisation générale, accès refusé/révoqué, SD, Android 26–29/30+, dossiers privés exclus, annulation et dépassement de borne |
| Réseau | URI opaques, secrets chiffrés, révision de connexion, certificats/pin, redirections, positions Range et interruptions ; index complet distinct des flux |
| Démarrage réseau | WebVTT HLS/DASH fini depuis le premier segment validé, toutes les occurrences source, attente à la limite vérifiée, inventaire complet au dernier segment et pause en cas de format hors périmètre ; occurrences progressives brèves, répétées et tardives, y compris après la fin de l’image ; Pause utilisateur prioritaire ; index complet pour les parcours avancés et la reprise exacte d’une réplique interrompue |
| Interface | Quatre onglets sans effets de lecture, grande police/défilement, bornes Minimum/Maximum et tickets de dialogues périmés |
| Modèle/mémoire | Aucun seuil sur la seule taille GGUF ; gardes mesurées, annulation/libération acquittée, erreurs de chaque tentative conservées |
| Audio/image | Commandes refusées sans mutation, séparation voix/film, PCM compatible, cadre/HDR et durée de vie des panneaux |

Les tests utilisant HTTP/WebDAV de boucle locale, les fixtures SFTP/FTP/SMB et les serveurs de production sont des preuves distinctes. Ne pas annoncer la compatibilité réelle d’un serveur sans l’essai correspondant. Un essai de traduction doit aussi conserver sa précision, son SHA de modèle et son corpus ; l’exactitude de petits calculs ne certifie pas la sémantique d’un film.

## Fusion, reconstruction et signature

Après réussite de la campagne du changement final, intégrer dans `main` par **commit de merge à deux parents**, sans contourner une protection. Vérifier l’arbre et les parents de la fusion par rapport à la fusion provisoire testée. Le workflow `release-build.yml` reconstruit ensuite sans rejouer les suites déjà réussies sur le même changement. Comparer le contenu applicatif de l’APK reconstruit à celui de l’APK testé, hors seules métadonnées de signature autorisées. Une différence de provenance ou de contenu interdit de réutiliser ces résultats.

La distribution doit garder le certificat durable de la série Traduction. Empreinte attendue :

```text
304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d
```

`SUBTITRES_SIGNING_PROPERTIES` désigne un fichier **hors dépôt** contenant `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. Le PKCS12 reste lui aussi hors dépôt. Aucun secret n’est versé aux sources, notes, logs ou artefacts publics. Sans cette configuration, un APK release peut être non signé ; ne pas le présenter comme installable.

Contrôler l’APK exact avec les outils Android :

```sh
apksigner verify --verbose --print-certs /chemin/externe/application.apk
zipalign -c -P 16 -v 4 /chemin/externe/application.apk
sha256sum /chemin/externe/application.apk
```

Contrôler aussi manifeste compilé, `debuggable=false`, version 1.1.1/code 25/applicationId exact, absence du service vocal de fixture, bibliothèques et notices attendues. Ne jamais recréer une clé pour rendre une mise à jour artificiellement installable.

## Publication et contrôle indépendant

Le [parcours de contribution](CONTRIBUTING.md#publier-un-apk-vérifié) précise le transport de l’APK signé déjà vérifié. Publier seulement la source validée de `main`, un tag réel `v1.1.1`, l’APK et `SHA256SUMS`. La 1.1.1 est destinée à une release stable, sans indicateur prerelease ; cela ne retire pas les limites expérimentales de certains backends.

Après publication, télécharger les fichiers publics et vérifier de nouveau taille, SHA-256, manifeste et certificat. Le corps de la release doit fournir PR, commits, runs, effectifs réellement exécutés, arbre/parents, empreintes et identifiants d’assets. L’existence d’un brouillon, d’un tag ou d’un lien ne suffit pas à annoncer la livraison.

## Instantanés techniques de l’émulateur

Les relevés techniques de l’émulateur décrivent le contexte des essais synthétiques : processus, mémoire, trames, version installée et état thermique lorsque ces services sont disponibles. Un relevé manquant ou un processus arrêté doit rester signalé, sans inventer une valeur. Ces instantanés ne mesurent ni l’autonomie du téléphone, ni la voix entendue, ni une exécution GPU/NPU physique. La [description historique du recueil](docs/archives/verification-avant-1.0.0.md#instantanés-techniques-de-lémulateur) conserve les modalités de la campagne correspondante.

## Preuves historiques

La 0.5.15-loading a été publiée au commit `bc678e26aef233b6ee5f0e366bd717b0ba0027ed`, après la [PR #31](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/pull/31) et le [run 42406642590](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/42406642590) : **941 méthodes**, dont 196 noyau, 629 JVM application et 116 Android API 37. Ces résultats ne valident pas le changement 1.0.0.

Les résultats antérieurs et leurs échecs intermédiaires sont conservés dans l’[historique détaillé de validation](docs/archives/verification-avant-1.0.0.md), les [notes de chaque version](docs/README.md#versions-et-archives) et les fichiers de `docs/verification`. Les mentions de candidate ou de stable de ces archives décrivent leur époque.
