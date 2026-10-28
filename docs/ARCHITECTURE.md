# Architecture et invariants de lecture

[Index de la documentation](README.md) · [Compilation et validation](../TESTING.md)

Cette vue décrit les sources **0.5.2**. Elles conservent les fonctions Confort intégral de la 0.5.0, les six premières étapes
et le module de traduction de la 0.4.0. Le projet sépare la logique de
narration indépendante d’Android, les adaptateurs du lecteur et l’interface. Les
invariants ci-dessous sont des exigences à préserver lors des modifications ; les
résultats de vérification restent attachés au commit réellement testé.

La 0.5.0 ajoute `NarrationLedger` pour le suivi sans texte, `PlaybackFeatures`
pour les séances et leurs repères de file inachevée, `SubtitleTimeline` et
`SubtitleBoundarySchedule` pour parcourir toutes les occurrences corrigées,
ainsi qu’une bibliothèque vocale durable distincte du cache évictable.
La traduction complète reste disponible ; le mode progressif expose seulement les préfixes validés et attend avant la première occurrence manquante. En 0.5.2, une reprise automatique exige une attente détenue par la traduction et une intention Lire encore valide. Pause utilisateur, déplacement, erreur, changement de média/piste ou fermeture invalident cette permission ; ni restauration ni callback périmé ne lancent la vidéo. Le [guide Google 0.5.2](TRADUCTION_GOOGLE_052.md) décrit le fonctionnement courant ; le [guide 0.5.0](CONFORT_INTEGRAL_050.md) et la [matrice des demandes](COUVERTURE_DEMANDES_050.md) restent les références de l’étape antérieure.

## Composition des composants

La traduction avant lecture ajoute un port `TranslationEngine` et une préparation
indépendante de la synthèse. `SubtitleTranslationSession` possède les tâches,
générations, confirmations et traductions activées ; `TranslationPlan` et
`TranslatedTrack` conservent les identités/minutages. `TranslationRunner` valide
les lots et le cache atomique. Les adaptateurs HTTP n’accèdent ni à la vidéo,
ni au lecteur, ni au moteur de voix. La session fournit seulement le texte
traduit validé au contrôleur existant, avec les identités originales.
Voir [Traduction — étape 7](TRADUCTION_ETAPE_7.md) pour le contrat initial et le [guide Google 0.5.2](TRADUCTION_GOOGLE_052.md) pour ses extensions.

### Adaptateurs Google et frontière de traduction

`GoogleWebTranslationEngine` fournit un client Kotlin natif d’un accès Google non officiel ; `MlKitTranslationEngine` adapte le SDK Google 17.0.3 au même port. Aucun des deux n’obtient le contrôle de la vidéo ni du TTS. Les paramètres de transport/téléchargement de `TranslationExecutionOptions` restent distincts des options susceptibles de changer le contenu traduit.

Google en ligne traite une occurrence par unité de travail pour enregistrer une progression validée sans perdre le reste d’un lot. Son cache en mémoire de textes strictement identiques économise des appels, sans fusionner les occurrences du plan. L’identification de la piste et les résultats de chaque occurrence restent contrôlés par le runner.

L’intervalle Google est configurable de 500 à 10 000 ms, défaut 1 000 ms. Les appels sont sérialisés ; la politique augmente l’attente lors de réponses temporaires, borne les nouvelles tentatives à trois et le cumul d’attente à 120 s, respecte `Retry-After` et vérifie l’annulation. Une durée serveur trop grande provoque l’arrêt sans tentative anticipée. Aucun autre point d’accès ni rotation d’IP n’est utilisé pour contourner un refus.

ML Kit contrôle les langues, possède un traducteur réutilisable et prépare les modèles nécessaires une fois pendant le travail. La préparation exige le Wi-Fi par défaut, avec autorisation mobile explicite. L’attente des tâches du SDK est bornée et annulable ; un résultat tardif ne peut pas activer une autre génération. Le SDK peut cependant terminer un téléchargement déjà lancé après annulation de l’attente côté lecteur.

### GGUF local — candidate 0.5.4

Le moteur GGUF local respecte le même port de traduction. L’import consenti produit une copie privée et son empreinte ; l’identité du modèle et des options distingue le cache et les points de reprise. Une tâche garde un modèle chargé pour plusieurs générations dans un processus privé distinct du lecteur. Les calculs utilisent le CPU et une fenêtre de contexte bornée ; aucun transport réseau n’est créé par ce moteur.

