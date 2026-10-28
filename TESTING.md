# Vérifications de régression

[Accueil](README.md) · [Documentation](docs/README.md) · [Essais sur téléphone](docs/TESTS_TELEPHONE.md) · [Contribution](CONTRIBUTING.md)

## Candidate 0.5.7-directcl — validation à établir

Les sources préparent **0.5.7-directcl / code 14**, avec la même identité Android et le même certificat durable. Le [guide](docs/OPENCL_DIRECT_057.md) et les [notes](docs/releases/0.5.7-directcl.md) décrivent le passage par les fonctions du pilote OpenCL sans exiger son extension ICD. Le rapport reçu de la 0.5.6 établit une plateforme directement visible et un échec ICD ; il ne prouve pas une exécution GPU.

Les nouvelles régressions doivent vérifier le véritable parcours de liaison directe avec une bibliothèque synthétique ne fournissant pas l’extension ICD, les fonctions requises manquantes, les fonctions optionnelles, les appels transmis, la concurrence et la conservation des erreurs. Les sources de calcul épinglées, les poids, la quantification, le contexte, les occurrences, les minutages et les protections de Pause, de déplacement et de callbacks périmés restent conservés. Le mode CPU ne doit jamais charger ni sonder le pilote ; l’accès direct et le calcul GPU réel restent des observations distinctes.

L’inventaire final attendu contient **178 tests noyau, 420 tests JVM application et 110 tests Android**, soit **708 méthodes**. La campagne exacte doit construire et tester **l’APK release sous Android 17 / API 37**, avec compilation et ciblage API 36 conservés. Les outils 22.0 et le garde de cible AVD 37.0 introduits pour la 0.5.6 restent requis. Les succès de la 0.5.6 ne valident pas les changements de cette candidate.

Avant fusion, les effectifs et identités exactes doivent être retrouvés dans les XML sans échec, erreur, test ignoré, manquant ou dupliqué. Après fusion à deux parents, une reconstruction doit vérifier la provenance et le contenu applicatif identique hors signature, **sans rejouer les suites réussies**. La distribution exige la signature durable puis un contrôle indépendant des fichiers publics. Aucun résultat de compilation, de test, de GPU du OnePlus, de qualité linguistique, d’autonomie ou de TalkBack réel n’est annoncé avant son exécution correspondante. La **0.5.2 reste la dernière stable**.

### Outillage local 0.5.7

Les **59 tests Python hors réseau** ont réussi : **20** de publication, **23** de synchronisation de l’historique, **10** de nettoyage documentaire et **6** de provenance après fusion. Les outils locaux de préparation ont aussi passé **8 vérifications synthétiques** : refus d’une provenance ou d’un checksum modifié, d’un fichier public supplémentaire, de propriétés ambiguës, formatage des notes et restauration exacte des parties de transport. Aucun fichier de clé, artefact réel, signature ou téléversement n’a été utilisé pour ces huit essais. Ces résultats sont distincts des suites applicatives et de l’instrumentation Android attendues.

### Vérifications locales de la candidate 0.5.7

La compilation Kotlin principale, JVM et AndroidTest et **44 méthodes JVM ciblées** ont réussi en **4 min 43 s** : diagnostic **4**, runtime **9**, moteur **23** et benchmark **8**, sans échec, erreur ni test ignoré. Ce passage ne remplace pas la campagne complète de 708 méthodes attendues sur la proposition finale.

Le test natif de liaison directe a réellement compilé et chargé des bibliothèques synthétiques sans interface ICD : cas complet, fonctions facultatives absentes et fonction obligatoire absente. Il a vérifié les appels transmis, handles opaques, callbacks, tailles 64 bits, publication concurrente, absence de table partielle et isolement ELF. Les premiers problèmes de compilation du générateur et de ses fixtures ont été corrigés avant le passage final réussi ; ils ne sont pas présentés comme des tests Android réussis. Le même lanceur sera exécuté en CI à partir des en-têtes épinglés du véritable build ARM64.

La construction native hôte a compilé les cibles existantes, puis les essais de traduction, de sonde et de diagnostic ont réussi avec des modèles miniatures synthétiques. La vérification sensible au contexte conserve la comparaison des logits avec et sans réutilisation de préfixe. Aucun de ces essais ne constitue une mesure du pilote ou du GPU physique du OnePlus.

Les tâches requises `:core:test :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` ont ensuite réussi en **4 min 24 s**. Les **178 tests noyau ont réellement été exécutés** et réussis ; lint rapporte **0 erreur et 97 avertissements**. Les APK ont passé les contrôles du manifeste compilé, des alignements ELF/ZIP de 16 Kio, de l’absence de dépendance obligatoire au pilote et de l’absence de fonctions OpenCL exportées ou non résolues dans notre bibliothèque native. L’APK ARM64 contient le nouveau parcours direct ; l’exigence de fonction ICD n’y figure plus. Le modèle miniature de diagnostic principal est conservé et la fixture terminante de traduction reste uniquement dans l’APK d’instrumentation.

Le nouveau test natif de CI a également réussi localement avec les en-têtes provenant du véritable build ARM64, après vérification de leur empreinte. Cela vérifie le chemin d’intégration de la future étape CI ; cette réussite hôte ne constitue pas un test de GPU physique.

Les **59 tests Python de livraison** ont réussi (publication **20**, historique **23**, nettoyage documentaire **10**, provenance **6**), ainsi que **8 essais synthétiques des helpers privés**. Ces derniers ne lisent aucune clé et ne signent aucun APK réel pendant leurs régressions.

## Préversion publiée 0.5.6-opencl — résultats vérifiés

