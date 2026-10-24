# Vérifications de régression

## Édition Traduction 0.4.0 — contrôles locaux du 24 octobre 2026

Le développement part de l’étape 6 `1942ba48ae3f9324b3c7d5b03ab8c9bf11463e09`.
La nouvelle application porte l’identité `fr.soustitresvoix.player.translation`,
version 0.4.0, code 6, avec une signature durable propre à cette édition et
sauvegardée hors du dépôt. Elle coexiste avec la stable 0.3.2.

Les passages Gradle locaux ont réellement exécuté **87/87 tests du noyau et
135/135 tests JVM de l’application**, en debug puis en release, sans échec,
erreur, méthode ignorée, manquante, inattendue ou dupliquée. Les inventaires des
sources ont été comparés aux XML JUnit. Les compilations des deux applications
et de leurs APK de tests ont réussi. Les **82 méthodes instrumentées** sont
compilées ; leur exécution Android doit être attestée séparément par la CI.

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
rester distingués des tests contrôlés. Les résultats CI et de publication seront
rattachés à leurs commits et exécutions réels.

Le contrôle ciblé suivant a également reproduit une perte de la réplique courante
après la seule commande de piste audio. La réinjection est maintenant effectuée
pour ce cas, sans réutiliser une ancienne réplique après changement de piste texte.
Les callbacks du renderer pendant l’attente normale d’une traduction ne sont plus
présentés comme une erreur de correspondance. Le compte de la suite JVM augmente
de 135 à 137 ; le passage complet CI doit attester ce nouvel inventaire.

Le mécanisme de publication a été soumis à 17 tests comportementaux hors réseau,
conservés dans `tools/test-publish-verified-release.py` et exécutés en CI.
Deux cas ont initialement échoué : changement concurrent de cible du brouillon et
création d’un tag incorrect pendant les téléversements. Après déplacement des
vérifications avant publication, les 17 cas passent. L’API GitHub ne fournit pas
de transaction atomique contre toute modification concurrente entre la dernière
vérification et la publication ; aucune garantie absolue n’est revendiquée.

## Validation continue dans GitHub Actions

Le workflow unique [Android — compilation et régressions](.github/workflows/android.yml)
s'exécute pour chaque proposition de fusion vers `main`, après une modification
de `main` et à la demande depuis l'onglet **Actions**. Chaque nouvelle exécution
doit réussir avant de présenter les modifications correspondantes comme validées.
Les anciens workflows propres aux branches Cinéma sont remplacés par ce parcours.

| Étape | Contrôles exigés |
| --- | --- |
| Structure | XML, manifeste source, démonstration synthétique et absence de clé privée versionnée. |
| Variante debug | 87 tests JVM du noyau, 137 tests JVM de l'application, lint, compilation de l'application et de son APK d'instrumentation ; manifeste et signature de l'APK. |
| Variante release | Nouvelle exécution des 87 tests du noyau et des 137 tests JVM de l'application, lint, compilation de l'application et de son APK d'instrumentation. |
| Identité de la release | Application non débogable, permissions, signatures identiques de l'application et des tests, alignement et empreintes SHA-256. |
| Android 36 | Deux passages complets des 82 tests instrumentés sur émulateur AOSP x86_64, appliqués à la même paire d'APK release. |
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
existants. La [cinquième étape](docs/COMMANDES_AUDIO_ETAPE_5.md) ajoute six tests
au noyau, onze tests JVM de raccordement et d’interface audio, ainsi que deux
scénarios Android. Les totaux 40/41/69 de l’étape 2, 44/46/70 de l’étape 3 et
44/53/71 de l’étape 4 restent propres à leurs états historiques ; les effectifs
de l’étape 5 sont 50/64/73. La [sixième étape](docs/AUDIO_EXPERT_ETAPE_6.md)
ajoute 27 méthodes noyau, 28 méthodes JVM de l’application et deux scénarios
Android ; le scénario ASS de l’étape 5 est aussi étendu aux commandes DSP.
Les effectifs exigés sur cette branche sont **87/137/82**.
Ces nombres sont des critères à contrôler,
pas une preuve d’exécution :
consulter les rapports de l’exécution reliée au commit et à la demande de fusion.
Aucun test antérieur n'est retiré. Lorsqu'un test est
ajouté, mettre à jour les effectifs attendus dans le workflow ou dans
`tools/run-device-tests.sh` ; ne jamais retirer un test pour retrouver un nombre
attendu. Les contrôles des rapports refusent les tests manquants, ignorés,
échoués ou dupliqués. Les quatre suites JVM et les deux passages Android
comparent aussi les méthodes exécutées à l’inventaire des sources. Les dates des rapports doivent correspondre au passage
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


L’étape traduction ajoute 10 méthodes du noyau, 45 méthodes JVM de l’application et 7 méthodes Android. Les contrôles du transport sont réalisés avec des réponses synthétiques sans compte fournisseur. Les trois scénarios Android de traduction utilisent une traduction contrôlée et le service TTS de fixture ; ils ne mesurent ni fidélité linguistique, ni disponibilité d’un compte API, ni rendu d’une voix commerciale.

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