Le modèle reçoit des consignes propres à TranslateGemma ou Hunyuan dense et un contexte récent borné. Le runner conserve les identifiants et minutages et valide chaque occurrence avant sauvegarde ou publication progressive. Le modèle ne réécrit pas la structure du SRT. Une limite de génération, une annulation ou une erreur native ne constitue pas une réponse complète. Le SRT autonome utilise ces mêmes règles sans devoir ouvrir une vidéo. Voir le [guide GGUF](TRADUCTION_GGUF_054.md) et les [notes de validation](releases/0.5.4-local.md).

Le préfixe progressif conserve l’ordre, les identifiants et les minutages des occurrences. Le tampon de 30 s par défaut, configurable de 10 à 120 s, détermine l’avance nécessaire ; il ne mesure pas le débit de traduction. L’autorisation de reprendre après une attente de traduction appartient à la session, et les gardes existantes de narration, pause et génération restent applicables. Une fin de tâche ne donne jamais une autorisation générale de lecture.

`PlaybackSession` reçoit un `PlaybackComponents`. Les factories par défaut créent
des instances propres à cette session :

| Contrat | Implémentation par défaut | Responsabilité |
|---|---|---|
| `PlaybackBackend` | `Media3PlaybackBackend` | Posséder ExoPlayer, assembler ses renderers et ouvrir ses sources |
| `MovieAudio` / `MovieDsp` | `Media3MovieAudio` / `ExpertAudioProcessor` | Posséder le `SpeechBandProcessor` historique, puis le processeur avancé optionnel installés dans le même sink du film |
| `VideoRendererFactory` / `VideoOutput` | `CinemaVideoRendererFactory` / sortie du backend | Construire le renderer existant et transmettre effets, format et stratégie de fréquence |
| `SubtitleSource` / `SubtitleIndexer` | `Media3SubtitleSource` / adaptateur de `FullSubtitleIndexer` | Extraction pendant la lecture et indexation complète annulable |
| `NarrationController` | `ExistingNarrationController` | Déléguer les événements à `NarrationMachine` et retourner tous ses effets dans le même ordre |
| `SpeechService` | `AndroidSpeechService` | Déléguer la synthèse, le cache, la préparation et les mesures à `AndroidSpeech` |

Le contrôleur de narration, le moteur vocal, les parseurs et leurs algorithmes
existants restent derrière ces adaptateurs. Les effets de narration sont appliqués
par la session sur le thread principal. Les callbacks d’extraction sont postés sur
ce même thread et filtrés par l’epoch du média ; les retours d’indexation sont
également filtrés par l’identité de la tâche. Le focus audio et les décisions de
pause/reprise restent dans la session.

La façade `Player` utilisée par les vues et certains types de source, pistes,
effets et analytics sont encore ceux de Media3. La modularisation isole les
implémentations ; elle ne fournit pas déjà un moteur alternatif ni un protocole
entièrement indépendant de Media3. Les limites et les obligations d’un remplacement
figurent dans [Modularisation — étape 1](MODULARISATION_ETAPE_1.md).

## Capacités et observations communes

`core/PlaybackDiagnostics.kt` définit des valeurs sans type Android ou Media3 :
disponibilité, provenance, faits demandés/configurés/observés et instantanés
immuables. `PlaybackDiagnosticStore` invalide une génération lors d’un changement
de média et rejette une publication ancienne ou postérieure à sa fermeture.
L’absence d’une observation reste inconnue ; elle ne devient pas `false` ou zéro.

`PlaybackDiagnosticsObserver` adapte les événements et états Media3 existants à ce
modèle sur le thread de la session. Une identité opaque dans le tag du `MediaItem`
est créée à chaque ouverture. Un événement n’est accepté que si sa période est
retrouvée dans sa propre timeline, dans la bonne fenêtre, avec cette même identité.
Un événement sans période peut être un repli des analytics vers la fenêtre
courante : il ne constitue pas une preuve suffisante. Une réutilisation de codec
sans nouvelle initialisation peut donc laisser le décodeur inconnu après une
réouverture. Aucune réinitialisation du lecteur n’est provoquée pour le diagnostic.

Les déclarations matériel/logiciel sont mémorisées au cours de la sélection de
codecs déjà existante, uniquement à partir des flags Android disponibles depuis
l’API 29. La consultation ne déclenche pas de nouvel inventaire. Les effets
graphiques exposent le dernier plan transmis ; le compteur PCM reste un cumul de
session. Ces données ne prouvent ni les pixels affichés, ni la fréquence réelle
de l’écran, ni une atténuation sonore instantanée.

