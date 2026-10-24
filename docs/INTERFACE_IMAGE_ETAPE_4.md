# Interface d’image — étape 4

> **État publié au 24 octobre 2026 : cette étape est intégrée à `main` et à la [version 0.4.0 — édition Traduction](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.4.0).** Les mentions ci-dessous de travail séparé, de livraison future, d’ancienne version ou d’effectifs intermédiaires décrivent le développement de cette étape. La branche de travail est conservée par son [tag d’archive](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/tree/archive/20261024/work/etape-4-interface-image-20261023). Pour installer et vérifier la version actuelle : [accueil](../README.md) et [TESTING.md](../TESTING.md).

[Index](README.md) · [Architecture](ARCHITECTURE.md) · [Validation](../TESTING.md)

## Objectif et état de départ

Cette étape rend les réglages d’image existants plus précis et plus
compréhensibles. Elle utilise le contrat validé de l’[étape 3](COMMANDES_IMAGE_ETAPE_3.md)
pour le contraste, la luminosité de l’image et la comparaison avec l’original.
Elle ajoute les explications de chaque commande du dialogue **Image et cadrage**,
sans changer les algorithmes de vocalisation ou introduire un moteur graphique
supplémentaire.

| Repère | Référence conservée |
|---|---|
| Sources de départ | [`c96b687f91b2c9f7fadfc44383c8d346047cd30e`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/c96b687f91b2c9f7fadfc44383c8d346047cd30e) |
| Sauvegarde de l’étape 3 | `backup/etape-3-validee-20261023-c96b687` |
| Validation historique de l’étape 3 | [Exécution 39867160436](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/39867160436), attachée à ses propres sources |
| Branche de cette étape | `work/etape-4-interface-image-20261023` |
| Version conservée | 0.3.2 |

Les étapes précédentes et leurs sauvegardes restent disponibles. Cette étape
reste séparée de `main`, avec une demande de fusion en brouillon, jusqu’à une
instruction ultérieure de livraison. Elle ne publie pas d’APK. Les résultats
historiques ne remplacent pas une nouvelle validation des sources de cette
branche.

## Accéder aux réglages

Ouvrir **Plein écran cinéma**, puis **Image / zoom**. Le dialogue rassemble les
corrections, le cadrage, les marges, le redimensionnement, le zoom et la
luminosité de l’écran dans une vue verticale défilante. Les explications sont
placées auprès des commandes. Chaque modification conserve les limites et les
protections de son chemin existant.

Le contraste et la luminosité de l’image peuvent maintenant être modifiés de
trois façons : boutons par pas de **0,01**, saisie d’une valeur décimale ou retour
à zéro. Les raccourcis antérieurs restent présents. Les valeurs demandées et le
dernier plan transmis sont affichés séparément ; le bouton d’actualisation les
consulte sans changer le lecteur.

## Guide de chaque paramètre

### Contraste et luminosité de l’image

| Paramètre | Effet et limites | Comment le modifier |
|---|---|---|
| **Contraste** | Renforce l’écart entre les tons avec une valeur positive et l’atténue avec une valeur négative. Zéro est neutre. Plage du lecteur : **−0,30 à +0,30**, bornes incluses. Le coefficient n’est pas un pourcentage ; un réglage élevé peut masquer des détails dans les zones sombres ou claires. | Utiliser les boutons **Diminuer/Augmenter** par pas de 0,01, **Saisir le contraste**, ou **Remettre le contraste à zéro**. |
| **Luminosité de l’image** | Décale les composantes rouge, verte et bleue de la vidéo : positif pour éclaircir, négatif pour assombrir, zéro pour conserver les valeurs d’origine. Plage : **−0,20 à +0,20**. Ce coefficient ne mesure pas une quantité de lumière et ne change pas la luminosité de l’écran. | Utiliser les boutons par pas de 0,01, **Saisir la luminosité de l’image**, ou son retour à zéro. Vérifier les zones claires et les noirs après chaque changement. |
| **Contraste léger + / original** | Demande **+0,12** lorsque le contraste est nul ; toute valeur non nulle revient à zéro. La luminosité de l’image est conservée. | Appuyer sur le raccourci existant, sous les protections courantes. |
| **Éclaircir légèrement / original** | Demande **+0,06** lorsque la luminosité de l’image est nulle ; toute valeur non nulle revient à zéro. Le contraste est conservé. | Appuyer sur le raccourci existant. |
| **Remettre les deux corrections à zéro** | Demande ensemble contraste 0,00 et luminosité de l’image 0,00. La comparaison, les marges, Lanczos, le cadrage, le zoom et la luminosité de l’écran gardent leur état. | Appuyer sur ce bouton pour annuler uniquement ces deux corrections. La demande reste soumise à la disponibilité et à la protection HDR. |

