# Architecture et invariants de lecture

[Index de la documentation](README.md) · [Compilation et validation](../TESTING.md)

Cette vue décrit les comportements de l’édition **0.3.2** et les trois premières étapes
préparatoires sur cette branche. Le projet sépare la logique de
narration indépendante d’Android, les adaptateurs du lecteur et l’interface. Les
invariants ci-dessous sont des exigences à préserver lors des modifications ; les
résultats de vérification restent attachés au commit réellement testé.

## Composition des composants

`PlaybackSession` reçoit un `PlaybackComponents`. Les factories par défaut créent
des instances propres à cette session :

| Contrat | Implémentation par défaut | Responsabilité |
|---|---|---|
| `PlaybackBackend` | `Media3PlaybackBackend` | Posséder ExoPlayer, assembler ses renderers et ouvrir ses sources |
| `MovieAudio` | `Media3MovieAudio` | Posséder l’unique `SpeechBandProcessor` installé dans le sink du film |
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
effets et analytics sont encore ceux de Media3. Cette première étape isole les
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
des codecs l’utilisent sans envoyer de commande de lecture ou de voix. Les
algorithmes de narration, d’anticipation, de synthèse et d’extraction restent ceux
de l’étape 1. Les détails et scénarios de vérification figurent dans
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

## Noyau indépendant d’Android

Le module [`core`](../core/src/main/kotlin/fr/soustitresvoix/core/) contient la logique de narration, le traitement du texte, la planification adaptative, la validation des fichiers WAV, le cache et les mesures.

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

`VisualTools` et `CinemaVideoRenderer` appliquent le cadrage et les effets graphiques. Les deux modes « image entière » réinitialisent zoom, déplacements et marges retirées. Le recadrage volontaire et la suppression de marges demandent une action distincte. L’analyse de quelques images ne garantit pas qu’aucun texte ne figure dans une marge à un autre instant. Les protections existantes bloquent les corrections concernées lorsque le HDR est reconnu ; l’absence d’une telle détection n’est pas une preuve de SDR.

La baisse de volume agit sur le mix du film. Le traitement expérimental de bande vocale PCM16 n’est pas une séparation des voix et des ambiances. Les diagnostics et bancs d’essai sont locaux ; ils ne prouvent pas l’utilisation d’un backend vocal GPU/NPU ni une amélioration sur un appareil non mesuré.

## Fichiers et confidentialité

Le sélecteur Android accorde un accès ciblé aux documents choisis. Les sous-titres externes sont copiés dans le répertoire privé de l’application ; les fichiers originaux ne sont pas modifiés. Le lecteur ne demande pas d’autorisation Internet, de microphone, de caméra ou d’accès général au stockage. Les liens du catalogue s’ouvrent dans le navigateur sur demande ; le moteur vocal externe possède ses propres permissions.

La synthèse des sous-titres reste distincte des annonces de l’interface. Le passage de l’activité en arrière-plan arrête la lecture. Les règles d’accessibilité et l’absence de reprise inattendue doivent aussi être vérifiées sur téléphone.

## Vérification et provenance

La [procédure de validation](../TESTING.md) distingue tests JVM, Lint, compilation, instrumentation Android, contrôle du manifeste et vérification de signature. Les tests utilisent des sous-titres et médias synthétiques. Le service vocal de fixture permet de contrôler les requêtes et les transitions ; il ne mesure pas le naturel d’une voix externe.

La démonstration principale est générée par Gradle à partir du SRT versionné. Des petites fixtures ASS/MKV sont aussi conservées dans les ressources de test. FFmpeg sert à la génération de la démonstration dans l’environnement de compilation ; il n’est pas intégré au lecteur Android.

Pour l’APK 0.3.2, le commit source de référence est `311d99052d287bb2227e85255740de201234e770` et la [validation de livraison correspondante](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/39532451903) est identifiable séparément des futures validations de `main`. Les [essais sur téléphone](TESTS_TELEPHONE.md) complètent ces preuves pour les voix, TalkBack, les codecs, le rendu et les séances prolongées.