`PlaybackDiagnosticText` formate un seul instantané. Le diagnostic et le panneau
des codecs l’utilisent sans envoyer de commande de lecture ou de voix.
Le diagnostic n’ajoute pas de commande de narration, d’anticipation, de synthèse
ou d’extraction. La traduction de l’étape 7 conserve les contrôles de génération
et fournit ses textes validés au parcours vocal existant. Les détails et scénarios
de vérification du diagnostic figurent dans
[Capacités et observations — étape 2](CAPACITES_ETAPE_2.md).

## Commandes d’image validées

`core/ImageCommands.kt` définit le port `ImageCommandPort`, ses instantanés et un
validateur pur. `PlaybackSession.imageCommands` expose l’instance `VisualTools`
déjà propriétaire des valeurs et des effets ; aucun second moteur de rendu ou
ordonnanceur n’est créé.

| Type | Responsabilité |
|---|---|
| `ImageCommand.SetAdjustments` | Demander ensemble le contraste et la luminosité de l’image, sans mutation partielle |
| `ImageCommand.SetOriginalComparison` | Demander un état explicite de comparaison, sans basculement implicite |
| `ImageCommandRequest` / `ImageCommandContext` | Séparer le ticket d’origine de la génération et des protections courantes |
| `ImageCommandPolicy` | Retourner une acceptation ou un refus ; aucune lecture Android, commande de lecteur ou planification |
| `ImageCommandDecision.Accepted` | Fournir les valeurs demandées, leur disposition sous HDR/bypass et l’indication d’un changement réel |
| `SubmittedImageAdjustments` | Décrire contraste et luminosité d’un plan dont l’envoi a effectivement retourné, avec génération et séquence |
| `ImageCommandSnapshot` | Lire le contexte et la dernière soumission attribuable, sans envoyer de commande |

La validation traite d’abord la fermeture, puis la génération, les valeurs
numériques, la disponibilité et le HDR. Les deux corrections doivent être finies
et rester dans les bornes inclusives existantes : contraste de −0,3 à +0,3,
luminosité de l’image de −0,2 à +0,2. Une demande invalide est refusée entièrement,
sans écrêtage. Une capacité inconnue ou indisponible interdit aussi la commande.
Une acceptation identique à l’état demandé ne relance ni le regroupement des
effets ni l’interruption d’un essai comparatif.

`VisualTools` conserve son regroupement de 32 ms et le calcul existant du plan.
Sous comparaison, les valeurs demandées sont conservées mais leurs corrections
sont neutralisées. Le HDR interdit les nouvelles corrections et reste contrôlé
au moment du calcul du plan ; modifier la comparaison ne peut pas lever ce
verrou. Le cadrage `Presentation` reste distinct de ces corrections et continue
à s’appliquer dans les mêmes conditions. Un format inconnu ne constitue pas une
preuve de SDR.

Le ticket du port change à chaque remise à zéro pour une ouverture de média,
même à URI identique, ainsi qu’à la fermeture. Les contrôles du dialogue capturent
le ticket à son ouverture et ne le remplacent pas par le ticket courant au moment
d’un clic tardif. Les événements de format utilisés par la protection d’image
suivent aussi l’attribution stricte à la période, à la fenêtre et au tag du média
introduite à l’étape 2. La fermeture annule les envois différés et interdit toute
nouvelle commande.

`lastSubmitted` n’est enregistré qu’après le retour de `setEffects`, puis invalidé
au changement de média ou à la fermeture. Une commande regroupée, neutralisée ou
sans changement ne prouve pas à elle seule un nouvel envoi. Cette trace décrit
la configuration envoyée, jamais une vérification des pixels ou de la qualité du
rendu. Les règles de narration et le traitement audio restent indépendants de ce
port. Voir [Commandes d’image — étape 3](COMMANDES_IMAGE_ETAPE_3.md) pour les refus,
les limites et les contrôles exigés.

## Interface des réglages d’image

`ExpertImageControls` présente le contrat d’image dans le dialogue de
`CinemaView`. Il reçoit seulement le port de commande, un propriétaire de
dialogues et une condition de durée de vie ; il n’accède ni au lecteur, ni au
contrôleur de narration, ni aux préférences. `ImageSettingsDescriptions` contient
les explications des paramètres et ne lit ni ne modifie aucun état.