Les corrections ne recréent pas des informations absentes du fichier. Un réglage
numériquement accepté ne garantit pas qu’il améliore la visibilité de toutes les
scènes. Les bornes ci-dessus sont celles choisies par le lecteur, et non la
totalité des valeurs possibles des classes Media3.

### Saisie précise et erreurs

Le sous-dialogue accepte une virgule ou un point comme séparateur décimal, un
signe plus ou moins et des valeurs intermédiaires telles que **0,125**. Sa
description rappelle les bornes et la limite de **24 caractères**. Le signe
moins typographique est reconnu. Les pourcentages, la notation scientifique,
les champs vides et les textes non numériques sont refusés.

**Appliquer** valide seulement le paramètre ouvert, en conservant l’autre
correction courante. **Annuler** conserve le réglage précédent. Une erreur de
saisie maintient le dialogue, son texte et une explication visible ; elle ne
transforme pas silencieusement la demande en une autre valeur. Une valeur
dépassant la borne reste refusée même lorsqu’elle est très proche de celle-ci.

Les pas utilisent une addition décimale : par exemple, **0,125 + 0,01** donne
**0,135**. Si le prochain pas dépasse une borne, le bouton devient indisponible
au lieu de tronquer la valeur. Depuis **0,295**, le bouton d’augmentation de 0,01
est donc indisponible ; utiliser la saisie pour demander **0,30**. La valeur
retenue et affichée après validation correspond à la précision du nombre
transmis au contrat du lecteur.

### Comparaison, disponibilité et état réel

| Commande ou indication | Ce qu’elle signifie |
|---|---|
| **Comparer à l’original : désactiver les traitements** | Cocher neutralise temporairement les deux corrections, les suppressions de marges et Lanczos. Leurs réglages sont conservés. Décocher permet de les retrouver sous les protections courantes. |
| **Modifications pendant la comparaison** | Une nouvelle correction valide est conservée mais reste masquée jusqu’à la fin de la comparaison. |
| **Cadrage et zoom pendant la comparaison** | Le cadrage choisi, le zoom, les déplacements et la luminosité de l’écran restent inchangés. La comparaison ne remet donc pas toute la géométrie à son état initial. |
| **Valeurs demandées** | Réglages acceptés et conservés par le lecteur. Ils peuvent différer des corrections envoyées pendant la comparaison ou sous protection HDR. |
| **Dernier plan transmis** | Corrections présentes dans le dernier plan dont l’envoi au moteur a retourné pour cette ouverture. L’absence de transmission connue reste indiquée explicitement. |
| **Dernière demande** | Acceptation, absence de changement ou motif de refus. Ce résultat n’est pas une mesure des pixels affichés. |
| **Actualiser l’état des corrections** | Relit les informations courantes sans envoyer d’effet, modifier les réglages ou demander une synthèse vocale. |

Le HDR et Dolby Vision reconnus neutralisent les corrections concernées.
Modifier ou remettre à zéro les deux corrections est alors refusé, conformément
au contrat de l’étape 3. La comparaison peut changer si la capacité le permet,
mais ne lève jamais la protection. Une disponibilité inconnue ou indisponible
reste expliquée ; l’absence d’un verrou HDR ne certifie pas à elle seule un
format SDR.

