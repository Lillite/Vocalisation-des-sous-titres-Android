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
| Variante debug | 40 tests JVM du noyau, 41 tests JVM de l'application, lint, compilation de l'application et de son APK d'instrumentation ; manifeste et signature de l'APK. |
| Variante release | Nouvelle exécution des 40 tests du noyau et des 41 tests JVM de l'application, lint, compilation de l'application et de son APK d'instrumentation. |
| Identité de la release | Application non débogable, permissions, signatures identiques de l'application et des tests, alignement et empreintes SHA-256. |
| Android 36 | Deux passages complets des 69 tests instrumentés sur émulateur AOSP x86_64, appliqués à la même paire d'APK release. |
| Intégrité finale | APK inchangés entre les passages, sources inchangées et conservation des preuves. |

Ces effectifs incluent les suites de la version 0.3.2 et les compléments de la
[première étape de modularisation](docs/MODULARISATION_ETAPE_1.md) : trois tests
du contrôleur de narration, trois tests de composition et un scénario instrumenté
de pause/reprise. La [deuxième étape](docs/CAPACITES_ETAPE_2.md) ajoute quatre tests
du modèle de diagnostic, trois tests JVM de consultation et d'invalidation, et
un scénario Android de consultation/copie avec observation des requêtes TTS.
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

Dans ce test instrumenté, les dialogues se ferment par leurs boutons et avec
une racine Espresso explicitement limitée aux dialogues. Deux fermetures Retour
successives sans racine explicite pouvaient sélectionner l’activité sans focus.
Les fermetures utilisent des scopes `Closeable.use` imbriqués : toutes les
assertions restent actives, les deux nettoyages sont tentés et une éventuelle
erreur de fermeture est attachée à l’échec initial au lieu de le remplacer.

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