Les deux corrections disposent de pas décimaux de 0,01, d’une saisie précise et
de retours à zéro. Une modification individuelle conserve l’autre correction
au moment de la validation. Le sous-dialogue accepte une virgule ou un point,
vérifie le format et les bornes avec `BigDecimal`, puis convertit la valeur en
`Float` pour le contrat existant. Il refuse une saisie hors bornes même si sa
conversion aurait arrondi à une borne autorisée. Les boutons de pas deviennent
indisponibles lorsque le prochain pas dépasserait une borne, sans écrêtage.

Le ticket est capturé à la création du panneau et reste celui des saisies
différées. Une ouverture ultérieure, même à URI identique, ne réattribue pas une
ancienne demande. Les erreurs conservent le texte saisi ; les refus du port
restent visibles dans un dialogue et dans le résultat de la dernière demande.
Une comparaison refusée rétablit l’état de sa case.

Les valeurs demandées et la dernière soumission sont rendues à partir d’un même
instantané. Une soumission d’une autre génération n’est pas présentée comme
courante ; une soumission absente reste inconnue. Les indications de HDR, de
comparaison et de disponibilité expliquent les protections sans les lever.
`refresh()` ne soumet aucune commande et ne remplace pas le texte d’une saisie
en cours. `CinemaView` l’appelle depuis son actualisation existante ; le panneau
n’ajoute ni ordonnanceur d’effets ni boucle de temporisation.

Les descriptions accompagnent les commandes existantes de géométrie, d’analyse
et de luminosité de la fenêtre. Leurs effets restent distincts des deux
corrections. Les commandes synchrones de ce dialogue, ainsi que la confirmation
du mode recadré, vérifient aussi la génération capturée et la fermeture avant
leur action. L’analyse asynchrone des marges conserve son mécanisme existant ;
le ticket du panneau ne remplace pas le contrôle de son worker. En particulier,
revenir à la luminosité système enlève une consigne
de fenêtre sans activer à lui seul le mode adaptatif Android. La comparaison
neutralise les corrections, marges et Lanczos mais conserve le cadrage et les
transformations de surface. Voir [Interface d’image — étape 4](INTERFACE_IMAGE_ETAPE_4.md)
pour le guide de chaque paramètre et les exigences de régression.

## Commandes du son du film

`core/AudioCommands.kt` expose un port indépendant d’Android pour les six réglages
historiques du film et le groupe des traitements avancés. `PlaybackSession.audioCommands` le possède, car le volume est
configuré sur son `Player`, tandis que le filtre spectral appartient à son unique
`MovieAudio`. Le port de commandes ne crée pas de processeur, de sink ou d’ordonnanceur.

| Commande | Validation et application |
|---|---|
| `SetLevels` | Deux valeurs finies entre 0 et 1, validées ensemble ; le ratio multiplie le volume normal pendant la parole. Un changement réel remplace le fondu courant par la cible immédiate. |
| `SetDuckingTiming` | Trois durées entières de 0 à 2 000 ms validées ensemble ; les prochaines transitions les utilisent, sans reconstruire celles déjà planifiées. |
| `SetSpectralDucking` | Activation explicite sous déclaration de capacité ; seule la propriété `enabled` du processeur existant change. Désactiver reste possible si la capacité est inconnue. |
| `SetDsp` | Configuration avancée immuable, validée entièrement ; soumission au processeur déjà installé. Les paramètres actifs exigent une capacité déclarée. Désactiver ou préparer une configuration désactivée reste possible. |

Le validateur pur vérifie d’abord fermeture et génération, puis les valeurs et
la capacité applicable. Le ticket capturé par le producteur est invalidé à chaque
ouverture de média, même identique, ainsi qu’à la fermeture. Les préférences sont
conservées ; ce ticket ne réinitialise aucun réglage audio. Une acceptation
identique retourne avant écriture, interruption d’essai ou annulation de fondu.
La comparaison numérique traite les deux signes de zéro de la même façon.

Après validation d’un changement, la session enregistre les valeurs audio dans une
seule édition des préférences et ne modifie que les composants concernés. Aucun
appel à `refreshSettings()`, aucune commande de narration et aucune demande de
synthèse ne fait partie de ce chemin. Le traitement des effets `Duck`, son attente
de réplique proche, le signal `speaking` et le lissage PCM restent existants.
La notification générale `onChanged` n’est pas appelée par ces commandes : son
parcours dans l’activité peut lancer une préparation vocale. Les contrôles audio
actualisent leur propre état ; l’actualisation périodique existante reste distincte.