Une commande acceptée, un plan transmis et un résultat mesuré dans les pixels
restent trois faits différents. Le lecteur conserve le regroupement de ses
effets sur 32 ms ; un état demandé peut être visible avant son envoi. Il ne
fabrique pas un nouvel envoi lorsque le plan est identique. La rubrique
**Dernier plan transmis** n’est ni une confirmation de qualité ni une mesure du
rendu final.

### Cadrage et protection

| Paramètre | Effet | Utilisation et retour arrière |
|---|---|---|
| **Image entière, proportions originales** | Conserve toute l’image et ses proportions ; des bandes peuvent rester lorsque la surface a un autre rapport largeur/hauteur. | Appuyer sur ce mode remet le zoom à 1,00×, recentre l’image, annule les marges retirées et active la protection. |
| **Tout l’écran — image entière étirée** | Remplit la surface avec l’image entière en changeant ses proportions ; les formes peuvent paraître plus larges ou plus hautes. Les bandes encodées dans le fichier restent dans les pixels. | Appuyer réinitialise également zoom, déplacements et marges retirées, puis active la protection. |
| **Tout l’écran, image recadrée** | Conserve les proportions et remplit la surface en coupant des bords. Des personnages ou textes peuvent sortir du cadre. | Si la protection est active, confirmer le recadrage. Le zoom revient à 1,00× et les déplacements à zéro. Le choix est mémorisé pour cette vidéo et ne devient pas le défaut des nouveaux fichiers. |
| **Protéger le cadre** | Bloque zoom et déplacements accidentels. Cocher recentre et rétablit 1,00× ; depuis le mode recadré, cela revient à l’image entière avec proportions originales. | Décocher avant de zoomer ou déplacer. Une suppression de marges déjà validée en mode entier reste une opération distincte : utiliser son bouton d’annulation. |
| **Diagnostic du cadrage et des bandes noires** | Présente les dimensions codées, les proportions déclarées, la sortie du traitement, la surface disponible, le mode, le zoom, la protection et les marges. | Appuyer pour consulter, puis fermer. Ces données ne mesurent pas les pixels visibles ni les petites zones masquées par les coins physiques ou le poinçon. |

### Marges et redimensionnement

| Paramètre | Effet et limites | Comment agir |
|---|---|---|
| **Analyser les marges encodées — aperçu obligatoire** | Met la lecture en pause et examine localement sept images réparties dans la vidéo. Une proposition peut être impossible en cas de scène sombre, de texte dans les bandes ou de marges variables. Android 8.1 minimum ; analyse désactivée sous protection HDR. | Lancer explicitement, examiner les sept aperçus et confirmer seulement la proposition souhaitée. Aucun recadrage n’est automatique. Le fichier original reste intact. |
| **Annuler toute suppression de marges** | Remet les quatre marges retirées à zéro. Les corrections et le mode de cadrage restent séparés ; un mode recadré peut encore couper des bords. | Appuyer pour retirer l’analyse appliquée. |
| **Mise à l’échelle détaillée Lanczos** | Demande un filtre de redimensionnement conservant les proportions à cette étape, avant le cadrage. La cible suit la surface mesurée, avec un grand côté limité à 3 840 pixels. Près de la taille originale, le filtre peut ne rien changer. | Cocher pour demander le filtre, décocher pour le redimensionnement habituel. Il attend une surface connue et reste neutralisé sous comparaison ou protection HDR. Comparer netteté et fluidité. |

L’examen de sept images ne prouve pas l’absence de texte dans les marges de
toutes les autres images. Lanczos recalcule des pixels ; il ne reconstruit pas
des détails absents et un coût graphique supplémentaire ne garantit pas une
amélioration sur l’appareil.

### Zoom, déplacements et luminosité de l’écran

