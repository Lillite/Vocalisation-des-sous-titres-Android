# Vérifications de régression

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