`AudioCommandSnapshot` expose préférences demandées, volume configuré, activation
du filtre, signal de parole et compteur de traitement. Le volume peut être entre
deux cibles pendant un fondu. Le compteur additionne les échantillons de tous les
canaux depuis le début de session, y compris le passage sans atténuation ; il ne
mesure pas une atténuation acoustique. Après fermeture, les observations sont
absentes et le lecteur libéré n’est pas relu.

Les panneaux, curseurs et gestes de volume utilisent ce port. `AudioSettingsControls`
présente les pas existants, les retours individuels aux défauts, les explications
et un état actualisé explicitement, sans boucle de suivi supplémentaire. Un refus
rétablit les valeurs enregistrées, affiche le motif et demande son défilement à
l’écran. La case spectrale ne déclenche aucune commande pendant l’actualisation.
Le panneau général conserve sa pause et son application des réglages vocaux
historiques ; Volumes rapides ne passe pas par ce parcours.

Les saisies numériques conservent le ticket d’origine dans leurs sous-dialogues.
`AudioNumericInput` vérifie une grammaire explicite, les unités et les bornes avec
`BigDecimal`, avant conversion vers `Float`, `Double` ou entier. Un refus conserve
le texte ; une annulation ne soumet rien. Les champs compagnons sont relus à
l’application pour éviter d’écraser une modification plus récente d’un autre champ.

Le détail des durées, de leur anticipation et des contrôles de non-régression
figure dans [Commandes du son du film — étape 5](COMMANDES_AUDIO_ETAPE_5.md).

## Traitements avancés du film

`AudioDspSettings` possède cinq bandes sous forme de champs immuables, sans liste
mutable partagée. `AudioDspPolicy` vérifie tous les paramètres, même désactivés,
puis normalise seulement les signes de zéro pour les comparaisons. Le groupe
versionné persiste séparément des préférences vocales ; une représentation
invalide revient au DSP globalement désactivé.

`Media3MovieAudio` installe d’abord le `SpeechBandProcessor` historique, puis un
`ExpertAudioProcessor` distinct. Le nouveau composant reçoit seulement le film
en PCM16 mono ou stéréo. Son bypass compatible reste installé afin qu’une
activation ne reconstruise ni le lecteur ni le sink. `configure` prépare un
format ; `onFlush` construit l’état correspondant au format devenu actif ;
`onReset` retire les observations et l’état de flux explicitement.

Le moteur pur `AudioDspEngine` applique préampli, cinq biquads paramétriques,
moyenne mono éventuelle, balance puis limiteur lié entre les canaux. Le calcul
interne utilise des `Double` ; l’adaptateur quantifie une seule fois sa sortie
en PCM16. Les changements de chaîne utilisent deux filtres stables en fondu
de 20 ms, avec regroupement des demandes rapprochées vers la dernière cible.
Les modifications du seul limiteur conservent les états des biquads. Un plafond
de limiteur consommé s’applique immédiatement, y compris pendant le fondu ;
le retour au bypass libère son gain restant progressivement.

Le moteur ne rééchantillonne pas, ne retire ni n’ajoute de trame et n’accède à
aucun composant de narration. Une fréquence inapplicable est signalée sans être
déplacée. Le limiteur porte sur les crêtes des échantillons à cette sortie,
pas sur les crêtes inter-échantillons, la sonie ou la sortie acoustique.

Les configurations passent vers le thread audio par une publication immuable
avec numéro de révision. L’adaptateur publie ensuite un `AudioDspObservation`
immuable à la fin d’un bloc : révision consommée, format, cible, transition,
bandes ignorées, compteurs et crête PCM quantifiée. `AudioDspSnapshot` conserve
séparément la dernière soumission. Une lecture d’observation ne traite pas de
buffer et ne demande pas de voix. Ces faits concernent le dernier flux vu :
ils ne sont pas automatiquement réattribués à un nouveau média.

Les états et les compteurs DSP repartent à zéro au renouvellement du flux ;
le compteur spectral historique conserve sa portée de session. Un format
compatible sans trame conserve une révision consommée absente. Le passthrough,
l’offload, le flottant et le multicanal ne constituent pas des parcours traités
par ce processeur. Les détails de chaque réglage et leurs limites figurent
dans [Saisie et audio avancé — étape 6](AUDIO_EXPERT_ETAPE_6.md).

### Propriétaire des dialogues audio

En 0.5.2, le bouton DSP créé par `AdvancedSettings.populate()` emprunte la durée de vie du véritable `SettingsPanel`. La colonne temporaire de construction est vidée lors de la distribution des commandes entre rubriques ; elle ne doit donc pas être capturée comme propriétaire d’une fenêtre. Cette ancienne capture faisait échouer `isAttachedToWindow` et empêchait l’ouverture depuis Son.