| Paramètre | Effet et unité | Comment agir |
|---|---|---|
| **Zoom + / Zoom −** | Agrandissement de **1,00× à 4,00×**, par pas de **0,25×** avec arrêt aux bornes. Au-delà de 1,00×, une partie des bords peut sortir de la surface. | Décocher la protection puis utiliser les boutons. La protection conserve le zoom à 1,00×. |
| **Déplacer l’image** | Déplace dans la direction indiquée de **40 dp** par appui, unités Android indépendantes de la densité, avec limitation selon le zoom et la surface. | Zoomer et désactiver la protection, puis utiliser gauche, droite, haut ou bas. À 1,00× ou avec protection active, aucun déplacement n’est conservé. |
| **Recentrer / réinitialiser** | Annule les déplacements et rétablit **1,00×**. Le mode de cadrage et les corrections gardent leurs valeurs. | Appuyer pour revenir à une image centrée dans le mode choisi. |
| **Écran : luminosité +10 points / −10 points** | Change la consigne de luminosité de la fenêtre par **10 points**, sur l’échelle **1 à 100 %** du lecteur. Ce pourcentage ne mesure ni les nits ni la lumière émise. | Depuis une consigne explicite, augmenter ou diminuer de 10 points. Depuis le mode système, le calcul part de 50 % : le premier plus donne 60 %, le premier moins 40 %. Ce départ n’est pas une lecture du niveau réel. |
| **Écran : reprendre le réglage système** | Supprime la consigne de fenêtre pour reprendre le réglage préféré d’Android, manuel ou adaptatif selon la configuration du téléphone. | Appuyer sur ce bouton. Cela n’active pas automatiquement la luminosité adaptative et ne change pas la correction des pixels de la vidéo. |

## Conservation des valeurs et durée de vie

Les deux corrections, la comparaison, les marges et Lanczos appartiennent à
l’ouverture courante du média. Leur remise à zéro lors d’une nouvelle ouverture
reste celle du lecteur existant, y compris quand le même fichier est rouvert.
La conservation du cadrage par vidéo et le choix par défaut des modes d’image
entière restent distincts de ces corrections.

Le panneau capture le ticket d’ouverture à sa création. Une saisie commencée
avant un changement de vidéo garde ce ticket ; elle est refusée si elle est
validée après le remplacement. Relire l’état courant ne donne pas à un ancien
dialogue le droit de régler le nouveau média. La fermeture du propriétaire
retire les dialogues et empêche leurs commandes tardives.

Le même contrôle de génération précède les autres commandes synchrones du
dialogue et la confirmation d’un mode recadré. L’analyse asynchrone des marges
conserve son worker et ses contrôles existants ; cette étape ne refond pas son
cycle de vie et ne lui attribue pas un nouveau mécanisme de ticket.

## Mise en œuvre et séparation des responsabilités

- [`ExpertImageControls.kt`](../app/src/main/java/fr/soustitresvoix/player/ExpertImageControls.kt)
  présente les corrections, les états et la saisie. Il reçoit `ImageCommandPort`,
  la fonction de présentation des dialogues et la condition de durée de vie. Il
  ne possède aucun lecteur, ordonnanceur d’effets ou moteur vocal.
- [`ImageSettingsDescriptions.kt`](../app/src/main/java/fr/soustitresvoix/player/ImageSettingsDescriptions.kt)
  regroupe les descriptions françaises sans consulter ni modifier l’état.
- [`CinemaView.kt`](../app/src/main/java/fr/soustitresvoix/player/CinemaView.kt)
  compose le dialogue défilant, conserve les commandes de géométrie existantes,
  possède les sous-dialogues et actualise le panneau depuis son parcours déjà
  présent.
- [`ImageCommands.kt`](../core/src/main/kotlin/fr/soustitresvoix/core/ImageCommands.kt)
  et [`VisualTools.kt`](../app/src/main/java/fr/soustitresvoix/player/VisualTools.kt)
  conservent le contrat, les protections et l’envoi effectif des plans de l’étape 3.