La [PR #22](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/pull/22) a réussi le [run 40751406769](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40751406769) : **178 tests noyau, 418 tests JVM et 110 tests Android**, soit **706 réussites sans échec, erreur, test ignoré, manquant ou dupliqué**. Les 110 tests portaient sur le véritable APK release dans un émulateur **Android 17 / API 37 / x86_64** ; les rapports ont confirmé la cible AVD android-37.0 et Command-line Tools 22.0.

La fusion a produit **1e982b907c6d322cb4066da743c9a8fefcabe7a2**, arbre **9ae53f6d0df0cb842f3368721c40078369c83caa**. Le [run après fusion 40756758677](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40756758677) a vérifié les mêmes arbre et parents que la proposition testée, puis reconstruit l’APK et retrouvé son contenu applicatif identique hors signature, **sans rejouer les suites**. Le [run de publication 40758848200](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40758848200) a réussi après signature durable et contrôles ; le téléchargement public indépendant a confirmé les octets exacts.

| Repère | Valeur vérifiée |
| --- | --- |
| APK publié | SousTitresVoix-0.5.6-opencl.apk, **53 545 770 octets** |
| Identité et version | fr.soustitresvoix.player.translation, **0.5.6-opencl / code 13** |
| SHA-256 APK | 58af4d6f4ae9efe65e21877e442275f74bdd6f1168e430dd623885a6eeb9aded |
| Certificat SHA-256 | 304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d |
| Release / asset APK | 427128248 / 686168829 |
| Publication | Préversion, dernière stable 0.5.2 conservée |

Le rapport reçu ensuite du téléphone a confirmé Android 17 / API 37 et les réponses CPU, puis identifié **OPENCL_ICD / ICD_ENTRY_POINT_MISSING**, avec une plateforme accessible directement. Il ne constitue ni un succès GPU ni un manque de mémoire démontré. La candidate suivante prépare une liaison directe. Les paragraphes ci-dessous conservent la préparation de la **0.5.6**, ses vérifications locales et la première campagne interrompue ; ils ne valident pas la 0.5.7.


La préparation des sources **0.5.6-opencl / code 13** portait sur cette édition, avec la même identité Android et le même certificat durable. Le [guide](docs/DIAGNOSTIC_OPENCL_056.md) et les [notes](docs/releases/0.5.6-opencl.md) distinguent le correctif de diagnostic d’une résolution encore non démontrée de l’échec GPU. Aucun modèle, poids, quantification ou contexte n’est modifié pour accélérer les mesures.

Les nouvelles régressions doivent vérifier la conservation de l’étape et de la raison bornées depuis le natif jusqu’au rapport, les codes et compteurs inconnus, le refus des données arbitraires, la réutilisation éventuelle d’une préparation échouée et les durées inclusives du premier appel. Les protections de Pause, de déplacement, de callbacks périmés, d’occurrences, de reprise et d’export demeurent requises. Le chemin CPU ne doit jamais ouvrir le pilote OpenCL ; le mode GPU strict ne peut pas valider un repli CPU.

L’inventaire de cette version contient **178 tests noyau, 418 tests JVM application et 110 tests Android**, soit **706 méthodes distinctes**. La campagne réussie ci-dessus a retrouvé les effectifs et identités exactes sur **l’APK release**, avec **Android 17 / API 37**. Les résultats Android 16 / API 36 de la version précédente ne les ont pas remplacés. Les niveaux de compilation et de ciblage restent API 36 ; le niveau du système d’exécution est une mesure distincte. Les tests émulateur n’établissent ni déport GPU réel sur le OnePlus, ni qualité linguistique, débit soutenu, autonomie ou TalkBack réel.

Avant fusion, la proposition exacte doit construire et tester cet APK dans GitHub Actions. Après fusion à deux parents, la reconstruction doit prouver les mêmes sources et le contenu applicatif identique hors signature, sans répéter les suites réussies. La publication exige la signature durable et le contrôle indépendant des fichiers publics. La **0.5.2 reste la dernière stable**. Les résultats de cette préparation sont distincts de ceux de la candidate suivante.

### Préparation et outillage local 0.5.6

Les **59 tests Python hors réseau** ont réussi : **20** pour la publication, **23** pour la synchronisation de l’historique, **10** pour le nettoyage documentaire et **6** pour la provenance après fusion. Le formatage des notes publiques a aussi été vérifié avec des identifiants synthétiques, sans signature ni téléversement. Ces résultats ne remplacent pas les suites Kotlin, JNI et Android de la candidate.

La compilation Kotlin principale, JVM et AndroidTest, puis **42 méthodes JVM ciblées**, ont réussi en **1 min 12 s** : diagnostic **3**, runtime **8**, moteur **23** et benchmark **8**, sans échec, erreur ni test ignoré. Les tâches requises `:core:test :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` ont ensuite réussi en **2 min 50 s** ; les deux architectures natives ont été construites et lint rapporte **0 erreur, 97 avertissements**. Les tâches à jour, notamment le noyau, ne sont pas comptées comme une nouvelle exécution de tests. Le test Android AUTO a été corrigé pendant cette construction pour distinguer les replis mémoire légitimes du backend absent ; son fichier final a effectivement été recompilé, sans modifier le garde-fou mémoire de production.

Les trois essais natifs hôte ont réussi : sonde existante, traduction avec modèle miniature terminant et fixture sensible au contexte, et détection OpenCL synthétique. Cette dernière exerce les étapes d’erreur, la réponse mémorisée, les compteurs bornés et une initialisation concurrente unique à travers le même parcours de détection que le code de production ; elle ne mesure aucun pilote GPU réel. La comparaison des logits avec et sans réutilisation reste identique pour les cas testés.

Les APK ont passé les contrôles du manifeste compilé, des alignements ELF/ZIP de 16 Kio et de l’absence de pilote OpenCL embarqué ou de dépendance obligatoire vers lui. Le premier contrôle ad hoc, trop large, refusait tout GGUF dans l’APK principal : il a correctement trouvé l’ancien modèle miniature de diagnostic intégré. Le contrôle corrigé distingue ce modèle conservé de la fixture terminante de traduction, qui reste uniquement dans l’APK AndroidTest. Ces vérifications locales ne remplacent pas la campagne complète sur l’APK release et Android 17.

### Première campagne Android 17 interrompue

Le [run 40747778424](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40747778424), source `97a24d33b96e39cb235a0725043e3ca5122c986c`, a validé **178 tests noyau et 418 JVM**, puis s’est arrêté après **2 tests Android exécutés, dont 1 échec et 108 manquants**. Ce run ne valide donc pas les 706 méthodes attendues. Les rapports sont conservés dans l’artefact `12843267116`, ZIP de **2 982 536 octets**, SHA-256 `82950fb1fca11abf66972ede3e0e5c0ee154ce98c5617200bf8ce48352d56cb2`.

Les journaux montrent un `SIGABRT` de `system_server` dans `GoldfishMapper::readFromHost`, pendant la persistance d’un aperçu de tâche, sur l’assertion `!rcEnc->featureInfo()->hasReadColorBufferDma`. Le `DeadSystemException` de l’application survient ensuite. Les outils préinstallés étaient **Command-line Tools 12.0** ; un [reproducteur primaire](https://github.com/actions/runner-images/issues/14484) documente cette même assertion avec une cible AVD `android-0` créée par des outils antérieurs à 20. Notre première collecte n’avait pas conservé le fichier externe indiquant cette cible : cette métadonnée ne peut pas être établie rétrospectivement.

La préparation est corrigée pour installer et utiliser explicitement **Command-line Tools 22.0**, conserver les fichiers AVD et refuser une cible différente de `android-37.0` avant démarrage. Aucun test, effet graphique, capture de tâche ou garde-fou mémoire n’est désactivé. Le code applicatif et les tests restent inchangés ; la nouvelle proposition doit être validée avant fusion, sans transformer les réussites de la campagne interrompue en preuve du nouvel état.

## Préversion publiée 0.5.5-perf — résultats vérifiés

La [PR #21](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/pull/21) a été validée par le [run 40721684965, tentative 2](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40721684965/attempts/2) : **178 tests noyau, 410 JVM application et 110 Android**, soit **698 réussites**, sans échec, erreur, test ignoré, manquant ou dupliqué. Les 110 tests Android ont réellement porté sur l’APK release dans un émulateur **Android 16 / API 36**. La première tentative s’était arrêtée sur un téléchargement NDK invalide, avant toute compilation ou test ; seule cette exécution échouée a été relancée, sur les mêmes sources.

La fusion a produit `71a014af332906ecda30b74faadcc5d6ee3ddc9d`, arbre `4f24e0ccd8065f78d259d5ac204e869169f32ac5`. Le [run après fusion 40727662374](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40727662374) a vérifié les mêmes arbre et parents que la proposition testée, puis le contenu applicatif identique de l’APK reconstruit hors signature, **sans rejouer les suites**. Le [run de publication 40730354656](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40730354656) a réussi après signature durable et contrôles ; le téléchargement public indépendant a confirmé les octets exacts.

| Repère | Valeur vérifiée |
| --- | --- |
| APK publié | `SousTitresVoix-0.5.5-perf.apk`, **53 529 386 octets** |
| Identité et version | `fr.soustitresvoix.player.translation`, `0.5.5-perf`, code **12** |
| SHA-256 APK | `cd07705714eff711283711041abe196ed5f849c8cf8a9ae41cd8fb64dfd16950` |
| Certificat SHA-256 | `304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d` |
| Release / asset APK | `426942970` / `685686076` |
| Publication | Préversion, dernière stable 0.5.2 conservée |

Les essais de téléphone sont distincts de ces 698 régressions. Ils ont validé deux réponses CPU et la réutilisation de 92 tokens d’entrée sur 93 ; les tentatives GPU ont échoué avec un message trop général. Le [guide 0.5.6](docs/DIAGNOSTIC_OPENCL_056.md) décrit cette limite et les mesures CPU ponctuelles. Les résultats ci-dessous relatent la préparation de la **0.5.5**, pas la validation de la candidate suivante.

### Périmètre et inventaire 0.5.5

Les sources publiées **0.5.5-perf / code 12** conservent l’identité Android. Le [guide des performances](docs/TRADUCTION_GGUF_055.md) décrit les optimisations CPU, le GPU facultatif à mesurer, le cache de préfixe exact et le correctif de reconnaissance Hy-MT2. Traduction et OCR restent indépendants ; le NPU et l’OCR ne sont pas intégrés. Aucun gain de vitesse ou de qualité linguistique n’est déduit des tests synthétiques.

| Groupe | Vérifications requises |
| --- | --- |
| Profil et erreurs | Métadonnées du GGUF officiel Hy-MT2, refus des profils ambigus, distinction accès/copie périmée/profil/chargement/backend/génération, détail technique expurgé. |
| CPU et GPU | Chemin CPU conservé, backend GPU facultatif, absence de pilote et échecs perceptibles, backend demandé distinct de l’exécution et du déport réellement constatés. |
| Empaquetage natif | ARM64 OpenCL/KleidiAI et x86_64 CPU ; `libOpenCL.so` facultative au manifeste, aucune copie de ce pilote dans l’APK et aucun `DT_NEEDED` obligatoire vers lui ; licences et copyrights conservés. |
| Préfixe natif | Identité exacte des tokens, résultat équivalent au chemin sans réutilisation, bornes de contexte, remise à zéro après interruption/erreur et exclusion des états périmés. |
| Mesures | Temps de chargement, consigne et génération séparés ; nombre de tokens calculés et réutilisés ; aucune affirmation GPU ou gain arbitraire à partir du seul inventaire. |
| Occurrences et lecteur | SRT autonome, répétitions, chevauchements, segmentation Unicode, sorties incomplètes, annulation/reprise, export complet ; pause, déplacement et callbacks périmés toujours prioritaires. |
| Interface et stockage | Choix explicite CPU/GPU, état du modèle fiable, paramètres de reprise compatibles, progression et erreurs perceptibles, grands caractères et défilement. |

L’inventaire de la 0.5.5 contient **178 tests noyau, 410 tests JVM application et 110 tests Android**, soit **698 méthodes distinctes**. Les lanceurs ont retrouvé ces effectifs et identités exactes dans les XML du run vérifié ci-dessus. Les succès des versions précédentes n’ont pas servi à valider ces nouvelles sources. Aucun modèle de production ni texte personnel n’a été téléchargé ou téléversé pour ces régressions ; leurs résultats ne mesurent pas TalkBack réel, la fidélité linguistique ou les performances du téléphone.

Le modèle miniature terminant reste généré dans les **assets de l’APK d’instrumentation seulement**, jamais dans l’APK principal. Ses générations réelles vérifient JNI et le cycle de vie ; les doubles contrôlés JVM restent distincts. L’émulateur ne démontre pas un calcul GPU sur le OnePlus ni le gain d’un modèle de production.

Avant fusion, la proposition exacte doit construire puis tester l’APK release dans GitHub Actions. Après la fusion à deux parents, une reconstruction vérifie la provenance et le même contenu applicatif hors signature, **sans rejouer les suites**. La distribution exige le certificat durable `304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d`, puis le contrôle des fichiers publics. La 0.5.2 reste la stable recommandée.

### Contrôles locaux exécutés sur l’outillage

Les **59 tests Python hors réseau** ont réussi pour les scripts de cette candidate : **20** de publication, **23** de synchronisation de l’historique, **10** de nettoyage documentaire et **6** de provenance après fusion. Ils vérifient les refus, l’identité de la release, la préversion sans changement de dernière stable et le contenu applicatif hors signature. Ils ne compilent pas l’application et ne remplacent pas les suites Kotlin, JNI ou Android propres à la nouvelle candidate.

### Contrôles locaux de construction et reprises ciblées

Le passage des tâches requises `:core:test :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` a réussi en **2 min 53 s**, avec les deux architectures natives, OpenCL et KleidiAI pour ARM64. Lint rapporte **0 erreur et 97 avertissements**. Les inspections des APK ont confirmé les alignements ELF/ZIP de 16 Kio, le manifeste attendu, le modèle terminant uniquement dans l’APK de tests, les licences conservées et l’absence de pilote OpenCL embarqué ou de dépendance `DT_NEEDED` obligatoire vers lui. Ces contrôles ne signifient pas que les tests Android ont été exécutés ni qu’un GPU de téléphone a calculé.

Une première compilation Kotlin incrémentale a rencontré un doublon de cache de helper. La reprise de compilation principale, JVM et Android avec l’incrémentalité désactivée a réussi en **1 min 06 s**. Les sources n’ont pas été remplacées par des stubs et les protections applicatives n’ont pas été abaissées.

Un premier lot ciblé de **141 méthodes JVM** a produit **133 réussites et 8 échecs** dus à l’espace temporaire : l’environnement overlay rapportait `File.usableSpace == 0`, donc les imports de fixtures étaient refusés par le garde-fou réel de stockage. Les huit échecs appartenaient à cinq essais de benchmark, deux de stockage du modèle et un de contrôles SRT. Les trois classes concernées ont ensuite été rejouées intégralement avec le répertoire temporaire Java sur un espace RAM réellement disponible : **5 + 13 + 5 = 23 réussites**, sans échec, erreur ni test ignoré. Aucun garde-fou de production ni mesure d’espace n’a été simulé pour obtenir ce résultat. Ces passages distincts ne sont pas présentés comme une nouvelle campagne complète de 141 réussites sur l’état final.

La dernière reprise locale ciblée a réussi en **1 min 47 s** : **22 méthodes JVM**, réparties en **7** de runtime, **6** de contrôles du modèle, **5** de benchmark, **3** d’interface du benchmark et **1** de reconstruction de l’activité. La compilation incrémentale des deux architectures, l’APK AndroidTest et lint ont également réussi, avec **0 erreur et 97 avertissements** lint. Ce lot ciblé ne remplace pas l’inventaire complet de **698 méthodes attendu en CI**.

L’essai natif hôte a comparé une réutilisation de préfixe avec un calcul neuf sur une fixture sensible au contexte, aux poids d’attention non nuls : différence maximale des logits **0** pour les cas testés. Cette vérification CPU hôte ne mesure ni traduction de production, ni GPU du téléphone, ni gain prolongé. Les suites finales de cette proposition ont ensuite été exécutées avec les résultats de la 0.5.5 vérifiés ci-dessus.

Le dernier smoke test natif hôte et l’ancienne sonde ont aussi réussi après les corrections de reconnaissance des messages d’offload. Le helper sensible au contexte est réservé à l’essai hôte et absent des APK. Ces essais ne transforment pas une déclaration de backend ou un chargement de couches en preuve de calcul GPU sur le téléphone.

## Préversion publiée 0.5.4-local — résultats vérifiés

La [PR #20](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/pull/20) a été validée dans le [run 40700699288](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40700699288), avec **178 tests noyau, 375 tests JVM application et 109 tests instrumentés Android**, soit **662 réussites sans échec, erreur ni test ignoré**. L’instrumentation portait sur l’APK release de test et exécutait le runtime CPU avec le modèle miniature synthétique terminant.

La fusion a produit `6797e59a19053b375a24f8b7bb1c8e6beb48f478`, arbre `2afa49e2be8d24f56c8430e7de0c0da99c3b62fc`. Le [run après fusion 40706287348](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40706287348) a vérifié le même arbre et les mêmes parents que la fusion testée, reconstruit la release et comparé son contenu applicatif hors signature, **sans rejouer les suites**. Le [run de publication 40708944236](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40708944236) a réussi après signature durable. Le téléchargement public indépendant a retrouvé exactement le fichier signé.

| Repère | Valeur vérifiée |
| --- | --- |
| APK publié | `SousTitresVoix-0.5.4-local.apk`, **53 647 479 octets** |
| Identité et version | `fr.soustitresvoix.player.translation`, `0.5.4-local`, code **11** |
| SHA-256 APK | `d98184d6c54a0beccbe38ceff9fa7a6431037bb5a4c2d384781f6c540d628210` |
| Certificat SHA-256 | `304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d` |
| Release / asset APK | `426794876` / `685300135` |
| Publication | Préversion, sans remplacer la dernière stable 0.5.2 |

Le [guide 0.5.4](docs/TRADUCTION_GGUF_054.md) conserve le parcours publié. CPU seul, traduction textuelle locale, reprise et export étaient validés techniquement ; ni GPU, NPU, OCR, qualité linguistique universelle ou débit prolongé du téléphone n’en sont déduits. Le refus lié aux métadonnées du GGUF officiel Hy-MT2 a été identifié après publication et fait l’objet de la candidate suivante.

### Contrôle local des assets de test

Un premier passage local a refusé des dépendances implicites entre le générateur du modèle terminant et les tâches lint AndroidTest. Une tentative avec un fournisseur mappé a permis la compilation, mais l’inspection de l’APK de tests a révélé l’absence de la fixture : ce passage ne validait donc pas son empaquetage. Les tâches consommatrices AndroidTest possèdent désormais une dépendance explicite sur le générateur.

La reprise ciblée **`:app:lintDebug :app:assembleDebugAndroidTest`** a réussi en **1 min 45 s**, sans relancer les suites JVM ou noyau déjà exécutées. Le générateur a réellement produit **51 072 octets** ; le ZIP AndroidTest contient `assets/translation_test_model.gguf`, identique au fichier généré, SHA-256 `306a4f24485ac99191335242d58fccc2e25182985b72eb86fe74b01d5f443e61`. L’APK principal debug ne contient pas cette fixture. Son manifeste compilé a aussi passé les contrôles de version, permissions et services privés. Ces constats de construction et de contenu ne remplacent pas l’exécution des tests instrumentés dans la campagne CI finale.

## Préversion publiée 0.5.3-diagnostic — résultats vérifiés

La [PR #19](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/pull/19) a été validée dans le [run 40641431843](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40641431843), avec **178 tests noyau, 316 tests JVM application et 106 tests instrumentés Android**, soit **600 réussites sans échec, erreur ni test ignoré**. L’instrumentation portait sur l’APK release de test, avec un véritable essai CPU du modèle miniature synthétique.

La fusion a produit `3b68b118c3f7d21ef47a47e6eb5611ceb34f2055`. Le [run après fusion 40643641925](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40643641925) a vérifié le même arbre et les mêmes parents que la fusion testée, reconstruit la release et comparé son contenu applicatif hors signature, **sans rejouer les suites**. Le [run de publication 40647450315](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40647450315) a réussi après signature durable et contrôle des fichiers téléchargés.

| Repère | Valeur vérifiée |
| --- | --- |
| APK publié | `SousTitresVoix-0.5.3-diagnostic.apk`, **53 565 559 octets** |
| Identité et version | `fr.soustitresvoix.player.translation`, `0.5.3-diagnostic`, code **10** |
| SHA-256 APK | `c622d3f49bced6f1afeb8abd35060841cc2f7636f75576e5e6c1bc5af18d80d1` |
| Certificat SHA-256 | `304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d` |
| Release / asset APK | `426350156` / `684138289` |
| Publication | Préversion, sans remplacer la dernière stable 0.5.2 |

Le [guide du diagnostic](docs/DIAGNOSTIC_LOCAL_053.md) et les [notes 0.5.3](docs/releases/0.5.3-diagnostic.md) conservent son périmètre. Les essais sur téléphone transmis par l’utilisateur ne sont pas des exécutions de CI et ne sont pas ajoutés aux effectifs ci-dessus. Leur succès de chargement court ne mesure ni la qualité linguistique, ni une accélération GPU/NPU, ni la stabilité d’un film complet.

## Version 0.5.2 — parcours de validation

La version publiée de référence est **0.5.2 / code 9**, avec l’identité Android
`fr.soustitresvoix.player.translation`. Les [notes de version](docs/releases/0.5.2.md)
décrivent Google en ligne, ML Kit local, la lecture pendant la traduction et le
correctif du bouton audio avancé. Les preuves d’exécution sont produites par les
[runs GitHub Actions](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/workflows/android.yml)
du commit proposé ; la présence d’un test dans les sources ne signifie pas qu’il
a été exécuté avec succès.

L’inventaire de cette version contient **178 tests noyau, 290 tests JVM application et 99 tests Android**, soit **567 méthodes**. Le workflow doit retrouver exactement cet inventaire dans les rapports, sans échec ni test ignoré. Ce total décrit les tests attendus ; leur réussite doit être établie par le run concerné.

Les régressions ajoutées portent sur :

- la conservation de tous les fragments, caractères Unicode, segments de réponse
  et occurrences répétées pendant la traduction ;
- le refus d’une réponse incomplète, les délais entre appels, les refus Google,
  l’annulation et la reprise des occurrences déjà validées ;
- le téléchargement des modèles locaux, les erreurs et les réponses tardives ;
- l’attente à la frontière de traduction, la reprise seulement après une attente
  créée par l’application, et la vocalisation complète des occurrences ;
- l’ouverture réelle des paramètres audio experts depuis Son, et l’absence de
  reprise de lecture provoquée par ce dialogue.

Les transports réseau et les tâches ML Kit sont simulés dans les régressions
déterministes. Ils vérifient le comportement de l’application, pas la qualité
linguistique ni la disponibilité permanente des services. Une sonde séparée
envoie **une seule phrase synthétique** au service Google en ligne, sans reprise
ni contournement. Son rapport `google-network-probe.json` distingue une réponse
complète, un refus et une indisponibilité ; un refus ne devient jamais un succès
de traduction. Le téléchargement réel des modèles et la qualité d’une voix sur
un téléphone font l’objet du [parcours manuel](docs/TESTS_TELEPHONE.md).

Avant fusion, le workflow construit l’APK release de test puis exécute la suite
Android sur ce même fichier. Après fusion, il reconstruit la release, vérifie sa
provenance et compare son contenu avec l’APK testé, sans relancer les suites.
La publication ajoute les empreintes et les preuves à la
[release 0.5.2](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.5.2).
Les résultats historiques ci-dessous restent attachés à leurs versions.

## Version 0.5.1 — validation exécutée avant fusion

Les sources **0.5.1 / code 8** ont été fusionnées par la [PR #16](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/pull/16). Leur commit exact est [`d5d5b63ea37ca4be423f375e8e7a75e870d154e6`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/d5d5b63ea37ca4be423f375e8e7a75e870d154e6). La signature privée reste hors du dépôt et des artefacts de CI.

### Résultats réellement obtenus

Le [run préalable 40556708779](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40556708779) a réussi sur la proposition de fusion, avant son intégration à `main` :

| Contrôle | Résultat |
| --- | --- |
| Noyau indépendant d’Android | **178 tests réussis** |
| Application, JVM release | **254 tests réussis** |
| APK release, instrumentation Android 36 | **92 tests réussis** |
| Total | **524 tests**, aucun échec, erreur ou test ignoré |
| Compilations | APK debug et release, avec leurs APK d’instrumentation, construits |
| Lint | Variantes debug et release réussies ; les avertissements restent dans les rapports |
| APK | Manifestes, permissions, signatures éphémères, alignements et empreintes contrôlés |

Chaque suite complète est exécutée une fois. Les XML sont confrontés aux méthodes attendues ; la compilation d’un APK d’instrumentation ne remplace pas son exécution sur Android. Les effectifs historiques de 0.5.0 restent dans leur section et ne s’additionnent pas à ces résultats.

Les régressions ajoutées couvrent le défilement après curseur/bouton/case, le maintien de la saisie, les refus DeepL 429/529 puis succès, les délais du serveur, le plafond de reprises, l’annulation et la fermeture, le refus 456, une réponse réseau perdue et la reprise sans renvoyer le préfixe déjà traduit. Ces échanges utilisent des textes et réponses synthétiques : aucun quota personnel n’est consommé.

### Construction après fusion et identité de l’APK

Le [run de construction 40560530124](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40560530124) a réussi après fusion. Il a vérifié le même arbre de sources et les parents de la fusion testée, exécuté `:app:assembleRelease`, puis constaté que le **contenu applicatif reconstruit est identique à celui de l’APK testé**, hors métadonnées de signature. Il n’a pas relancé les suites.

| Preuve | Valeur |
| --- | --- |
| Arbre de sources | `6ee86dbeab9cde02598325de3316d34ea3be4cb7` |
| Source de la release | `d5d5b63ea37ca4be423f375e8e7a75e870d154e6` |
| APK reconstruit non signé, SHA-256 | `5cb61d8fb11eb0f704730dbaf1c682bc53c013c282be245bbc1ea42ceeb56953` |
| APK signé publié | `SousTitresVoix-0.5.1-correctifs.apk` |
| Taille du fichier signé | **5 242 523 octets** |
| APK signé, SHA-256 | `75ed88b3198d4ea2bef9b837d870bb48db2624c8a8325e6f3d1fa8b9d2f5d881` |
| Certificat durable, SHA-256 | `304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d` |
| Identité Android | `fr.soustitresvoix.player.translation`, version `0.5.1`, code `8` |

La signature locale v2/v3, l’alignement et l’identité du contenu hors signature ont été vérifiés. Le [run de publication 40580117622](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40580117622) a réussi : vérification de la provenance et du certificat, envoi des assets, contrôle des fichiers téléchargés puis publication publique. La release porte l’identifiant `425961994`, l’APK l’identifiant `682813699`. Un téléchargement public indépendant a ensuite confirmé la taille et le SHA-256 exacts du fichier signé. **[Télécharger la 0.5.1](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/download/v0.5.1/SousTitresVoix-0.5.1-correctifs.apk)** · [Notes de version](docs/releases/0.5.1.md).

### Portée des résultats

La [preuve structurée 0.5.1](docs/verification/0.5.1.json) conserve les identifiants des exécutions, les effectifs, la source exacte et les empreintes de publication après expiration des artefacts temporaires GitHub Actions.

Les tests valident les sources et les scénarios exécutés. Ils ne déclarent pas réussis les essais acoustiques, TalkBack réel, Bluetooth, la qualité linguistique d’un fournisseur, les droits et quotas d’un compte personnel, ni l’autonomie ou les performances du OnePlus 15. Le [parcours sur téléphone](docs/TESTS_TELEPHONE.md) permet de consigner ces observations séparément.

## Historique des validations

Les sections suivantes conservent les résultats, les échecs intermédiaires et les contrôles attachés aux versions antérieures. Pour la procédure actuelle, consulter [Validation continue dans GitHub Actions](#validation-continue-dans-github-actions).

## Version publiée 0.5.0 — lecture intégrale et traduction progressive

La version **0.5.0, code 7**, conserve l’identité Android
`fr.soustitresvoix.player.translation`. La [fiche de version](docs/releases/0.5.0.md)
et le [guide des fonctions](docs/CONFORT_INTEGRAL_050.md) décrivent ses changements.
Les sources de `main` au commit
[`3740356420fb09eadabe23da207fa554515da101`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/3740356420fb09eadabe23da207fa554515da101)
ont réussi le [run Android 40402709269, tentative 1](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40402709269/attempts/1).
La [PR #14](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/pull/14)
avait auparavant réussi les mêmes contrôles dans le
[run 40397467976, tentative 2](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40397467976/attempts/2),
sur sa tête `beb946f1f151d9c72e496cfe61088451d78f040a`.
Le rapport de cette proposition consigne le commit de fusion provisoire testé
`ce9a1be0c0cd41958ca6364a07c1bb74390bb44d` ; la validation suivante de `main`
porte bien sur `3740356`, sans confondre ces deux sources.
La [release 0.5.0](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.5.0)
a été publiée le **26 octobre 2026 à 21:48:52 UTC** après réussite du
[run de publication 40407440389, tentative 1](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40407440389/attempts/1).
**L’APK public a été téléchargé à nouveau et vérifié** : taille, SHA-256,
certificat durable, manifeste, alignement et contenu identique à la CI hors
signature. Le tag `v0.5.0` pointe exactement vers le commit `3740356` ; `main`
est resté sur ce commit pendant la publication.
Les résultats publiés de la 0.4.0 ci-dessous restent historiques.

La [preuve durable de validation 0.5.0](docs/verification/0.5.0.json) conserve
les six résumés, les inventaires complets des méthodes, les empreintes des
entrées de preuve, les résultats lint et les faits de publication. Elle permet
de consulter ces repères après expiration des artefacts temporaires Actions.

L’inventaire des annotations `@Test` du 26 octobre 2026 contient **178 méthodes
JVM du noyau, 242 méthodes JVM de l’application et 88 méthodes instrumentées
Android**, soit **508 méthodes distinctes**. Les six suites du run de `main`
ont effectivement réussi **1 016 exécutions JUnit**, sans échec, erreur, test
ignoré, manquant, inattendu ou dupliqué. Les contrôles comparent les identités
des méthodes exécutées à l’inventaire des sources ; une méthode seulement
présente dans le dépôt ne compte pas comme exécutée.

| Contrôle exécuté sur `3740356` | Résultat |
| --- | --- |
| Noyau JVM, debug | 178 réussites. |
| Application JVM, debug | 242 réussites. |
| Noyau JVM, release | 178 réussites. |
| Application JVM, release | 242 réussites. |
| Android 36, application et tests release, passage 1 | 88 réussites sur émulateur AOSP x86_64. |
| Android 36, même paire d’APK release, passage 2 | 88 réussites. |
| Total JUnit | **1 016 exécutions de 508 méthodes distinctes**, sans échec, erreur, méthode ignorée, manquante, inattendue ou dupliquée. |
| Lint debug / release | **0 erreur, 69 avertissements et une indication dans chaque variante** ; rapports conservés sans suppression d’alertes. |
| Compilation et intégrité CI | Applications et APK de tests debug/release compilés ; manifestes, permissions, signatures correspondantes, alignement et empreintes contrôlés ; la même paire release est conservée entre les deux passages Android. |
| Outillage Python | **62 tests réussis au total** : 19 de publication et 20 de préparation d’émulateur dans le run Android ; 13 de synchronisation des releases et 10 de rangement documentaire exécutés localement. Les 62 ont aussi été relancés localement après l’actualisation documentaire. |

La vérification couvre notamment les régressions ajoutées pour la file réellement
inachevée, les sauvegardes relues sur disque après recréation, la traduction
partielle ou indisponible, les callbacks périmés et les reconstructions imbriquées
du plein écran. Les 88 tests instrumentés ont été exécutés sur Android ; leur
simple compilation n’est pas présentée comme une réussite d’instrumentation.

### APK publié et contrôlé après téléchargement

L’APK provient de l’artefact release de la validation exacte de `main` ci-dessus.
Il a été signé avec la clé durable hors du dépôt ; toutes ses entrées autres que
les données de signature ont été comparées à celles de la CI. Le fichier signé
a passé les vérifications de signature, d’alignement et de manifeste : application
non débogable et non réservée aux tests, version/code et permissions attendus.
Ces vérifications ont été répétées sur le téléchargement public après la
publication, avec comparaison de son contenu à l’APK exact de la CI.

| Repère | Valeur vérifiée |
| --- | --- |
| Fichier | [SousTitresVoix-0.5.0-confort-integral.apk](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/download/v0.5.0/SousTitresVoix-0.5.0-confort-integral.apk) |
| Taille | **5 242 608 octets** |
| Identité Android | `fr.soustitresvoix.player.translation` |
| Version / code | `0.5.0` / `7` |
| Commit source | `3740356420fb09eadabe23da207fa554515da101` |
| SHA-256 de l’APK signé | `dcaf9f07cdc85f5dfa007ceeef40a3e71cdba25b40c66381a3cf39998207ed7c` |
| SHA-256 de l’APK CI avant signature durable | `4e5ae14b9bc2cb6d2190731a9b66c06f4475ea23dcda234a44bba85cbfa5bb50` |
| SHA-256 du certificat | `304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d` |
| Publication / asset APK | `424807308` / `679580568` |
| Somme de contrôle publique | [SHA256SUMS](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/download/v0.5.0/SHA256SUMS), 108 octets, asset `679580596`, SHA-256 `c77ec58299dacf773e2485a668273dfeda0aa88d909358aaa1e7a193c1369ea9` ; ligne attendue vérifiée. |

Le certificat est celui de l’édition Traduction 0.4.0. Aucune clé privée ni
mot de passe n’est joint au dépôt ou aux fichiers publics. La compatibilité de
signature n’est pas présentée comme un essai d’installation sur tous les
téléphones ; le parcours de mise à jour reste dans les [tests sur téléphone](docs/TESTS_TELEPHONE.md).

### Charge supplémentaire sur le noyau

Les deux suites autonomes `CoreChecks` et `Revision03Checks` ont aussi été
exécutées avec **20 nouvelles graines**, de 10001 à 10020 : **40 invocations et
800 000 événements aléatoires supplémentaires**, toutes réussies sur JVM 17 avec
un plafond de tas de 512 Mio. Le contrôle a été exécuté sur `c099440` ; les objets
Git du noyau et de sa configuration de compilation sont identiques dans le
commit final `3740356`. Ces graines ne sont pas de nouvelles méthodes JUnit et
ne sont pas ajoutées aux 1 016 exécutions ci-dessus. Ce contrôle ne mesure ni
la performance d’un téléphone ni le cycle de vie d’une activité Android.
Les graines, la provenance et les objets du noyau comparés sont conservés dans
la [preuve de charge supplémentaire](docs/verification/0.5.0-additional-stress.json).

| Groupe de régressions 0.5.0 | Couverture des suites exécutées |
| --- | --- |
| Occurrences et lecture intégrale | Identités source distinctes, répétitions légitimes identiques, panneaux persistants, chevauchements, changements de style ASS, sous-titres SRT/VTT simultanés et très brefs entre deux mises à jour, déplacements et fin de vidéo après correction temporelle. |
| File et journal de narration | États détectée, préparée, en attente, commencée, terminée, interrompue et erreur ; conservation et reprise des textes en attente en cas de surcharge ; export de métadonnées sans texte et refus des contextes divergents ; absence de reprise sur callback périmé. |
| Réglages et compréhension | Recherche accentuée et rubriques, champs numériques et bornes, boutons et présélections, actualisation des profils, annulation synchrone des changements et pauses de compréhension. |
| Séances et synchronisation | Sauvegarde atomique par vidéo, choix des pistes réellement sélectionnées, restauration locale de la traduction en pause, réglages par piste, corrections à deux points ou par portions, aperçu annulable, fichier déplacé et oubli sans résurrection d’un ancien enregistrement. |
| Conservation hors ligne | Index et segments durables, contrôle WAV et SHA-256, estimation de l’espace, reprise, suppression par type, invalidation des préparations périmées, priorité à la lecture et refus de « Prêt hors ligne » sur une source incomplète. |
| Traduction progressive | Lots validés sauvegardés, refus des reprises facturables incertaines, plafond de consommation, correspondance entrée/sortie, seuil initial et pause au premier texte manquant, correction isolée, affichages et export SRT/VTT réimportable. |
| Android sur émulateur | Interactions de réglages et préparation hors ligne, en plus des scénarios existants de narration, Media3, diagnostic, image, audio et traduction, sur émulateur Android 36. |

Les tests d’application sous `app/src/test` s’exécutent sur la JVM avec
Robolectric et les vrais parseurs Media3. Ils ne remplacent pas les tests sous
`app/src/androidTest`, qui doivent être **exécutés** sur Android. Construire
`assembleDebugAndroidTest` ou `assembleReleaseAndroidTest` ne suffit pas.

### Instantanés techniques de l’émulateur

Pendant la commande d’instrumentation, un échantillonneur facultatif conserve
les sorties `dumpsys meminfo` de l’application, `gfxinfo framestats` et `cpuinfo`
dans `live-metrics/`. Il vise un départ toutes les trente secondes, avec au plus
quarante échantillons et cinq secondes au maximum par commande. Le fichier
`samples.tsv` associe chaque sortie à son numéro, son heure UTC et son statut.
La période inclut le déploiement et des tests synthétiques mixtes : certains
échantillons peuvent donc précéder le lancement du processus ou suivre sa
fermeture entre scénarios. Les compteurs de trames ne sont pas remis à zéro.
Le groupe de processus du moniteur est arrêté à la fin de Gradle et par le
nettoyage de sortie, y compris pour un passage en échec, sans modifier son
résultat. Le nettoyage attend au plus environ deux secondes avant d’envoyer
un arrêt forcé aux seules commandes de diagnostic de ce groupe.

À la sortie de chaque passage instrumenté, le script tente aussi de conserver
les sorties Android `dumpsys meminfo` de l’application, `gfxinfo framestats`,
`cpuinfo`, `package` et `thermalservice`, en plus des fenêtres et de logcat.
`diagnostic-snapshots.tsv` indique l’heure UTC et le code de sortie de chaque
commande ; chaque tentative est limitée à dix secondes. Ces fichiers restent
dans les preuves de validation du passage, sur l’émulateur jetable et ses seuls
contenus synthétiques. Le recueil est aussi tenté si la préparation échoue :
les métadonnées précisent alors le statut de sortie du script, sans prétendre
qu’une instrumentation a eu lieu.

Il s’agit d’**échantillons pendant le parcours et d’instantanés à sa fin**, pas
d’un benchmark de visionnage continu. Un processus déjà arrêté ou un service indisponible peut
ne fournir aucune mesure exploitable ; ce cas ne fait pas échouer une suite
réussie et ne masque jamais son statut initial. Les données ne prouvent ni un
nombre d’images par seconde stable pendant un film, ni la latence acoustique,
la qualité vocale, l’autonomie ou les performances du téléphone cible. Aucun
résultat chiffré n’est attribué à ces commandes avant lecture de leurs sorties
sur le run effectivement exécuté.

Dans le run final de `main` **40402709269**, chaque passage a produit **15
captures mémoire**, dont **14 avec le processus et une valeur PSS exploitables**.
Les valeurs PSS relevées vont de **46 059 à 63 148 Kio** au premier passage et de
**47 619 à 64 827 Kio** au second (`KB` dans `dumpsys`). Ce sont des relevés des
scénarios synthétiques mélangés de la suite, pas des maximums garantis pendant
un film. Les captures CPU ont toutes été produites, mais les lignes du package
ne permettent pas d’en déduire sa consommation CPU ; aucun résultat de
performance n’en est tiré. Les rapports finaux restent dans l’artefact
**Android-rapports-validation**, identifiant **12690416852** ; le SHA-256 de son
archive ZIP téléchargée est
`38063817ff6ea6dfebc3d43bfd31688f331707db19c38c1692c318ca3c1ee4c7`.
Les horodatages, statuts, valeurs extraites et empreintes des 97 fichiers
sources examinés sont conservés dans la
[preuve des relevés Android](docs/verification/0.5.0-device-metrics.json).

Dans les preuves du premier [run 40352075138](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40352075138), les instantanés finaux mémoire/trames des deux passages indiquaient `No process found`, bien que les commandes retournent zéro. Ils ne constituent donc pas des mesures mémoire ou de fluidité de l’application. Le recueil pendant l’instrumentation est ajouté pour observer aussi des phases où son processus est actif ; sa présence dans le script ne remplace pas la lecture des preuves du passage suivant.

### Contrôles d’outillage exécutés le 26 octobre 2026

Les commandes suivantes ont réellement réussi. Le job Android final a exécuté
le contrôle structurel et les deux premières suites Python ci-dessous ; les
deux autres suites ont aussi été exécutées localement pour la documentation.
Les 62 tests ont été relancés localement après sa mise à jour :

```sh
python3 tools/check-project.py
python3 tools/test-publish-verified-release.py
python3 tools/test-emulator-readiness.py
python3 tools/test_sync_releases.py
python3 tools/test-documentation-cleanup.py
```

Le contrôle structurel a examiné **67 fichiers Kotlin de production**, les XML,
la configuration réseau, la démonstration synthétique et l’absence de clé privée
versionnée. Les suites comportementales ont réussi **19 + 20 + 13 + 10 = 62 tests
Python**, dont les nouveaux contrôles de démarrage de l’émulateur, avec la
répartition CI/local précisée ci-dessus. Elles utilisent des réponses contrôlées en mémoire : ce résultat ne
constitue ni une publication réelle ni une validation Android.

### Échecs intermédiaires conservés

Les passages de développement ont révélé des annulations de réglages et d’aperçu
qui intervenaient trop tard, une pause de compréhension susceptible de rester
bloquée, ainsi qu’une perte de répétitions SRT strictement simultanées lorsque
seuls les groupes de rendu étaient utilisés. Des corrections et des régressions
spécifiques sont ajoutées. Leur présence ne vaut pas réussite du passage final.
Une exécution interrompue, un rapport incomplet ou un XML d’un passage précédent
ne doit pas être compté comme un succès.

Le [run 40352075138](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40352075138)
a exécuté les 87 méthodes instrumentées deux fois : **81 réussites et 6 échecs
à chaque passage**. Les erreurs concernaient les boutons de fermeture des
réglages rognés en grande police et deux attentes de tests devenues inexactes
après l’ajout des nouvelles commandes. Les actions des réglages ont été placées
dans une zone séparée du contenu défilant ; les tests exigent toujours des
boutons entièrement visibles et vérifient les dix champs réels du formulaire
de traduction.

Le [run 40358043991](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40358043991)
a ensuite exécuté les mêmes 87 méthodes deux fois : **86 réussites et un échec
à chaque passage**. Le dernier échec provenait du contrôle de défilement du
formulaire, qui mélangeait les coordonnées de l’écran et celles de la fenêtre
du dialogue. La correction emploie le même repère pour le champ et sa zone
visible, sans relâcher les exigences de visibilité ni supprimer de test.

Le [run 40365760856](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40365760856)
de la [PR #13](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/pull/13),
dont la tête était `c14e85fa906610f5ddaafd0ac5c95b75f5edbfe1`, a réellement réussi
les six suites : **166 + 226** méthodes JVM en debug, **166 + 226** en release,
puis **87 + 87** méthodes instrumentées sur la même paire d’APK release. Ses
rapports établissent **958 exécutions réussies**, sans échec, erreur ni test
ignoré, manquant, inattendu ou dupliqué. Le commit effectivement extrait par
Actions était le commit de fusion provisoire de la proposition
`2abca37882dd768259e658a3f790f5fc894e1b1c`, consigné dans
`build/verification/source-commit.txt` ; il ne faut pas le confondre avec la
tête de branche ni avec une fusion publiée dans `main`. Le rapport lint release
contient **0 erreur, 69 avertissements et une indication** : recommandations
KTX, internationalisation, versions de dépendances, constructeurs de vues,
règles de sauvegarde et détection statique d’un clic déjà transmis à
`performClick()` par le détecteur de gestes. Aucun de ces avertissements n’a
été masqué pour obtenir ce résultat.

Ce passage réussi reste une étape de développement. Une revue ciblée a ensuite
reproduit deux défauts de sélection de la réplique à relire : remettre en file
une ancienne occurrence déjà terminée pouvait la faire passer pour la dernière
achevée ; abandonner une réplique en attente qui n’avait jamais commencé pouvait
masquer l’interruption de celle réellement commencée. Des références de dernière
fin et de dernière interruption réellement commencée ont corrigé ces cas ; leurs
régressions font partie de la validation finale en tête de ce document. Le run
intermédiaire ci-dessus ne valide pas ces changements ultérieurs.

La [tentative 1 du run 40374255289](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40374255289/attempts/1),
tête `63cfd6a49699516cf421814f662cf399093cf492`, a réussi les suites JVM
174/228 en debug et en release ainsi que les compilations et contrôles d’APK,
puis s’est arrêtée **avant toute instrumentation Android**. Le garde-fou a
détecté un ANR de `com.android.systemui` au démarrage ; les diagnostics package
confirment que notre application n’était pas encore installée et les sorties
mémoire indiquent `No process found`. Aucun test Android de cette tentative ne
compte comme exécuté. L’archive de rapports conservée a pour SHA-256
`1d921c3fce99a22192a4920053639f15f993c9327f0d6ee4b466fead9682b8f6`.
Le job complet a été relancé sur un nouvel environnement sans fermer le dialogue,
retirer le garde-fou ni filtrer une suite. Les logs montrent une forte pression
CPU au démarrage et aucune saturation mémoire détectée ; cela motive un essai
avec quatre cœurs d’émulateur, sans prétendre établir la cause ni garantir
l’absence d’ANR système. Les [ressources standard du runner public Ubuntu](https://docs.github.com/actions/using-jobs/choosing-the-runner-for-a-job)
sont documentées par GitHub ; les ressources réellement observées restent dans
les preuves de chaque run.

La [tentative 2 de ce même run 40374255289](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40374255289/attempts/2)
a ensuite réussi les six suites de cette tête `63cfd6` : **978 exécutions**, dont
87 tests Android réellement exécutés dans chacun des deux passages. Elle ne
valide pas les correctifs de reprise ajoutés ensuite.

Une autre revue a confirmé une perte possible lors de **Reprendre après mort du
processus** : une occurrence A dont la voix continuait à la frontière de B pouvait
avoir expiré visuellement à la position sauvegardée. Restaurer seulement le
journal et la position ne reconstruisait pas cette file. La version 0.5.0 conserve
donc un repère de la vraie file inachevée, distinct des interruptions historiques
après déplacement. Les tests de cette correction couvrent aussi la restauration
de la traduction, les corrections temporelles, les sauvegardes précoces et les
commandes utilisateur qui invalident une reprise différée. Ces scénarios ont
ensuite réussi dans la validation finale en tête de ce document.

La tête `c0994407f2f4c3d13c354e08b78768a09f676a2e` a passé **832 exécutions JVM**
par tentative (178 + 238 en debug et autant en release), lint et les compilations
dans les [tentatives 1 et 2 du run 40380367522](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40380367522).
Les deux démarrages se sont arrêtés avant installation de l’application et
avant tout test Android, sur un ANR de Launcher3 sans fenêtre focalisée. Les
journaux montrent l’injection de la touche 82 par l’action d’émulation avant
l’affichage de sa première fenêtre et avant notre script ; cette attribution
causale reste une inférence appuyée par la chronologie. Les deux archives ont
pour SHA-256 `75e852a2c3d184be54e7b9d15de1d638f71fee9a308f9fc446d60b4799c421bf`
et `24d5f871992b3809ee2beb051901a06a39db79d7621b8100a604bfd597068bd2`.
Les quatre cœurs n’ont donc pas suffi à éviter ce défaut de démarrage.

Le lancement CI utilise désormais les outils CLI du SDK officiel, sans cette
injection précoce. Un contrôle en lecture seule exige boot, service framework,
animation arrêtée et fenêtre focalisée, affichée et stable sur trois observations.
Le verrouillage éventuel est traité seulement après cette première vérification,
puis la fenêtre de Launcher est vérifiée avant les deux suites existantes.
Un ANR reste un échec immédiat ; aucun dialogue n’est fermé et aucune suite n’est
réessayée dans le lanceur. **20 tests comportementaux** ont vérifié ce contrôle,
ses délais et son refus des appareils physiques. Son analyseur a aussi accepté
un vrai dump sain Android 36 et refusé un vrai dump ANR conservé. La validation
réelle du nouveau lancement a ensuite réussi dans les runs finaux. Cela ne
transforme pas les tentatives de démarrage en échec en tests Android exécutés.

En local, les inventaires complets avant le correctif de plein écran ont passé **178 tests core, 238 tests
app debug et 238 tests app release**, sans échec ni méthode ignorée ou manquante.
Deux assemblages ont rencontré un fichier dex temporaire disparu dans les sorties
partagées. La reconstruction avec un répertoire de build isolé a ensuite réussi
les APK debug et de tests, lint debug (**0 erreur, 67 avertissements**), manifestes,
signatures correspondantes et alignement 16 Kio. Ces APK locaux restent des
contrôles ; ils ne remplacent pas l’artefact exact de `main` destiné à la publication.

La révision `1442f62ccafe13987eb0ad85c25ce240b5db7e45` a ensuite réussi les
**1 006 exécutions** de la proposition dans le [run 40386234171](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40386234171).
La fusion de la PR #13 a produit `db346e703c5b591757a3c1c326c9b052075b6620`.
Son [run main 40392692955](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40392692955)
a correctement bloqué la publication : **1 005 réussites et un échec sur
1 006 exécutions**, avec 86/87 au premier passage Android et 87/87 au second.
Le second succès ne remplace pas le premier échec. Le lancement CLI a bien obtenu
trois observations de fenêtres affichées et stables et conservé le statut d’échec.

L’exception réelle `The specified child already has a parent` provenait du
rattachement du `PlayerView` dans `MainActivity.buildUi`, pendant
`CinemaInstrumentationTest.landscapeFullscreenHidesSystemBarsAndBackExitsOnlyCinema`.
Une régression déterministe avec de vrais parents Android a reproduit cette
exception sur l’ancien code : un changement de configuration et une sortie du
plein écran intervenaient pendant le retrait de la vue. Le correctif sérialise
ces reconstructions et regroupe la demande suivante, en conservant le dernier
état demandé. Les tests ciblés passent après correction et le nouveau test
instrumenté vérifie l’unicité des vues, la même surface et la pause sur plusieurs
cycles. Le dialogue de reprise est aussi libéré à la destruction de l’activité,
car un `WindowLeaked` distinct était présent dans ce même journal.

L’archive de cet échec a pour SHA-256
`c697ba469a246857b4cca8f2f6b777b2d5858597f5c318bed78e21975aecb2f3`.
Aucune capture paysage n’avait encore été créée par le test défaillant ; les
captures du second passage attestent uniquement sa réussite. Aucun APK de ce
commit n’a été signé ou publié. Les quatre nouvelles méthodes JVM et la nouvelle
méthode Android ont ensuite réussi les passages complets de la PR #14 et de
`main`, référencés en tête de ce document.

La [tentative 1 de la PR #14, run 40397467976](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40397467976/attempts/1),
sur la même tête `beb946f`, a réussi **840 exécutions JVM** mais échoué au
démarrage Android : un ANR de `com.android.systemui`, signalé comme échec de
démarrage, a interrompu le contrôle de fenêtre. Aucun test Android n’a été
exécuté et aucun état prêt n’a été délivré. L’archive a pour SHA-256
`b4ed243d79adb799c6a9e637305218249fe1fc1998d0f1370189ce00ab1bcd26`.
La relance complète sur la même source a réussi en tentative 2 ; les 1 016
exécutions finales de `main` constituent une vérification supplémentaire sur
le commit de fusion réel. Aucun ANR système ni test en échec n’est masqué par
cette relance.

Après publication, la [PR documentaire #15](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/pull/15),
tête `0ac3c43db278d8961c56ae09783ab37b479e5ba9`, a rencontré deux échecs de
démarrage dans les [tentatives 1 et 2 du run 40409577689](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40409577689).
Chacune a réussi **840 exécutions JVM**, puis le garde-fou a arrêté le lancement
après détection d’un ANR de `com.android.systemui`, signalé comme échec de
démarrage, avant installation de l’application et avant tout appel du lanceur
d’instrumentation : **zéro test
Android exécuté**, aucun état prêt délivré. Les archives téléchargées ont pour
SHA-256 `1353054544ee21b944add671bb82ff8c4825a4cec5f96492a54f7081d8a2a129`
(tentative 1) et `d74339e37d7008908e52534d1975801c957f35e8e298352895cdb5010fa6f9c0`
(tentative 2). Le lancement CI remplace ensuite `-gpu swiftshader_indirect` par
`-gpu swangle` pour examiner l’hypothèse d’un problème de rendu logiciel au
démarrage ; cette hypothèse n’établit pas la cause des ANR. API 36, écran,
quatre cœurs, mémoire, garde-fous et deux passages complets des 88 tests restent
inchangés. La validation de cette configuration repose sur les suites complètes
de la PR #15 et de son commit de fusion dans `main`, dont les résultats restent
consultables dans leurs runs. Ces échecs de démarrage ne remplacent ni les
résultats du commit publié `3740356` ni la vérification de son APK.

La validation des sources et la publication sont deux contrôles distincts.
L’APK distribué provient de l’artefact exact de la CI réussie ; il a été signé
avec la clé durable hors dépôt, contrôlé puis téléchargé à nouveau après
publication. Les API de comptes
personnels, la fidélité linguistique, les voix commerciales, TalkBack, Bluetooth
et les performances sur le téléphone cible restent des essais séparés ; aucune
garantie universelle d’absence de défaut n’est déduite des tests automatisés.

## Version publiée 0.4.0 — résultats de référence

La [release 0.4.0](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.4.0), publiée le **24 octobre 2026**, est issue du commit [`32864ba`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/32864ba732d834e71d87b887ae63f0d7bc09eb8c). Le [run Android 40053284969, tentative 1](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40053284969) a réellement réussi sur ce commit de `main`.

| Contrôle exécuté | Résultat |
| --- | --- |
| Noyau JVM, debug | 87 réussites. |
| Application JVM, debug | 137 réussites. |
| Noyau JVM, release | 87 réussites. |
| Application JVM, release | 137 réussites. |
| Android 36, application et tests release, passage 1 | 82 réussites sur émulateur AOSP x86_64. |
| Android 36, même paire d’APK release, passage 2 | 82 réussites. |
| Total JUnit | **612 exécutions de 306 méthodes distinctes**, sans échec, erreur, méthode ignorée, manquante, inattendue ou dupliquée. |
| Lint debug / release | **0 erreur et 51 avertissements dans chaque variante**. Les avertissements restent visibles dans les rapports ; ils ne sont pas supprimés pour obtenir ce résultat. |
| Compilation et intégrité | Applications et APK de tests debug/release compilés ; manifestes, permissions, signatures, alignement, sources et empreintes contrôlés. |
| Outils de publication | 19 tests Python comportementaux réussis dans le même run Android. |
| Synchronisation et rangement documentaire | 13 + 7 tests Python réussis dans le [run 40053284958](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40053284958). |

Les **39 tests Python** s’ajoutent aux 612 exécutions JUnit. Les outils utilisent des réponses contrôlées en mémoire : leur réussite ne représente pas 39 opérations réelles sur un compte fournisseur. Les recommandations lint concernent notamment KTX, les chaînes, les vues, l’accessibilité, les règles de sauvegarde et les versions de dépendances.

### APK effectivement distribué

Le [run de publication 40054994161](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40054994161) a réussi après la validation de `main`. Il a comparé le contenu de l’APK signé durablement à l’APK exact testé en CI, hors fichiers de signature, puis vérifié les téléchargements de l’APK et de `SHA256SUMS` avant de rendre la release publique. Aucune recompilation intermédiaire n’a remplacé l’APK testé.

| Repère | Valeur vérifiée |
| --- | --- |
| Fichier | `SousTitresVoix-0.4.0-traduction.apk` |
| Taille | 5 066 480 octets. |
| Identité Android | `fr.soustitresvoix.player.translation` |
| Version / code | `0.4.0` / `6` |
| SHA-256 de l’APK | `30d1126392a9fd995867f599b12436b1e2d9e8e1d41bc60d20dca76c9b88f298` |
| SHA-256 du certificat durable | `304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d` |

L’édition Traduction s’installe à côté de la stable 0.3.2. La signature de cette dernière n’étant plus accessible, les deux applications ont des identités et des signatures distinctes. La clé durable de la 0.4.0 est sauvegardée hors du dépôt ; aucune clé privée ne fait partie des assets publics. Voir les [instructions d’installation et de test sur téléphone](docs/TESTS_TELEPHONE.md).

### Portée et limites de la validation

Ces résultats attestent les sources, l’environnement et les scénarios exécutés. Ils ne garantissent pas l’absence de tout défaut. Les régressions utilisent de vraies classes Android/Media3 et des fichiers synthétiques ; les réponses de traduction et le service TTS sont contrôlés. **Les API de comptes personnels ou payants, la fidélité linguistique, les voix commerciales, TalkBack et les performances sur OnePlus 15 n’ont pas été validés par ces tests.** Les essais manuels restent à effectuer et à consigner séparément.

Les résultats d’une ancienne étape ou les effectifs attendus d’un workflow ne remplacent jamais les rapports du commit effectivement distribué. Les éléments ci-dessous conservent les contrôles, les erreurs intermédiaires et leurs corrections.

## Maintenance après publication — reconnaissance des fusions GitHub

La réorganisation documentaire fusionnée au commit [`d1627bd`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/d1627bddc3698181604a58bbff4f4a87481bf26e) a passé à son tour le [run Android 40086156531](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40086156531) : les six suites, soit **612 exécutions réussies**, et les 19 tests de publication. Les sources applicatives, leurs tests, leurs dépendances et l’APK publié sont restés inchangés.

Les tentatives 1 et 2 du [rangement documentaire 40086156589](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40086156589) ont réussi la synchronisation des cinq releases et les tests de l’époque, puis refusé la suppression de la dernière branche. Le contrôle demandait un champ de PR absent de la réponse API. Les premières lectures renvoyaient un ancien format ; une nouvelle requête et les en-têtes de réponse ont permis d’identifier la différence. GitHub documente le [retrait de `merge_commit_sha` dans l’API 2026-03-10](https://docs.github.com/en/rest/about-the-rest-api/breaking-changes?apiVersion=2026-03-10). La première hypothèse de simple délai de propagation n’expliquait donc pas le refus persistant.

Le correctif conserve la recherche bornée et l’unicité de la PR fermée, puis vérifie ses détails (`merged` vrai, numéro, références, dépôts et tête exacte). Il exige ensuite que le commit de `main` possède exactement deux parents Git, dont le second est la tête documentaire à archiver. La validation Android de ce même commit, la relecture de la tête après attente et les contrôles d’archive et de concurrence restent obligatoires. Aucun champ retiré ni abaissement de version d’API ne sert de remplacement.

Les fixtures reproduisent le format 2026 sans le champ supprimé. Deux cas positifs ont reproduit le refus avec l’ancien workflow. Les sept méthodes de test antérieures sont conservées et trois régressions sont ajoutées : confirmation indépendante des détails, refus des graphes incorrects et parcours API sans l’ancien champ. Les contrôles locaux du correctif ont passé **42 tests Python : 19 de publication, 13 de synchronisation et 10 de rangement**. Les réponses contradictoires, numéros invalides, mauvais dépôts, parents absents ou inversés, doublons et changements de tête restent des refus sans archivage.

Les **39 tests Python de la référence APK 0.4.0** en tête de ce document restent les effectifs réellement exécutés sur ses sources `32864ba`. Les 42 tests concernent l’outillage maintenu ensuite. La validation CI du correctif doit être lue sur sa demande de fusion et le commit correspondant de `main` ; les résultats d’un autre commit ne la remplacent pas. Cette maintenance ne change ni la version Android, ni le tag `v0.4.0`, ni le fichier APK et son empreinte.

## Historique local de la traduction — 24 octobre 2026

Le développement part de l’étape 6 `1942ba48ae3f9324b3c7d5b03ab8c9bf11463e09`.
La nouvelle application porte l’identité `fr.soustitresvoix.player.translation`,
version 0.4.0, code 6, avec une signature durable propre à cette édition et
sauvegardée hors du dépôt. Elle coexiste avec la stable 0.3.2.

Les passages Gradle locaux ont réellement exécuté **87/87 tests du noyau et
137/137 tests JVM de l’application**, en debug puis en release, sans échec,
erreur, méthode ignorée, manquante, inattendue ou dupliquée. Les inventaires des
sources ont été comparés aux XML JUnit. Les compilations des deux applications
et de leurs APK de tests ont réussi. À ce stade local, les **82 méthodes instrumentées** étaient
compilées ; leur exécution Android a ensuite été attestée par les runs CI cités ci-dessus.

Les lint debug/release ont réussi avec **zéro erreur**, respectivement **49 et
50 avertissements** dans les rapports locaux. Les alertes comprennent les
recommandations KTX, les chaînes non externalisées, les constructeurs de vues,
les règles de sauvegarde et l’accessibilité d’un toucher, ainsi qu’une alerte de
version de dépendance supplémentaire en release. Aucune alerte n’est masquée
et aucune absence totale d’avertissement n’est revendiquée.

Le manifeste compilé release, les permissions, le certificat et l’alignement
ont été contrôlés. Certificat SHA-256 de l’édition Traduction :
`304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d`.
Ce certificat diffère volontairement de celui de la stable 0.3.2 ; les deux
identités d’application sont distinctes. Les clés privées ne sont pas versionnées.

La première tentative Gradle a révélé un Java 17 limité au JRE ; un véritable
JDK 17 a été installé avant la compilation, avec JDK 21 pour Robolectric.
Le premier passage complet des tests de traduction a ensuite trouvé une
omission réelle : une réplique déjà visible n’était pas réémise par un déplacement
à position identique. Le correctif réinjecte explicitement `player.currentCues`
dans la file en pause. Le test reproduisant l’échec, les deux cas de lecture sans
voix et de rescan divergent, puis le passage ciblé **12/12** et les suites
complètes ont réussi après correction.

Les revues ont aussi corrigé la séparation des clés LibreTranslate par serveur,
la persistance des erreurs du formulaire, l’annulation des aperçus à la fermeture,
la fermeture du transport en vol, la recherche temporelle bornée et le refus
entier d’une préparation vocale dont l’index diverge. Les anciens tests restent
présents. Les tests réseau n’utilisent aucun compte payant ni sous-titre personnel.

Ces contrôles ne prouvent pas la qualité sémantique d’une traduction, les droits
d’un compte API, la qualité acoustique d’une voix, les performances du OnePlus 15
ou TalkBack sur appareil. Les essais manuels et les résultats d’API réelles doivent
rester distingués des tests contrôlés. Les résultats CI et de publication sont
rattachés à leurs commits et exécutions réels en tête de ce document.

Le contrôle ciblé suivant a également reproduit une perte de la réplique courante
après la seule commande de piste audio. La réinjection est maintenant effectuée
pour ce cas, sans réutiliser une ancienne réplique après changement de piste texte.
Les callbacks du renderer pendant l’attente normale d’une traduction ne sont plus
présentés comme une erreur de correspondance. Le compte de la suite JVM augmente
de 135 à 137 ; les passages complets CI ont ensuite attesté ce nouvel inventaire.

Le mécanisme de publication a d’abord été soumis à 17 tests comportementaux hors réseau,
conservés dans `tools/test-publish-verified-release.py` et exécutés en CI.
Deux cas ont initialement échoué : changement concurrent de cible du brouillon et
création d’un tag incorrect pendant les téléversements. Après déplacement des
vérifications avant publication, les 17 cas passent. Deux tests supplémentaires de transport, décrits à la fin de ce document, portent ensuite cet inventaire à 19. L’API GitHub ne fournit pas
de transaction atomique contre toute modification concurrente entre la dernière
vérification et la publication ; aucune garantie absolue n’est revendiquée.

## Validation continue dans GitHub Actions

Le parcours demandé depuis le 27 octobre 2026 comprend deux phases. Le workflow [Android — compilation et régressions](.github/workflows/android.yml) s’exécute sur une proposition de fusion vers `main`, ainsi qu’à la demande. Une fusion exige sa réussite sur la tête exacte proposée. Le workflow [Construction après fusion](.github/workflows/release-build.yml) contrôle ensuite la provenance et reconstruit la release sans répéter les suites.

| Phase | Contrôles |
| --- | --- |
| Avant fusion, structure | XML, manifeste source, démonstration synthétique et absence de clé privée versionnée |
| Avant fusion, JVM | Un passage de tous les tests noyau et application release, avec inventaire exact des méthodes attendues |
| Avant fusion, compilations | APK debug/test debug et release/test release, lint debug/release |
| Avant fusion, Android 36 | Un passage de tous les tests instrumentés, installé et exécuté sur la paire d’APK release |
| Avant fusion, intégrité | Inventaires des méthodes, absence d’échec/erreur/test ignoré/manquant/dupliqué, manifestes, permissions, signatures, alignements, empreintes et conservation des preuves |
| Après fusion | Commit de merge à deux parents, validation réussie de la PR, même arbre/parents que la fusion testée, reconstruction de release et comparaison de son contenu applicatif hors signature |
| Publication | Signature durable, contrôles du fichier et de sa provenance, publication et vérification du téléchargement ; aucune recompilation ni répétition des suites dans cette phase |

La variante debug et son APK de tests sont construits ; l’instrumentation automatique porte sur la **variante release**. Les effectifs attendus sont versionnés dans le workflow et `tools/run-device-tests.sh`, puis confrontés à l’inventaire des sources et aux rapports réels. Aucun test antérieur n’est retiré pour retrouver un total. Les nombres attendus ne remplacent jamais les résultats de l’exécution concernée.

Les artefacts avant fusion sont :

- **Android-rapports-validation** : journaux, résultats JUnit, lint, manifestes, signatures, empreintes, diagnostics d’émulateur, commit source et archive des sources ; les preuves disponibles sont conservées même en cas d’échec.
- **SousTitresVoix-release-validation** : paire d’APK release testée, empreintes, certificat éphémère et commit source, produite après réussite des contrôles.

Après fusion, **SousTitresVoix-release-main** contient la nouvelle construction non signée et sa preuve ; **Release-preuves-apres-fusion** conserve les contrôles de provenance et de contenu. La démonstration FFmpeg est déterministe et les métadonnées VCS variables sont exclues de l’APK ; le commit reste conservé dans les preuves séparées.

La signature CI est **éphémère** : l’APK de validation ne remplace pas la distribution signée avec la clé durable. Les artefacts Actions ont une conservation limitée ; le [README](README.md) pointe vers l’APK effectivement distribué. Les passages doubles des anciennes versions restent décrits dans leur historique : ils ne représentent plus le parcours courant.

## Validation locale

La compilation Android utilise le JDK 17. Les tests Robolectric avec Android 36
utilisent un processus JDK 21. Installer les deux JDK et les rendre disponibles
aux toolchains Gradle, par exemple avec `JAVA_HOME_17_X64` et `JAVA_HOME_21_X64`.
Il faut également le SDK Android 36, Build Tools 36.0.0, Gradle 8.13 et FFmpeg
pour produire uniquement la démonstration synthétique.

Exécuter la validation complète depuis la racine :

```sh
gradle --no-daemon :core:clean :app:clean
gradle --no-daemon --rerun-tasks --no-build-cache :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
python3 tools/check-project.py
python3 tools/test-publish-verified-release.py
python3 tools/test_sync_releases.py
python3 tools/test-documentation-cleanup.py
```

Contrôler ensuite chaque rapport JVM contre les méthodes attendues :

```sh
python3 tools/verify-test-results.py --reports core/build/test-results/test --expected-count 178 --sources core/src/test/kotlin --output build/verification/local-debug-core-summary.json
python3 tools/verify-test-results.py --reports app/build/test-results/testDebugUnitTest --expected-count 418 --sources app/src/test/java --output build/verification/local-debug-app-summary.json
```

Pour une preuve finale, fournir aussi `--not-before-ns` avec l’instant de début
du passage, comme le workflow CI, afin de refuser les XML périmés. Après la
variante release, utiliser `testReleaseUnitTest` et conserver séparément les
XML debug avant de relancer le noyau.

Les tests `app/src/test` emploient les vraies classes de l'application et les
parseurs Media3 épinglés. Les petits ASS et MKV sous `app/src/test/resources/ssa`
sont des fixtures synthétiques. Le helper `RealCueTimeline` donne accès au vrai
résolveur Media3 depuis son package ; il ne le remplace pas par un faux.

Les régressions ASS comparent les requêtes vocales à des textes et ponctuations
attendus littéralement. Un événement continu garde son identité entre les groupes
d’affichage ; des événements source distincts restent distincts, même si leur
texte est identique. Les essais couvrent aussi l’indexation, Confort, Adaptatif,
le buffering, la pause, la reprise et les callbacks périmés.

Le test JVM de copie du diagnostic utilise un shadow limité à la métrique
`ActivityManager.getProcessMemoryInfo`, absente de Robolectric 4.17. Cette valeur
mémoire est synthétique ; le fichier, l’indexeur, l’activité et le presse-papiers
suivent leur vrai parcours. Le test instrumenté correspondant n’utilise aucun
shadow. Ne pas interpréter cette précondition JVM comme une mesure Android.

Le scénario de diagnostic de l'étape 2 emploie aussi ce shadow PSS sur la JVM.
Ses proxies délèguent au vrai lecteur Media3 et enregistrent les commandes aux
frontières des composants ; la voix y est contrôlée. Le scénario Android utilise
le vrai service vocal de fixture et une action IPC dédiée observant **toutes**
les demandes de synthèse. Deux essais vocaux explicites, avant et après les
consultations, vérifient que cette observation fonctionne. L'entrée dans les
réglages garde sa pause existante ; les assertions de stabilité commencent
ensuite, lorsque le volume du film est réellement revenu au niveau demandé.
La fin de la phrase et une barrière du thread ExoPlayer ne suffisent pas à
attendre les callbacks différés du fondu de volume. Les assertions portent sur
afficher, actualiser et copier le diagnostic.

Sur Android API 28 et suivantes, le scénario vide le presse-papiers et vérifie
son absence de contenu avant les consultations, puis à nouveau avant la copie
explicite. Sur les API 26 et 27, où `clearPrimaryClip` n'existe pas, une sentinelle
distincte du diagnostic conserve la même vérification de copie fraîche. Une
sentinelle copiée sur Android récent peut afficher la vignette du presse-papiers
système : le journal d'un échec montrait le clic « Actualiser » suivi de
l'ouverture de `com.android.systemui/.clipboardoverlay.EditTextActivity`, mettant
l'activité du lecteur en pause. Le test évite cette vignette artificielle, sans
désactiver les fonctions du système. Le contenu, le libellé et les observations
du diagnostic réellement copié restent vérifiés avant le nettoyage du
presse-papiers ; ce nettoyage ne garantit pas la fermeture d'une vignette déjà
affichée par la copie réelle.

Dans ce test instrumenté, les dialogues se ferment par leurs boutons et avec
une racine Espresso explicitement limitée aux dialogues. Deux fermetures Retour
successives sans racine explicite pouvaient sélectionner l’activité sans focus.
Les fermetures utilisent des scopes `Closeable.use` imbriqués : toutes les
assertions restent actives, les deux nettoyages sont tentés et une éventuelle
erreur de fermeture est attachée à l’échec initial au lieu de le remplacer.

### Contrôles des commandes d’image de l’étape 3

Les quatre tests de `ImageCommandsTest` doivent couvrir les bornes inclusives, les valeurs non
finies, les refus atomiques, l’ordre des protections, les tickets de génération,
la fermeture, la disponibilité, le HDR, la comparaison et l’idempotence. Ils
doivent distinguer une acceptation d’une soumission de plan : valider une demande
ne remplit jamais une observation du rendu.

Les cinq tests de `ImageCommandsRobolectricTest` doivent exercer le vrai port
raccordé à `VisualTools`, le plan effectivement envoyé à la sortie vidéo et les
contrôles existants. Les ports de lecture, d’audio et de sources délèguent aux vrais
composants ; la voix active est contrôlée sur la JVM. Ils doivent vérifier les
refus sans mutation ni interruption de benchmark, les demandes identiques,
le regroupement des demandes, la protection HDR et le bypass, l’invalidation au
remplacement du média et à la fermeture, ainsi que les événements de format
anciens ou non attribuables. Les oracles conservent la séparation entre valeurs
demandées et dernière soumission ; aucune valeur supposée ne remplace un envoi
absent. Les contrôles de narration, d’audio et de transport doivent rester actifs,
y compris le maintien de la file et de l’identifiant vocal, puis le refus d’un
callback périmé après une pause. Un ancien dialogue d’image doit présenter le
refus de génération et restaurer sa case de comparaison.

Le scénario `ImageCommandsAndroidTest` doit emprunter la vraie activité, Media3
et le service TTS de fixture, avec les boutons réels de contraste, de luminosité
et de comparaison. Deux essais vocaux explicites encadrent les commandes ; la
trace IPC doit contenir ces deux demandes seulement. La pause, la position, le
volume et les préférences font partie de l’oracle de stabilité. Aucun nouvel
oracle de pixels n’est ajouté. Les tests antérieurs de pixels, de cadrage, de pause,
de reprise et de conservation des occurrences restent requis. Une réussite du
contrat ou de son raccordement ne mesure ni la qualité d’un effet sur l’appareil
cible, ni le rendu HDR réel, ni les performances d’un autre moteur.

Ces contrôles s’ajoutent aux exigences complètes du tableau initial. Les rapports
doivent établir les effectifs et les résultats du commit testé ; aucun résultat
des étapes 1 ou 2 ne vaut validation de l’étape 3.

### Contrôles de l’interface d’image de l’étape 4

Les sept scénarios ajoutés dans `ImageCommandsRobolectricTest` doivent exercer
les vrais contrôles, avec les composants de lecture délégués au vrai lecteur
et une voix contrôlée. Les cinq scénarios antérieurs restent requis.

| Scénario | Oracle requis |
|---|---|
| Pas fins et bornes | Atteindre les bornes inclusives des deux corrections sans dérive, refuser le pas suivant et conserver une phrase active, sa file, le transport et l’audio. |
| Saisie précise | Accepter point, virgule et valeurs intermédiaires comme 0,125 ; conserver cette précision lors d’un pas vers 0,135 ; refuser texte, valeurs hors bornes et notation non autorisée, sans filtrage silencieux ni écrêtage. |
| Demandes et soumissions | Distinguer une valeur conservée d’un plan effectivement transmis, y compris sous comparaison ; les remises à zéro ne touchent que les corrections visées. |
| Protections | Montrer les raisons HDR, capacité inconnue ou indisponible ; conserver les réglages refusés et restaurer la comparaison. |
| Générations et fermeture | Garder le ticket d’origine d’une saisie ouverte avant remplacement du média ; fermer les dialogues et empêcher une commande tardive après fermeture. |
| Aides et grandes polices | Vérifier la présence des descriptions et des libellés, les dimensions des contrôles, le défilement et la consultation sans commande dans une configuration de police agrandie. |
| Commandes de cadrage et d’écran | Conserver le ticket du dialogue et d’une confirmation de recadrage ; refuser les commandes périmées et vérifier les consignes écran, dont le premier appui plus depuis le système à 60 %. |

Le scénario Android ajouté dans `ImageCommandsAndroidTest` doit saisir les valeurs
par les vrais contrôles, constater une erreur persistante sans changement,
modifier la comparaison, remettre les corrections à zéro et attendre leurs
soumissions réelles. Deux essais vocaux explicites doivent encadrer ces actions ;
la trace IPC du service de fixture ne doit contenir que ces deux requêtes. La
comparaison de l’état commence après le retour réel du volume du film au niveau
demandé. Pause, position, choix de pistes, préférences et luminosité de fenêtre
restent dans l’oracle. Le premier scénario Android d’image reste exécuté.

Après une soumission d’effets, attendre aussi son traitement sur le thread de
lecture, une notification de première image postérieure au changement lorsque
le plan transmis est nouveau, puis le retour borné à `READY`. Le compteur doit
être installé avant les actions et relevé avant chaque demande. Le renderer
existant peut temporairement faire passer Media3 en `BUFFERING` lorsqu’il vide
sa sortie vidéo. Pendant cette attente, les autres invariants et les demandes vocales restent contrôlés ;
l’état complet, y compris `READY`, doit être identique à la référence à la fin.
Ne pas supprimer l’état de préparation de l’oracle ni accepter un blocage. Les
clics restent des interactions visibles, précédées d’un défilement si les
textes d’état ont déplacé le contrôle.

Les vérifications de grandes polices et des propriétés d’accessibilité sur la
JVM ne constituent pas un essai TalkBack. Les captures de la démonstration
synthétique documentent une interface observée ; elles ne prouvent pas à elles
seules l’intégralité du défilement, le rendu HDR ou le confort sur téléphone.
Les dix fichiers sensibles de narration, de sous-titres, d’audio et du renderer
doivent rester identiques au point de retour de l’étape 3. Toute limite ou
correction découverte pendant la validation doit rester attribuable au passage
et au commit concernés, avec les échecs intermédiaires conservés.

### Contrôles des commandes audio de l’étape 5

Les six tests de `AudioCommandsTest` doivent vérifier les bornes inclusives des
volumes et des durées, les valeurs non finies, les refus atomiques, les groupes
de paramètres indépendants, la priorité des tickets périmés et de la fermeture,
les valeurs identiques et le zéro signé. L’activation du filtre dépend de sa
capacité déclarée ; sa désactivation reste possible. Une validation pure ne doit
modifier aucun état ni prétendre mesurer un signal sonore.

Les onze tests de `AudioCommandsRobolectricTest` exercent le vrai port de session,
avec Media3 et une voix contrôlée. Les tests historiques de volume, de gestes,
de diagnostic et de composition restent requis.

| Scénario | Oracle requis |
|---|---|
| Phrase active et file | Modifier les groupes audio en conservant l’identifiant vocal, les demandes déjà émises, les textes en attente et le transport. |
| Refus et absence de changement | Ne modifier ni préférences, ni volume, ni filtre, ni benchmark, ni fondu lorsque la commande est invalide ou identique. |
| Baisse, maintien et remontée | Utiliser les durées non nulles et vérifier les valeurs intermédiaires puis la cible ; un volume explicite annule les anciens callbacks sans redémarrer la voix. |
| Changement de durée | Conserver la transition déjà planifiée et prendre la nouvelle durée lors de la suivante. |
| Réplique proche | Conserver la prolongation existante de l’attente entre deux sous-titres ; changer la préférence ne reprogramme pas le maintien déjà engagé. |
| Remplacement et fermeture | Refuser un ancien ticket après réouverture, conserver les préférences audio et ne pas consulter le lecteur libéré lors d’un instantané fermé. |
| Pause, déplacement et débranchement | Empêcher une commande audio et un ancien callback vocal de reprendre la lecture. |
| Consultation et préférences invalides | Lire sans commande et utiliser un défaut fini pour une préférence de volume non finie. |
| Contrôles réels | Modifier et réinitialiser les six réglages, respecter les bornes, expliquer les refus et restaurer la case du filtre refusée. |
| Grande police et durée de vie | Vérifier le défilement, les explications et les contrôles ; consulter sans mutation et refuser une action depuis une activité détruite. |
| Réglages généraux périmés | Refuser un curseur issu d’une ancienne ouverture, rétablir sa valeur conservée et afficher la raison dans le dialogue, sans changer le mode plein écran ni préparer une voix. |

Les deux scénarios de `AudioCommandsAndroidTest` doivent utiliser la vraie
activité, Media3 et le service vocal de fixture. La trace IPC doit recenser
exactement les demandes attendues ; l’absence de perte de sous-titre, la
conservation des répétitions réelles et le comportement des callbacks périmés
restent contrôlés. Le parcours des réglages passe par les contrôles visibles et
leur défilement normal. Une compilation de ce fichier ne valide pas son
exécution : les deux passages complets des 73 tests Android restent nécessaires.

Les instantanés audio distinguent les valeurs demandées, le volume configuré au
lecteur et les signaux du processeur. Le compteur PCM cumule les échantillons de
canaux, y compris le passage sans atténuation ; son augmentation et un filtre
activé ne prouvent pas une atténuation audible. Les tests du processeur PCM
existants restent exécutés. Les dix fichiers sensibles doivent rester identiques
au point de retour validé de l’étape 4. Aucun résultat ne constitue un essai de
TalkBack, d’une voix externe ou du son physique du téléphone cible.

#### Premier passage ciblé du 23 octobre 2026

Les 50 tests du noyau ont réussi. Les quatre classes ciblées de l’application
ont exécuté 17 méthodes : 16 réussites et un échec dans le nouveau scénario de
grande police. Ce test sélectionnait un unique `ScrollView` dans toute la fenêtre
`AlertDialog`, qui en contient plusieurs. La sélection vise désormais le
panneau audio défilant identifié par son contenu, tout en conservant les
assertions de lisibilité, d’accès et d’absence de mutation. Les rapports de cet
échec restent conservés ; une nouvelle validation complète doit porter sur
la correction.

Deux méthodes d’interface avaient été ajoutées avant la compilation de
l’application, après le démarrage du lanceur ciblé configuré pour 15 méthodes.
Son contrôle initial a donc aussi refusé le total 17. L’audit des XML bruts a
retrouvé exactement les 17 identités présentes dans ces quatre classes, sans
méthode absente ni dupliquée, avec le même échec réel de défilement. Ce passage
ciblé ne constitue pas une validation réussie de l’étape.

#### Premier passage complet local du 23 octobre 2026

Après cette première correction, les 50 tests du noyau ont de nouveau réussi.
Les 63 tests de l’application ont tous été exécutés, avec 62 réussites et un
échec du même scénario de grande police : la collecte des boutons de toute la
fenêtre incluait les boutons négatif et neutre du gabarit Android, masqués et
sans texte dans ce dialogue. L’oracle porte désormais sur les contrôles du
panneau audio, y compris sa case du filtre, et conserve les vérifications de
libellé, de focus, de dimensions et de texte non tronqué. Les sources n’ont pas changé
pendant ce passage et les dix fichiers sensibles sont restés identiques au
point de retour. Gradle s’est arrêté avant la production du rapport lint et
la fin de la compilation ; ce passage n’établit donc pas une réussite du lint
ou des APK.

#### Correction identifiée pendant la revue de l’interface générale

La revue du code a identifié un problème distinct des deux échecs de sélection
du test de grande police : le refus d’une commande depuis les réglages généraux
utilisait le message global de l’activité. Celui-ci pouvait rester derrière le
dialogue et faire quitter le plein écran. Le refus est présenté dans le dialogue
lui-même, avec une zone perceptible et défilante, et la valeur du contrôle est
restaurée. Le parcours audio évite également le rappel général d’actualisation,
qui peut déclencher une préparation vocale. Un onzième scénario JVM vérifie le
parcours réel des deux curseurs concernés après remplacement du média.
Cette découverte vient de la revue ; elle n’est pas présentée comme un échec
observé dans le premier passage Gradle. Les effectifs finaux attendus deviennent
50 tests du noyau, 64 tests de l’application et 73 tests Android.

#### Deuxième passage complet local du 23 octobre 2026

Les 50 tests du noyau ont réussi. Les 64 tests de l’application ont tous été
exécutés, avec 63 réussites et un échec. Les scénarios de grande police et de
refus des curseurs généraux ont réussi. Le scénario de maintien avant une
réplique proche a constaté un volume de 1,0 alors qu’il attendait encore 0,25 à
la milliseconde précédant l’échéance qu’il avait calculée. Les rapports bruts
conservent cette divergence. L’analyse a identifié une première faiblesse de
l’oracle : le test calculait son délai à partir d’une position lue auparavant,
alors que la fin de phrase réautorise la lecture avant de planifier la remontée.
La position réellement lue par le mixage pouvait donc avoir avancé entre les
deux calculs. Les traces de ce passage ne suffisent pas à attribuer à elles
seules l’échec à cette différence ; les vérifications suivantes sont décrites
ci-dessous.

Le scénario attend désormais la vraie pause Confort et une barrière du thread
Media3 avant d’installer le sous-titre proche. Il observe ensuite, sans les
modifier, les deux lectures réelles de position consommées lors de la
planification de la remontée après la fin de phrase. Ces entrées réelles
servent à calculer l’échéance attendue ; les assertions juste avant cette
échéance et à son terme restent inchangées. Le journal JUnit conserve la
position de préparation, les positions consommées et le délai attendu. Aucun
retard arbitraire, nouvelle tentative ni changement de la règle audio n’est
introduit pour faire réussir le scénario. Les sources sont restées stables
pendant le passage en échec. Le rapport lint n’a pas été produit avant l’arrêt
de Gradle : ce passage ne le valide pas.

Le lanceur local utilise ensuite `--continue` pour laisser se terminer les
tâches indépendantes de lint et de compilation même si une suite échoue. Il
exige toujours un code de sortie global nul, la réussite de chaque contrôle
et les identités exactes de tous les tests ; cette option ne transforme aucun
échec en réussite et les passages précédents restent conservés séparément.

#### Troisième passage complet et sondes de diagnostic du 23 octobre 2026

Avec `--continue`, les 82 tâches nécessaires ont été exécutées. Les 50 tests du
noyau ont réussi ; les 64 tests de l’application ont été exécutés avec 63
réussites et un échec du scénario de réplique proche. Ce nouvel échec intervient
avant la vérification de l’échéance : une seule lecture de position a été
observée au lieu des deux attendues. L’anticipation n’avait donc pas trouvé
l’entrée proche attendue dans ce parcours. Les règles audio de production
n’ont pas été modifiées pour adapter ce scénario.

Une trace de diagnostic a été ajoutée avant cette assertion pour conserver la
clé de piste indexée, la piste courante, les positions réellement lues et le
contenu temporel de l’index. Une sonde limitée à cette méthode a réussi, puis
une sonde unique de ses onze méthodes de classe a réussi. Dans les deux cas,
la piste était déjà `external-subtitle`, les positions lues étaient `[0, 0]`,
le sous-titre proche commençait à 900 ms et le maintien attendu était de
1 050 ms. **Ces deux sondes n’ont pas reproduit l’échec précédent et ne
prouvent donc pas à elles seules sa cause.** Elles ne remplacent pas une
validation complète.

Le helper de préparation attend désormais explicitement la sélection de
`external-subtitle` lorsqu’un fichier externe est fourni. Auparavant il
acceptait toute piste de texte non nulle, qui pouvait encore être une
sélection provisoire avant le choix asynchrone du fichier externe ; ce n’est
pas une précondition suffisante pour indexer un futur sous-titre sur la bonne
piste. Le scénario conserve la barrière après la pause Confort, l’observation
sans substitution des lectures réelles et les assertions exactes juste avant
et à l’échéance. Les traces restent dans les XML pour rendre un éventuel
nouvel échec analysable ; aucun résultat de sonde n’est substitué à un passage
complet en échec.

Les deux APK debug ont été compilés pendant le troisième passage. Le rapport
lint comportait 58 avertissements et un indice, contre 49 avertissements et un
indice au point de retour : dix nouveaux signalements ont été refusés, tandis
qu’un ancien avait disparu. Ils concernaient neuf constructions de texte dans
les deux panneaux audio et la nouvelle écriture groupée des préférences. Les
textes sont désormais placés dans des ressources avec paramètres ; l’écriture
groupée utilise l’extension KTX tout en gardant une seule application des
préférences. Les anciens signalements sur les mêmes lignes du panneau rapide
sont aussi corrigés. Aucune règle lint n’est ignorée. La compilation des APK
n’efface ni l’échec JVM ni ces ajouts : le troisième passage reste refusé.

#### Quatrième passage complet et contrôle lint final du 23 octobre 2026

Le quatrième passage complet a exécuté 82 tâches Gradle : les **50 tests du
noyau et les 64 tests de l’application ont tous réussi**, sans erreur, test
ignoré, manquant, inattendu ou dupliqué. Les identités ont été comparées aux
sources. Le scénario de réplique proche a observé la piste externe attendue,
les positions `[0, 0]` et le maintien de 1 050 ms ; les deux assertions de part
et d’autre de son échéance ont réussi.

Les APK debug de l’application et de l’instrumentation ont été compilés ; le
manifeste de l’application, ses permissions, les deux signatures et leurs
alignements ont été vérifiés. Les sources sont restées identiques pendant ce
passage et les dix fichiers sensibles correspondent au point de retour de
l’étape 4. L’inventaire trouve 73 méthodes Android, mais leur compilation et
cet inventaire ne constituent pas une exécution sur Android.

Le contrôle comparatif de lint a encore refusé un nouveau signalement `Typos` :
le dictionnaire interprétait le mot français « existant » dans une description
comme une faute anglaise. Les dix ajouts du passage précédent avaient disparu,
ainsi que trois avertissements historiques au total. Le libellé a été simplifié
en « Volume de la voix : », sans ignorer la règle lint ni changer un algorithme.
Le quatrième passage complet reste donc distingué du contrôle suivant.

Après cette seule reformulation, un **contrôle ciblé de lint et des ressources**
a réussi : **46 avertissements et un indice, aucun ajout par rapport au point
de retour, trois anciens signalements retirés**. Les 155 fichiers source
inventoriés sont restés stables pendant ce contrôle. Aucun test n’a été
réexécuté dans ce passage ciblé et aucune nouvelle compilation d’APK n’y est
revendiquée. La CI doit vérifier le commit final complet, avec les variantes
debug et release puis les deux passages instrumentés ; ses rapports doivent
rester liés au commit exact avant de conclure à la validation Android.

Les tests JVM ne remplacent pas l'instrumentation. Sur un émulateur Android
démarré, exécuter séparément `bash tools/run-device-tests.sh`. Les tests du
moteur vocal utilisent leur propre service de synthèse de fixture ; une réussite
ne prouve pas la qualité ni les performances d'un moteur externe sur un téléphone.

Conserver les résultats JUnit, le rapport lint, les journaux d'instrumentation et
les empreintes des APK testés. Une compilation réussie de l'APK de tests ne
constitue pas une exécution réussie de ces tests.

Lors d’une nouvelle validation complète, contrôler que les tâches de test ont
réellement été exécutées et que les timestamps des XML correspondent à ce
passage. Ne pas compter des rapports anciens, ni une tâche `UP-TO-DATE` ou
`FROM-CACHE`, comme une nouvelle exécution. Une sonde isolée en échec reste une
preuve distincte de la suite permanente ; son ancien XML ne doit pas être
additionné aux résultats d’un autre passage.

### Contrôles de l’audio expert de l’étape 6

Le point de retour est le commit `647de93e99ef778cfa3199f3c225e1fc0b759baf`,
conservé sur `backup/etape-5-validee-20261023-647de93`. La branche de travail
de cette étape conserve les dix fichiers sensibles de narration, sous-titres,
traitement spectral et rendu vidéo identiques à ce point de retour. Le nouveau
DSP est un composant séparé ; ses préférences concernent exclusivement le film.

Les contrôles supplémentaires exigés sont les suivants :

| Groupe | Méthodes nouvelles | Propriétés vérifiées |
|---|---:|---|
| Saisie numérique pure | 6 | Décimaux exacts avant conversion, virgule/point, bornes, syntaxe entière des durées, valeurs invalides, affichage. |
| DSP pur | 18 | Bypass des 65 536 valeurs PCM16, préampli analytique, égalisation sur sinusoïdes, Q, annulation boost/cut, Nyquist, mono/balance, canaux indépendants, stabilité, plafond lié stéréo, relâchement, transitions et rafales. |
| Commandes DSP | 3 | Refus atomique, capacités, fermeture/ticket, groupes indépendants et demandes identiques. |
| Saisie numérique JVM | 7 | Vrais champs et boutons, cinq réglages historiques, refus persistant, annulation, champs compagnons, ticket initial, durée de vie et grande police. |
| Interface DSP JVM | 8 | Cinq bandes, nombres et cases, réinitialisations, erreurs, capacités, observations sans mutation, panneaux imbriqués et grande police. |
| Adaptateur PCM JVM | 9 | Vrais AudioProcessor et pipeline Media3, frames partielles, configure différé, flush/reset, bypass exact, mono/stéréo, formats non traités, révisions et publications concurrentes. |
| Intégration session JVM | 4 | Phrase/file conservées, seul processeur installé commandé, refus/no-op sans mutation, persistance exacte, repli désactivé des préférences corrompues et empreinte du banc d’essai distinguant chaque paramètre DSP. |
| Android | 2 | Traitement de PCM effectivement décodé par Media3 depuis un WAV stéréo synthétique ; vrais éditeurs numériques et panneaux DSP avec erreurs, annulation, refus après réouverture et absence de synthèse vocale supplémentaire. |

Le scénario ASS existant de l’étape 5 modifie aussi le DSP pendant chaque
occurrence réellement reçue par le service TTS de test. Il conserve les mêmes
six textes attendus, les répétitions légitimes, les identifiants distincts et
les vérifications de terminaison. Les contrôles de pause, de déplacement,
de reprise et de callbacks périmés restent présents.

Les essais de DSP utilisent des signaux synthétiques et des comparaisons de
valeurs numériques, pas une évaluation subjective de qualité sonore. Le test
Android de PCM observe la sortie quantifiée du processeur installé et vérifie
préamplification, limitation et retour au bypass. Ces observations ne prouvent
pas la crête acoustique après les autres traitements, le DAC ou le casque.
L’émulateur et la voix déterministe de test ne remplacent pas une vraie voix,
TalkBack, Bluetooth ou des essais prolongés sur le téléphone cible.

Les cinq captures nouvelles attendues à chaque passage Android sont
`expert-audio-precise-invalid`, `expert-dsp-overview`, `expert-dsp-band`,
`expert-dsp-observations` et `expert-dsp-stale-editor`. Elles complètent les
six captures d’image et d’audio déjà attendues par passage ; elles montrent
uniquement les portions et états capturés, pas tous les contrôles simultanément.

#### Premiers contrôles ciblés du 23 octobre 2026

Le premier lancement local du noyau s’est arrêté avant toute tâche de test,
car l’environnement restreint refusait les sockets locales requises par
Gradle. Les anciens XML ont été identifiés comme périmés et n’ont pas été
comptés. L’exécution avec la permission système nécessaire a ensuite lancé
les 77 méthodes : 76 réussites et un échec d’oracle sur le zéro signé.

L’expression du test `-0.0.toRawBits()` appliquait le moins au résultat entier
de `toRawBits()`, au lieu d’interroger le flottant négatif. Elle a été corrigée
en `(-0.0).toRawBits()`. Les assertions de normalisation précédentes réussissaient ;
aucun algorithme DSP n’a été changé pour contourner ce test. La relance ciblée
a réussi **77/77**, sans méthode ignorée ou manquante et avec sources du noyau
inchangées pendant ce passage. Ce résultat ne constitue pas une validation
des interfaces Android, du lint, des APK ou de la future version finale.

Le premier passage ciblé de l’application a compilé les sources de production
et les 75 méthodes instrumentées, puis exécuté 38 méthodes JVM : 35 réussites
et trois échecs. Le compteur d’écrêtage de l’adaptateur comptait certains
écarts flottants avant arrondi comme des saturations, alors que le résultat
PCM16 arrondi restait représentable. Il compte désormais les valeurs non
finies et les valeurs arrondies réellement hors plage ; les cas situés à
un quart et trois quarts de pas au-delà des rails sont vérifiés séparément.
Le plafond du limiteur et la vérification d’absence d’écrêtage restent exigés.

Un test d’annulation vérifiait la fermeture du bouton standard `AlertDialog`
avant le traitement de son message par le looper Robolectric en mode
`PAUSED`. Il attend désormais ce traitement, avec l’assertion de fermeture
et l’absence de commande inchangées. Le troisième échec concernait la
géométrie du dialogue en grande police. Une première correction de l’oracle
a ajouté une passe explicite de mesure et de positionnement dans une fenêtre
de 320 × 480 dp ; elle n’a pas résolu cet échec.

Le deuxième passage ciblé a exécuté les mêmes 38 méthodes : **37 réussites
et un échec de grande police**. Les neuf méthodes de l’adaptateur et le cas
d’annulation ont réussi ; la hauteur du contenu et celle du viewport étaient
toutes deux de 286 dans l’oracle restant. Les sources sont restées inchangées
pendant chacun des passages. Aucun de ces deux passages ne constitue une
validation complète ; l’échec de géométrie doit être expliqué et corrigé avant
la validation finale. Les journaux et résultats bruts sont conservés séparément.

Une sonde de la seule méthode de grande police a ensuite employé les métriques
de texte natives de Robolectric et le thème de l’application. Elle a démontré
un texte effectivement agrandi et défilant : 17 lignes d’aide, contenu de
2 885 pixels et viewport de 265 pixels. Elle a ensuite échoué sur un autre
oracle : `minimumHeight` désigne la propriété de `View`, alors que le champ
configure `minHeight` de `TextView`. Le test contrôle désormais cette propriété
et la hauteur réellement mesurée, sans supprimer l’exigence de 56 dp.

Cette analyse a aussi révélé un défaut de production distinct : l’appel à
`setSingleLine(true)` rétablissait une contrainte d’une ligne après la définition
du minimum en pixels. Le minimum de 56 dp est maintenant fixé après cet appel.
La sonde précédente reste en échec ; cette correction et les oracles renforcés
doivent réussir lors du passage suivant. Aucun comportement vocal n’est modifié.

La revue du scénario Espresso a aussi identifié un défilement inadapté au
rapport audio, plus haut que sa fenêtre. L’action révèle maintenant son début
par un rectangle borné ; l’assertion de visibilité et la vérification du texte
complet restent présentes. Cette correction est issue de la revue de code,
pas d’un échec instrumenté déjà observé. Le test PCM Android vérifie le retour
à la crête de référence ; la copie exacte de toutes les valeurs PCM16 est
établie séparément par les tests du noyau et de l’adaptateur.

Une autre revue a identifié l’absence des nouveaux paramètres DSP dans
l’empreinte des réglages du banc d’essai. Le groupe canonique complet est
désormais inclus : deux essais dont le DSP diffère ne sont plus considérés
comme exécutés avec les mêmes réglages. Une méthode de régression vérifie
chaque champ, les préférences latentes, les demandes identiques, la restauration
exacte et le zéro signé. Elle ne lance pas un essai de performance synthétique
et ne mesure pas un gain de vitesse. Cet ajout porte l’effectif JVM de
l’application de 91 à 92.

#### Contrôle ciblé de la grande police et passage complet local

La seconde sonde native de grande police a réussi, **1/1**, avec la méthode
attendue et des sources stables. À une densité de 1 et une police agrandie
à 200 %, le champ mesure 67 pixels pour un minimum configuré de 56 pixels ;
l’aide occupe 17 lignes et le contenu de 2 885 pixels défile dans un viewport
de 265 pixels. Les assertions de retour à la ligne, de défilement, d’absence
d’ellipse, d’erreur persistante et d’absence de commande ont toutes réussi.

Le premier passage local complet de l’étape 6, après nettoyage, a exécuté
82 tâches Gradle et réussi **77/77 tests du noyau et 92/92 tests de
l’application**. Les rapports ont été contrôlés contre l’inventaire exact
des méthodes : aucun échec, erreur, test ignoré, manquant, inattendu ou dupliqué.
Les 176 fichiers inventoriés sont restés inchangés pendant ce passage et les
dix fichiers sensibles correspondent octet pour octet au point de retour
de l’étape 5.

Le rapport lint debug contient **46 avertissements et un indice**, exactement
les mêmes que ceux du point de retour : aucun ajout ni retrait. Les deux APK
debug ont été compilés ; le manifeste, les permissions, les signatures et
les alignements ont été vérifiés. Les **75 méthodes Android sont compilées
et inventoriées**, mais ne sont pas présentées comme exécutées dans ce passage
local. La documentation est ensuite complétée avec ce compte rendu ; la CI
doit valider le commit final, ses variantes debug/release et les deux passages
instrumentés avant de conclure à une validation Android.

Les autres résultats, les éventuelles corrections et les preuves finales
doivent identifier le commit exact de la proposition dans GitHub Actions.
Les anciens résultats de l’étape 5 ne sont pas une preuve des sources de cette étape.
La demande de l’utilisateur réserve la fusion et la livraison à une instruction
ultérieure ; cette validation ne vaut pas publication d’une application.

#### Premier passage CI complet et revue des consignes

Le commit `ba7cbd34eca7c956ffa32487826481d68b80e819` a réussi le
[run 39946746215](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/39946746215),
job `120391148255`, terminé le 23 octobre 2026 à 11:52 UTC. Les XML bruts
confirment 77 tests noyau et 92 tests application en debug, les mêmes effectifs
en release, puis deux passages des 75 tests Android release sur AOSP 36 x86_64 :
**488 exécutions réussies, 244 méthodes distinctes**, sans échec, erreur, test
ignoré, manquant, inattendu ou dupliqué. Les 187 méthodes de l’étape 5 sont
conservées ; cette étape en ajoute 57.

Les 176 fichiers de sources archivés correspondent exactement au commit de
travail. Les dix fichiers protégés sont identiques à l’étape 5. Le lint debug
et release contient, pour chaque variante, les mêmes 46 avertissements et un
indice, sans nouvelle alerte. La paire release conserve ses empreintes avant
et après les deux passages ; les preuves de manifeste, de permissions, de
signature et d’alignement ont été contrôlées. L’archive de rapports, sans APK,
a pour SHA-256
`43f41ab6425c9ede91d971a97f030d6c794ee194ae52f673e888eba52c6c8bbe`.

Les 22 captures attendues sont présentes en 1080 × 1920. La revue visuelle des
dix nouvelles captures a toutefois relevé deux consignes ambiguës : le refus
d’un éditeur périmé demandait de fermer « ce panneau », alors que le panneau
parent conservait lui aussi son ticket ; l’aide des volumes situait leur
bouton de réinitialisation dans l’éditeur, qui ne propose que l’annulation et
l’application. Les textes sont précisés pour demander de fermer les fenêtres
de réglages jusqu’au lecteur avant de les rouvrir, et pour situer la
réinitialisation dans le panneau de réglages audio. Les assertions existantes
contrôlent ces consignes, sans changer la validation des tickets ni le parcours
audio ou vocal.

Le passage local ciblé de cette correction a réussi **30/30 méthodes** :
15 d’intégration des commandes audio, 7 de saisie numérique et 8 d’interface
DSP. Les XML ne contiennent aucun échec, erreur, test ignoré, manquant,
inattendu ou dupliqué ; les sources surveillées sont restées inchangées.
La méthode native de grande police réussit aussi avec les descriptions
complétées. La compilation des tests Android réussit ; elle ne constitue pas
leur exécution. Ce passage a exécuté 37 tâches en 55 secondes.

Cette correction textuelle doit encore être validée par la CI sur son propre
commit. Le succès précédent reste attaché à
`ba7cbd34eca7c956ffa32487826481d68b80e819` ; le bilan
de la PR doit identifier la nouvelle exécution avant de déclarer le dernier
état validé. Les captures ne remplacent pas les tests d’interaction, l’écoute
sur appareil ni une vérification TalkBack.

## APK signé pour installation

L’identité d’installation de l’édition Traduction 0.4.0 est
`fr.soustitresvoix.player.translation`. La stable 0.3.2 utilise séparément
`fr.soustitresvoix.player.stable` ; une signature différente ne permet pas de la
mettre à jour avec l’APK 0.4.0. Conserver la clé privée durable de l’édition
Traduction hors du dépôt pour permettre ses prochaines mises à jour.
La variable `SUBTITRES_SIGNING_PROPERTIES` désigne un fichier externe contenant
`storeFile` (chemin du PKCS12), `storePassword`, `keyAlias` et `keyPassword`.
Ne jamais versionner ni publier ce fichier ou la clé.

Après la validation debug, compiler et tester aussi la variante distribuée :

```sh
gradle --no-daemon :core:clean :app:clean
gradle --no-daemon --rerun-tasks --no-build-cache -PverifyRelease=true :core:test :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease :app:assembleReleaseAndroidTest
gradle --no-daemon -PverifyRelease=true :app:connectedReleaseAndroidTest
```

Vérifier ensuite le manifeste compilé, l'absence de service de fixture dans l'APK
principal, `debuggable=false`, sa signature avec `apksigner verify --verbose
--print-certs` et son empreinte SHA-256. Sans la variable de signature, Gradle
peut produire une variante release non signée : ce fichier ne doit pas être
présenté comme APK installable. Un APK de tests debug ne remplace pas l'APK de
tests release, même si les sources sont identiques.


### Premier double passage Android de la traduction — 24 octobre 2026

Le [run 40044448351](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40044448351), sur la proposition `7907385bc7db418acaf38d5cce5e669858f3c61a`, a réellement exécuté 82 méthodes à chaque passage : **81 réussites et un échec**, deux fois, sans erreur, test ignoré, manquant ou dupliqué. Les suites JVM debug et release avaient chacune réussi 87 tests du noyau et 137 de l’application. Aucun APK de distribution n’a été produit par ce run en échec.

L’échec unique est `actualTranslationPanelKeepsLabelsDescriptionsAndScrollableHelpAtDoubleFontScale`, à la recherche d’un unique `ScrollView` dans tout le dialogue. Android 36 ajoute un défilement interne au `AlertDialog` en plus de celui du formulaire : la sélection était ambiguë. Les contrôles précédents de taille de texte, d’explications fournisseurs, de labels et de clé masquée avaient passé. Le formulaire possède désormais l’identifiant explicite `translation_controls_scroll` ; le test cible ce formulaire, vérifie qu’il contient tous les champs et conserve ses assertions de dépassement et de défilement effectif.

La correction a réussi sur le commit `3804260de5071b6314674181d520607b084291b7` dans le [run 40046561370](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40046561370) : 612 exécutions JVM/Android réussies. La [PR #9](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/pull/9) a été fusionnée par un commit de fusion `2141c2024e7ba0fd2801a0dc0e2c86eebfbb29cf`, conservant l’historique des étapes précédentes.


### Correction du téléchargement GitHub avant publication — HTTP 415

La validation du commit `2141c2024e7ba0fd2801a0dc0e2c86eebfbb29cf` a réussi dans le [run 40048404597, tentative 2](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40048404597) : **612 exécutions JVM/Android**, puis contrôle et signature durable de l’APK exact. Le [premier essai de publication 40051373135](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40051373135) s’est ensuite arrêté avec HTTP 415 au téléchargement de l’artefact Actions, **avant création d’un brouillon, d’une release ou du tag 0.4.0**. Les quatre versions historiques sont restées en place.

La tentative 1 de ce run Android a été annulée par l’agent après une longue installation de FFmpeg, juste après sa fin et le début des contrôles debug. Cette annulation n’est pas présentée comme un échec des tests. La tentative 2 a repris et réussi le parcours complet, avec les 17 tests de publication alors présents. Cet APK candidat a ensuite été remplacé par celui du commit final `32864ba`, après correction et nouvelle validation des outils de publication.

Le transport utilisait `Accept: application/octet-stream` pour tous les téléchargements. L’[API officielle des artefacts Actions](https://docs.github.com/en/rest/actions/artifacts?apiVersion=2026-03-10#download-an-artifact) demande l’en-tête JSON GitHub pour négocier sa redirection ZIP. La correction distingue ce seul endpoint ; les assets de release et les requêtes CDN conservent le type binaire, et le jeton reste limité à l’API GitHub. Les limites, refus de redirections non autorisées, contrôle du commit exact et invariants des trois fichiers ne sont pas modifiés.

Deux nouveaux tests exercent les véritables méthodes `download` et `_request` avec un opener strict en mémoire. Sur l’ancien code, le nouveau cas Actions reproduit HTTP 415 : **18 tests réussis, un échec**. Après correction : **19/19 tests de publication**, **13/13 tests de synchronisation** et **7/7 tests de rangement documentaire** réussis, soit **39 méthodes Python**, sans requête réseau.

La proposition corrigée `d3a74c15acdab24107c028fa02a816cc2975fdc1` a réussi le [run 40051967685](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40051967685), avec 612 exécutions JVM/Android et 19 tests de publication. La [PR #10](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/pull/10) a ensuite été fusionnée dans `main` au commit `32864ba732d834e71d87b887ae63f0d7bc09eb8c`. Les validations finales de `main` et la publication réussie figurent en tête du document ; les échecs antérieurs sont conservés ici pour rendre les corrections vérifiables.