`AudioDialogOwner` continue de fermer les fenêtres enfant lors du détachement du parent et de la destruction de l’activité. Le ticket d’ouverture audio reste celui de la session initiale ; changer de média ne renouvelle pas implicitement les droits d’un éditeur ancien. Les tests couvrent le vrai parcours Paramètres > Son, distinct de Volumes rapides.

## Noyau indépendant d’Android

Le module [`core`](../core/src/main/kotlin/fr/soustitresvoix/core/) contient la logique de narration, le traitement du texte, la planification adaptative, la validation des fichiers WAV, le cache, les mesures, les contrats de commande et le traitement audio avancé indépendant d’Android. Il définit également le port de traduction, le plan immuable des occurrences et la validation des pistes traduites ; les clients HTTPS et le stockage Android restent dans `app`.

`NarrationMachine` est une machine à états déterministe. Les événements de lecture, pause, déplacement, nouveau sous-titre, disponibilité de la voix et fin ou échec d’une phrase produisent une liste ordonnée d’effets. Chaque énoncé dispose d’un identifiant : un callback ancien ne doit pas terminer une nouvelle phrase ou provoquer une reprise indue.

| Mode | Comportement attendu |
|---|---|
| Confort | Attendre la fin d’une phrase lorsque nécessaire et conserver les événements textuels suivants |
| Adaptatif | Adapter la vitesse dans le plafond choisi, puis conserver l’attente de confort si le temps disponible ne suffit pas |
| Continu | Poursuivre la vidéo, avec interruption possible d’une phrase trop longue |

Une pause manuelle interdit toute reprise sans commande explicite. Les transitions de fin de vidéo, de buffering, de déplacement et de répétition font l’objet de tests distincts.

`SubtitleText` nettoie les styles connus et découpe le texte sans couper une paire de substitution UTF-16. `SubtitleImport` contrôle l’encodage et refuse un fichier vide, binaire ou trop volumineux. Les filtres de texte sont des réglages explicites ; ils ne doivent pas servir à masquer une perte de répliques.

## Sous-titres, occurrences et anticipation

Dans le module [`app`](../app/src/main/java/fr/soustitresvoix/player/), `CueAheadFactory` observe les sous-titres exposés par les parseurs Media3 et alimente `CueAheadIndex`. `SsaOccurrenceParser` conserve l’identité des événements ASS/SSA au travers des groupes d’affichage successifs.

Cette identité distingue un panneau continu, encore affiché lorsque d’autres répliques changent, d’un nouvel événement contenant les mêmes mots. **La correction des répétitions ne doit pas supprimer les événements source distincts : dialogues, panneaux, réapparitions et répétitions légitimes restent à vocaliser.** La sélection de piste et les horaires font partie de cette identification ; comparer uniquement le texte serait insuffisant.

`FullSubtitleIndexer` effectue une indexation locale explicite du média et du sous-titre externe éventuel. L’indexation est bornée et annulable. Une lecture incomplète ou un dépassement de limite doit être signalé, sans présenter le résultat comme une couverture intégrale. L’anticipation prépare les phrases à venir ; elle ne les prononce pas avant leur événement de lecture.

## Synthèse vocale, préparation et cache

`AndroidSpeech` pilote un moteur Android TextToSpeech externe, transmet ses callbacks au thread principal et borne l’attente de réponse. Les choix de voix portent sur celles que le moteur déclare installées et utilisables hors ligne. La qualité et les performances réelles dépendent de ce moteur et du téléphone.

La durée d’un WAV préparé peut servir à la planification adaptative. En synthèse directe, une estimation calibrée est utilisée. Le plafond de vitesse reste distinct de la vitesse effectivement demandée, et le moteur externe peut refuser un réglage.

`SpeechDiskCache` conserve des fichiers WAV dans un espace privé et limité. La validation de leur structure et de leur empreinte protège contre une entrée invalide ou corrompue. La configuration du cache tient compte des moteurs, voix, versions et paramètres connus ; un changement interne de modèle non annoncé par le moteur nécessite une invalidation explicite.

Les préparations de cinq minutes ou de toute la vidéo sont déclenchées par l’utilisateur et restent annulables. Le film demeure en pause pendant la préparation. Un parcours achevé ne signifie pas que toute l’œuvre tient encore dans le cache : les limites de capacité peuvent entraîner des évictions.

## Lecture, image et interface

