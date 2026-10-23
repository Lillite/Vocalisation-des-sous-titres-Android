# Vérifications de régression

## Validation continue dans GitHub Actions

Le workflow unique [Android — compilation et régressions](.github/workflows/android.yml)
s'exécute pour chaque proposition de fusion vers `main`, après une modification
de `main` et à la demande depuis l'onglet **Actions**. Chaque nouvelle exécution
doit réussir avant de présenter les modifications correspondantes comme validées.
Les anciens workflows propres aux branches Cinéma sont remplacés par ce parcours.

| Étape | Contrôles exigés |
| --- | --- |
| Structure | XML, manifeste source, démonstration synthétique et absence de clé privée versionnée. |
| Variante debug | 44 tests JVM du noyau, 53 tests JVM de l'application, lint, compilation de l'application et de son APK d'instrumentation ; manifeste et signature de l'APK. |
| Variante release | Nouvelle exécution des 44 tests du noyau et des 53 tests JVM de l'application, lint, compilation de l'application et de son APK d'instrumentation. |
| Identité de la release | Application non débogable, permissions, signatures identiques de l'application et des tests, alignement et empreintes SHA-256. |
| Android 36 | Deux passages complets des 71 tests instrumentés sur émulateur AOSP x86_64, appliqués à la même paire d'APK release. |
| Intégrité finale | APK inchangés entre les passages, sources inchangées et conservation des preuves. |

Ces effectifs incluent les suites de la version 0.3.2 et les compléments de la
[première étape de modularisation](docs/MODULARISATION_ETAPE_1.md) : trois tests
du contrôleur de narration, trois tests de composition et un scénario instrumenté
de pause/reprise. La [deuxième étape](docs/CAPACITES_ETAPE_2.md) ajoute quatre tests
du modèle de diagnostic, trois tests JVM de consultation et d'invalidation, et
un scénario Android de consultation/copie avec observation des requêtes TTS.
La [troisième étape](docs/COMMANDES_IMAGE_ETAPE_3.md) ajoute quatre tests de
validation des commandes d’image, cinq tests JVM de leur raccordement et un
scénario instrumenté supplémentaire. La [quatrième étape](docs/INTERFACE_IMAGE_ETAPE_4.md)
ajoute sept scénarios JVM et un scénario Android aux fichiers de tests d’image
existants. Les totaux 40/41/69 de l’étape 2 et 44/46/70 de l’étape 3 restent
propres à leurs états historiques ; les effectifs exigés sur cette branche sont
44/53/71. Ces nombres sont des critères à contrôler, pas une preuve d’exécution :
consulter les rapports de l’exécution reliée au commit et à la demande de fusion.
Aucun test antérieur n'est retiré. Lorsqu'un test est
ajouté, mettre à jour les effectifs attendus dans le workflow ou dans
`tools/run-device-tests.sh` ; ne jamais retirer un test pour retrouver un nombre
attendu. Les contrôles des rapports refusent les tests manquants, ignorés,
échoués ou dupliqués. L'instrumentation compare aussi les méthodes exécutées à
l'inventaire des sources. Les dates des rapports doivent correspondre au passage
en cours.

La variante debug est compilée et vérifiée sur la JVM. Les deux exécutions
instrumentées automatiques portent sur la **variante release** ; la compilation
de l'APK de tests debug ne constitue pas une exécution de ces tests sur Android.

Deux artefacts sont produits :

- **Android-rapports-validation** : journaux, résultats JUnit, rapports lint,
  manifestes, signatures, empreintes, diagnostics de l'émulateur, commit source
  et archive `sources-verifiees.zip`. Les preuves disponibles sont conservées
  même lorsqu'une étape échoue.
- **SousTitresVoix-release-validation** : paire d'APK release testée, empreintes,
  certificat de validation et commit source. Cet artefact est produit uniquement
  après la réussite de tous les contrôles.

La clé créée dans cette CI est **éphémère**, conservée hors du dépôt puis
supprimée. L'APK de validation ne remplace pas une distribution signée avec la
clé stable et ne garantit pas une mise à jour par-dessus une installation
existante. Les artefacts Actions sont conservés 14 jours ; les APK destinés à
l'utilisation et leurs informations de version sont accessibles depuis le
[README](README.md).

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
```

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

Les vérifications de grandes polices et des propriétés d’accessibilité sur la
JVM ne constituent pas un essai TalkBack. Les captures de la démonstration
synthétique documentent une interface observée ; elles ne prouvent pas à elles
seules l’intégralité du défilement, le rendu HDR ou le confort sur téléphone.
Les dix fichiers sensibles de narration, de sous-titres, d’audio et du renderer
doivent rester identiques au point de retour de l’étape 3. Toute limite ou
correction découverte pendant la validation doit rester attribuable au passage
et au commit concernés, avec les échecs intermédiaires conservés.

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

## APK signé pour installation

L'identité d'installation de cette série est `fr.soustitresvoix.player.stable`.
Conserver sa clé privée hors du dépôt pour permettre les prochaines mises à jour.
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