Le parseur de saisie utilise une validation décimale avant la conversion vers le
`Float` du contrat. Cela empêche une valeur comme `0.30000000000000001` de devenir
acceptable par simple arrondi à 0,3. Une valeur valable conserve la précision
disponible dans le type final, affichée après acceptation. La consultation ne
soumet aucune commande, ne réinitialise pas les effets et ne remplace pas le
texte d’un éditeur encore ouvert.

Les dix fichiers sensibles `NarrationMachine.kt`, `SpeechTools.kt`,
`AdaptiveTools.kt`, `SubtitleText.kt`, `AndroidSpeech.kt`, `CueAhead.kt`,
`SsaOccurrenceParser.kt`, `FullSubtitleIndexer.kt`, `SpeechBandProcessor.kt` et
`CinemaVideoRenderer.kt` doivent conserver leurs empreintes du point de retour.
Les identités des occurrences, files d’attente, pauses, reprises, callbacks,
indexations, cache vocal et traitements audio ne font pas partie des modifications
de cette étape.

## Contrôles requis et limites de preuve

Les effectifs suivants sont des critères de validation, pas une déclaration de
réussite. Consulter les rapports liés au commit et à sa demande de fusion pour
les résultats réellement exécutés.

| Passage | Effectif attendu et contrôles |
|---|---|
| JVM debug | **44 tests core et 53 tests app**, sans échec, erreur, ignorance ni doublon ; lint, APK principal et APK d’instrumentation compilés. |
| JVM release | Nouvelle exécution des **44 tests core et 53 tests app**, lint et compilation de la paire release. |
| Android release | **Deux passages de 71 tests** sur l’émulateur Android 36, avec la même paire d’APK et des empreintes identiques. |
| Intégrité | Sources et fichiers protégés inchangés pendant la validation ; manifeste, permissions, signatures et alignement contrôlés. |

Sept scénarios sont ajoutés à `ImageCommandsRobolectricTest`, en conservant ses
cinq scénarios précédents. Ils vérifient les pas et les bornes, la saisie précise,
les erreurs, les états demandés et transmis, les remises à zéro ciblées, les
protections, les tickets périmés, la fermeture et les explications dans une
configuration de police agrandie. Le contrôle des autres commandes et d’une
confirmation de cadrage couvre aussi les tickets périmés et les valeurs de
consigne écran. La conservation d’une phrase active et de sa
file utilise une voix contrôlée sur la JVM avec les vrais composants de lecture.

Un scénario supplémentaire dans `ImageCommandsAndroidTest` utilise la vraie
activité, les commandes visibles, Media3 et le service vocal de fixture. Deux
essais vocaux explicites encadrent le parcours ; la trace IPC doit contenir ces
deux requêtes seulement. Le scénario vérifie les erreurs visibles, la comparaison,
la remise à zéro, les envois réels et la conservation de l’état en pause, du volume,
des pistes et des préférences. Le premier scénario d’image, les tests de pixels,
de cadrage, de pause/reprise et de conservation des sous-titres restent requis.

Les propriétés d’accessibilité, la taille des contrôles, le défilement et la
configuration de grande police se vérifient séparément du comportement de
TalkBack sur téléphone. L’instrumentation utilise une voix de fixture ; elle ne
mesure pas une voix externe, le HDR physique ni le confort d’une séance prolongée
sur l’appareil cible. Les échecs intermédiaires et leurs corrections doivent
rester documentés ; augmenter un nombre de tests ne suffit pas à établir ces
résultats.

Le premier passage local de cette étape a exécuté les 44 tests du noyau et les
53 tests de l’application sans échec, puis compilé les APK debug principal et
d’instrumentation. Le contrôle comparatif de lint a toutefois refusé un nouvel
avertissement `ViewConstructor` : le composant recevait ses dépendances dans le
constructeur d’une sous-classe de `LinearLayout`, sans constructeur d’inflation
Android. La correction conserve ces dépendances dans un contrôleur possédant
une vue standard. Le libellé de comparaison est également placé dans une
ressource de texte, au lieu de déplacer un avertissement `SetTextI18n` existant.
Aucune règle lint n’est supprimée. Les rapports de ce passage restent conservés ;
une nouvelle validation complète doit porter sur les sources corrigées.