`PlaybackSession` relie Media3, la narration, les interruptions audio, l’indexation et la préparation. `PlayerPreferences` conserve les réglages et la dernière position. `MainActivity` expose les contrôles natifs et `CinemaView` les commandes du plein écran, avec libellés textuels et alternatives aux gestes.

`SettingsPanel` conserve les widgets pendant l’actualisation de leurs valeurs. En 0.5.1, `SettingsScrollView` reçoit initialement un focus neutre et libère le focus de saisie resté sur un champ distant lorsque l’utilisateur touche une autre commande. Cela évite qu’un rafraîchissement fasse remonter le panneau ; une saisie explicitement choisie reste possible. Le focus de saisie et le focus d’accessibilité restent distincts.

`VisualTools` et `CinemaVideoRenderer` appliquent le cadrage et les effets graphiques. Les deux modes « image entière » réinitialisent zoom, déplacements et marges retirées. Le recadrage volontaire et la suppression de marges demandent une action distincte. L’analyse de quelques images ne garantit pas qu’aucun texte ne figure dans une marge à un autre instant. Les protections existantes bloquent les corrections concernées lorsque le HDR est reconnu ; l’absence d’une telle détection n’est pas une preuve de SDR.

La baisse de volume agit sur le mix du film. Le traitement expérimental de bande vocale PCM16 n’est pas une séparation des voix et des ambiances. Les diagnostics et bancs d’essai sont locaux ; ils ne prouvent pas l’utilisation d’un backend vocal GPU/NPU ni une amélioration sur un appareil non mesuré.

## Fichiers et confidentialité

Le sélecteur Android accorde un accès ciblé aux documents choisis. Les sous-titres externes sont copiés dans le répertoire privé de l’application ; les fichiers originaux ne sont pas modifiés. Le manifeste, depuis la 0.4.0, déclare **`android.permission.INTERNET` pour la traduction explicitement demandée**. Il ne demande pas d’autorisation de microphone, de caméra ou d’accès général au stockage. La déclaration Internet n’est pas une demande de permission à l’exécution ; la confirmation du lecteur reste le contrôle précédant l’envoi de la piste.

L’indexation et l’estimation initiales restent locales. Après confirmation avec un moteur en ligne, si aucun cache complet correspondant n’est disponible, le fournisseur de traduction reçoit le texte des sous-titres, les langues et les paramètres nécessaires ; cette opération n’envoie ni vidéo ni audio. Le transport impose HTTPS, vérifie les certificats système, borne les délais et les réponses, refuse les redirections et ne relance pas automatiquement une requête potentiellement facturée. Les messages d’erreur ne recopient ni clé ni corps de réponse contenant du texte privé. Le traitement ultérieur des données dépend du fournisseur choisi.

Dans le **correctif 0.5.1**, `TranslationRequestPacing` espace les appels DeepL de 500 ms au minimum et réessaie seulement les refus explicites **429/529**. Les cinq nouvelles tentatives au maximum attendent 1/2/4/8/16 secondes avec aléa, ou le `Retry-After` fourni. L’attente cumulée est bornée à **120 secondes par lot**, hors temps réseau ; un délai serveur trop long provoque un arrêt, jamais un renvoi anticipé. Les attentes vérifient l’annulation par tranches d’au plus 100 ms. Les erreurs réseau, les réponses incomplètes, **456** et **500/502/503/504** restent sans renvoi automatique. La consultation du quota et les autres fournisseurs ne passent pas par cette politique. Voir le [guide des limites](TRADUCTION_GRATUITE_ET_LIMITES.md).

`TranslationRunner` conserve seulement les traductions complètes dans un **cache privé de 32 Mio**, distinct du cache WAV. L’écriture utilise `AtomicFile` ; l’empreinte lie les occurrences et leurs minutages, le contenu, les langues et le fournisseur, ainsi que l’adresse canonique du serveur LibreTranslate et les options de traduction. Une piste complète retrouvée peut être réutilisée sans réseau ni clé ; une entrée absente ou invalide nécessite une nouvelle demande confirmée. Un journal distinct conserve les lots validés pour la reprise. Il marque une requête comme incertaine avant l’envoi, puis enlève cette marque après un résultat validé ou un refus explicite non facturable ; une panne réseau ou serveur conserve l’incertitude. La conservation durable et l’export sont décrits dans le [guide 0.5.0](CONFORT_INTEGRAL_050.md).

La mémorisation d’une clé API est facultative. `TranslationCredentials` chiffre les clés avec **AES-GCM et Android Keystore**, sans repli en clair. Les clés LibreTranslate sont isolées par serveur ; changer son adresse vide le champ avant le chargement de sa propre clé. Les sauvegardes Android de l’application sont désactivées. Ce stockage ne remplace ni la maîtrise des quotas du compte ni la protection de l’appareil. La clé privée de signature de l’APK reste séparée des clés API et hors du dépôt et des releases.

ML Kit traite le texte sur le téléphone, sans envoyer les sous-titres au service de traduction. Le fournisseur d’initialisation automatique est retiré du manifeste : l’application initialise le SDK après confirmation explicite. Google déclare une télémétrie technique (appareil, application, identifiants d’installation, performances, tailles, langues et événements) ; les modèles et la configuration utilisent le réseau. Voir la [déclaration ML Kit](https://developers.google.com/ml-kit/android-data-disclosure). L’attribution du fournisseur reste visible. La candidate GGUF 0.5.4 charge directement un fichier local choisi, sans téléchargement de poids ni télémétrie de traduction ajoutés par ce moteur. Son backend CPU reste distinct d’une accélération GPU/NPU, non exécutée. Les liens documentaires s’ouvrent dans le navigateur sur demande ; le moteur vocal externe possède ses propres permissions. Aucun OCR, moteur Google Cloud ou déploiement Apps Script n’est intégré. Voir le [contrat de traduction](TRADUCTION_ETAPE_7.md) et le [guide des moteurs](GUIDE_MOTEURS_TRADUCTION.md) pour les parcours réellement disponibles.

La synthèse des sous-titres reste distincte des annonces de l’interface. Le passage de l’activité en arrière-plan arrête la lecture. Les règles d’accessibilité et l’absence de reprise inattendue doivent aussi être vérifiées sur téléphone.

## Vérification et provenance

La [procédure de validation](../TESTING.md) distingue tests JVM, Lint, compilation, instrumentation Android, contrôle du manifeste et vérification de signature. Les tests utilisent des sous-titres et médias synthétiques. Le service vocal de fixture permet de contrôler les requêtes et les transitions ; il ne mesure pas le naturel d’une voix externe.

La démonstration principale est générée par Gradle à partir du SRT versionné. Des petites fixtures ASS/MKV sont aussi conservées dans les ressources de test. FFmpeg sert à la génération de la démonstration dans l’environnement de compilation ; il n’est pas intégré au lecteur Android.

La **0.5.2** doit être validée sur son propre arbre et son APK avant fusion. Le workflow après fusion réutilise la preuve seulement si l’arbre et les parents correspondent, puis compare le contenu applicatif reconstruit hors signature. Les chiffres et liens effectifs restent consignés dans [TESTING.md](../TESTING.md) ; aucun résultat antérieur ne couvre les nouveaux adaptateurs.

À titre historique, les sources **0.5.1** du commit [`d5d5b63`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/d5d5b63ea37ca4be423f375e8e7a75e870d154e6) ont été intégrées par la [PR #16](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/pull/16) après **524 tests réussis** : 178 noyau, 254 JVM application et 92 instrumentés Android 36. Le [run préalable 40556708779](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40556708779) précède la fusion ; le [run 40560530124](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40560530124) reconstruit ensuite la release et vérifie le même contenu applicatif, hors signature, sans répéter les suites. L’état de la distribution signée reste indiqué dans [TESTING.md](../TESTING.md).

### Références historiques

L’APK **0.4.0** correspond au commit [`32864ba732d834e71d87b887ae63f0d7bc09eb8c`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/tree/32864ba732d834e71d87b887ae63f0d7bc09eb8c), validé par le [run Android 40053284969](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40053284969). Il compte **612 exécutions réussies** : 87 tests noyau et 137 tests JVM de l’application dans chacune des variantes debug/release, puis deux passages des 82 tests instrumentés Android. Les outils ont passé **39 tests Python** : 19 de publication et, dans le [workflow de métadonnées](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40053284958), 13 de synchronisation et 7 de rangement documentaire. Lint relève zéro erreur et 51 avertissements dans chaque variante. Ces résultats ne remplacent pas une validation après modification.

Pour l’ancienne 0.3.2, le commit `311d99052d287bb2227e85255740de201234e770` et son [run de livraison](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/39532451903) restent des preuves historiques distinctes. Les [essais sur téléphone](TESTS_TELEPHONE.md) restent à effectuer pour les voix réelles, TalkBack, les codecs, le rendu et les séances prolongées. Les réponses API contrôlées des tests ne prouvent ni la qualité linguistique d’un fournisseur réel ni les droits ou quotas d’un compte personnel.