### Premier passage de CI de cette étape

L’[exécution 39884427222](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/39884427222)
portait sur les sources `6c33e10f444283d0cf849b485e5985eeb74e4dd3`.
Les suites JVM debug et release ont réussi avec 44 et 53 tests chacune.
Les deux passages Android ont exécuté les 71 méthodes, avec les deux mêmes
échecs et sans test ignoré, manquant ou dupliqué. Leurs journaux et captures
restent conservés dans cette exécution ; elle ne constitue pas une validation
réussie.

Le scénario historique tentait un second clic sur la comparaison sans la
ramener à l’écran, après un changement de hauteur des textes d’état. Son
parcours est adapté avec le défilement normal avant ce clic, sans contourner
la contrainte de visibilité du contrôle ni appeler son action directement.

Le scénario expert comparait l’état complet dès le retour de l’appel d’envoi
des effets. Il observait `BUFFERING` au lieu de `READY`, tandis que la voix, la
file, la pause, la position, le volume, les pistes, les préférences et l’écran
étaient identiques. Le renderer existant vide sa sortie vidéo avant de changer
les effets ; Media3 peut donc rendre momentanément la vidéo non prête, même
en pause. L’accusé d’envoi ne prouve pas que cette préparation est terminée.
Le test attend désormais le traitement de la demande sur le thread de lecture,
puis une nouvelle notification de première image lorsque le plan transmis a
changé, ainsi que le retour effectif à `READY`, dans un délai borné. Le compteur
de ces notifications est installé avant les actions et relevé avant chaque
changement. Un plan identique ne crée pas artificiellement une attente d’image.
Le test vérifie les autres invariants pendant cette attente et conserve
l’égalité complète de l’état final : un blocage permanent, une erreur du
lecteur, une reprise ou une nouvelle synthèse restent des échecs. Cette
notification Media3 ne remplace pas les contrôles de pixels déjà présents.
Les algorithmes du renderer et de la voix ne sont pas modifiés pour adapter
l’application à cette attente.

## Références des descriptions

Les descriptions combinent les règles effectivement présentes dans le lecteur
et les sources primaires des composants utilisés. Les lectures de référence
ont été faites le **23 octobre 2026** :

- [Media3 1.11.1 — Contrast.java](https://github.com/androidx/media/blob/1.11.1/libraries/effect/src/main/java/androidx/media3/effect/Contrast.java) : coefficient de contraste et valeur neutre.
- [Media3 1.11.1 — Brightness.java](https://github.com/androidx/media/blob/1.11.1/libraries/effect/src/main/java/androidx/media3/effect/Brightness.java) : décalage des composantes RGB et absence de prise en charge HDR de cet effet.
- [Media3 1.11.1 — LanczosResample.java](https://github.com/androidx/media/blob/1.11.1/libraries/effect/src/main/java/androidx/media3/effect/LanczosResample.java) : filtre de redimensionnement proportionnel et absence possible de changement près du facteur 1.
- [Android — WindowManager.LayoutParams.screenBrightness](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#screenBrightness) : consigne propre à la fenêtre et retour au réglage préféré du système avec une valeur négative.

Les bornes de corrections, le pas, la limite de saisie, la protection HDR, les
sept images d’analyse, le plafond de surface et la conservation des réglages sont
des comportements du projet ; ils ne sont pas présentés comme des garanties
générales de Media3 ou de tous les appareils Android.

## Étape suivante proposée

Après validation de cette interface, préparer les commandes expertes de
l’**audio du film** avec le même principe : paramètres explicites, plages et
unités décrites, disponibilité constatée, refus compréhensibles et vérification
de l’absence d’effet indésirable sur la narration. L’intégration ou l’évaluation
de moteurs alternatifs reste un chantier ultérieur. La livraison demeure
réservée à une instruction explicite.
