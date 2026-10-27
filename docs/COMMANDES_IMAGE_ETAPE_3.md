# Commandes d’image — étape 3

> **Référence historique : cette étape a été intégrée à `main` et à la [version 0.4.0 — édition Traduction](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.4.0).** Les mentions ci-dessous de travail séparé, de livraison future, d’ancienne version ou d’effectifs intermédiaires décrivent le développement de cette étape. La branche de travail est conservée par son [tag d’archive](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/tree/archive/20261024/work/etape-3-commandes-image-20261023). Ces fonctions sont conservées en 0.5.1. Pour installer et vérifier la version actuelle : [accueil](../README.md), [notes 0.5.1](releases/0.5.1.md) et [TESTING.md](../TESTING.md).

[Index](README.md) · [Architecture](ARCHITECTURE.md) · [Validation](../TESTING.md)

## Objectif et périmètre

Cette étape donne aux réglages d’image existants une frontière de commande typée
et validée. Elle couvre le contraste, la luminosité de l’image et la comparaison
avec l’original. Le validateur ne dépend ni d’Android ni de Media3 ; l’adaptateur
continue à utiliser le calcul des effets et le rendu Media3 déjà présents.

Le travail se déroule sur `work/etape-3-commandes-image-20261023`, à partir du point
de retour de l’étape 2 :

| Référence | Valeur conservée |
|---|---|
| Sources de départ | [`0319dcb9b0cfd71be4231849306f0b248e1dbe02`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/0319dcb9b0cfd71be4231849306f0b248e1dbe02) |
| Sauvegarde de l’étape 2 | `backup/etape-2-validee-20261022-0319dcb` |
| Historique précédent | [Modularisation — étape 1](MODULARISATION_ETAPE_1.md) et [Capacités et observations — étape 2](CAPACITES_ETAPE_2.md) |

La livraison de l’application reste réservée à une instruction ultérieure. Cette
étape conserve la version 0.3.2, reste séparée de `main` et ne publie pas d’APK.
Les rapports précédents conservent leur valeur pour leurs commits respectifs ;
une nouvelle validation est exigée pour les modifications de cette étape.

## Deux commandes, trois réglages

Le contrat se trouve dans
[`core/ImageCommands.kt`](../core/src/main/kotlin/fr/soustitresvoix/core/ImageCommands.kt).
`PlaybackSession.imageCommands` expose `ImageCommandPort`, implémenté par
`VisualTools`, qui possède déjà les réglages et l’ordonnancement des effets.

| Commande | Sens et limites |
|---|---|
| `SetAdjustments(contrast, imageBrightness)` | Demander les deux corrections ensemble. Contraste de **−0,3 à +0,3** ; luminosité de l’image de **−0,2 à +0,2**, bornes inclusives. Les valeurs doivent être finies. |
| `SetOriginalComparison(enabled)` | Demander explicitement l’activation ou la désactivation de la comparaison. Répéter `true` conserve la comparaison activée ; ce n’est pas un ordre de basculement. |

La luminosité de cette commande modifie le traitement de l’image. Elle est
distincte de la luminosité de l’écran, qui conserve son contrôle existant. Les
boutons de contraste léger et d’éclaircissement gardent leurs valeurs habituelles
et passent maintenant par le port ; la case de comparaison envoie son état
explicite. Le périmètre ne crée pas de nouveau panneau expert ni de nouveaux
filtres, et les commandes de zoom, de cadrage et d’analyse des marges restent
distinctes.

`ImageSettings` contient les valeurs demandées. `snapshot()` renvoie un
`ImageCommandSnapshot` immuable avec le contexte courant et une éventuelle
dernière soumission. La lecture de cet instantané ne déclenche aucun traitement.

## Validation et refus explicites

Une demande porte sa génération d’origine. `ImageCommandPolicy.validate()` est
une fonction pure : elle ne consulte pas Android, ne change aucun état et ne
planifie pas de travail. Le contexte courant est fourni par l’adaptateur.

Les contrôles sont effectués dans cet ordre :

| Ordre | Motif de refus | Condition |
|---|---|---|
| 1 | `CLOSED` | La session d’image est fermée. |
| 2 | `STALE_MEDIA` | La demande appartient à une autre génération. |
| 3 | `NON_FINITE_VALUE` | Au moins une correction contient `NaN` ou un infini. |
| 3 | `OUT_OF_RANGE` | Une correction finie dépasse ses bornes. |
| 4 | `CAPABILITY_UNAVAILABLE` | Le moteur déclare les corrections indisponibles. |
| 4 | `CAPABILITY_UNKNOWN` | La disponibilité n’est pas établie. |
| 5 | `HDR_PROTECTED` | Une demande de corrections rencontre la protection HDR active. |

Les deux valeurs de `SetAdjustments` sont validées atomiquement. Un contraste
valide ne peut pas être appliqué avec une luminosité refusée, et une valeur hors
bornes n’est pas écrêtée silencusement. Les refus conservent le ticket d’origine.
Ils n’interrompent pas un essai comparatif, ne modifient pas les réglages et ne
relancent pas le délai d’envoi des effets.

Les capacités `SUPPORTED` et `CONDITIONAL` permettent de poursuivre la validation
sous les protections courantes. `CONDITIONAL` ne signifie pas que le résultat
visuel a été vérifié. L’interface existante affiche un motif compréhensible en
cas de refus ; une comparaison refusée rétablit l’état réel de sa case.

Une acceptation fournit les valeurs demandées, une disposition et `changed`.
Lorsque la demande est identique à l’état courant, `changed=false` évite un nouvel
ordonnancement et une interruption artificielle du benchmark. Les deux signes
de zéro représentent ici la même correction numérique.

## Acceptation, soumission et pixels

Ces trois niveaux restent séparés :

| Niveau | Ce qui est établi |
|---|---|
| Demande acceptée | Les valeurs peuvent être conservées dans l’état demandé selon les protections au moment de la validation. |
| Plan soumis | Un plan calculé a été transmis par `setEffects` et l’appel a retourné sans exception ; sa génération, sa séquence et ses deux corrections peuvent être rapportées. |
| Pixels affichés | Ils ne sont pas mesurés par ce contrat. Une soumission ne prouve ni l’effet final, ni le tone mapping, ni une amélioration de qualité. |

`VisualTools` conserve le regroupement des changements sur **32 ms**. Plusieurs
demandes acceptées peuvent conduire à un seul envoi. Le plan est recalculé avec
les conditions courantes ; s’il est identique au dernier plan envoyé, aucun
nouvel envoi n’est inventé.

`SubmittedImageAdjustments` décrit les corrections du plan effectivement soumis,
qui peuvent être nulles pendant la comparaison malgré des valeurs demandées non
nulles. Sa séquence suit les envois de plans, pas le nombre de demandes acceptées
ni les images décodées. Les autres réglages graphiques existants peuvent aussi
provoquer un nouvel envoi du plan. `lastSubmitted=null` signifie qu’aucune
soumission n’est attribuable dans le contexte courant ; ce n’est pas une mesure
d’absence de traitement à l’écran.

L’initialisation du lecteur et les plans du média précédent ne sont pas
réétiquetés comme une nouvelle soumission pour satisfaire un diagnostic. Le
diagnostic général conserve par ailleurs sa propre portée de session, décrite
à l’étape 2.

## Générations, fermeture et demandes tardives

Le port d’image possède une génération propre, distincte du magasin de diagnostic
et de l’URI du fichier. Chaque ouverture de média la renouvelle, même si le même
fichier est rouvert. Le dialogue d’image capture ce ticket à son ouverture : un
clic provenant d’un ancien dialogue ne reçoit pas automatiquement le ticket du
nouveau média.

Les producteurs différés doivent conserver leur ticket d’origine et utiliser
`submit()`. Les wrappers de compatibilité `adjust()` et `toggleComparison()`
restent destinés aux appels synchrones existants ; ils ne constituent pas une
autorisation de réattribuer une ancienne demande.

La remise à zéro annule l’envoi différé précédent, invalide `lastSubmitted` et
réinitialise les réglages comme auparavant. La fermeture avance la génération,
invalide les soumissions et annule les callbacks d’effets ; les commandes restent
refusées ensuite. La lecture d’un instantané fermé n’interroge pas le lecteur
libéré. Le port s’utilise sur le thread de la session.

## Protection HDR, comparaison et cadrage

La protection HDR est vérifiée à l’acceptation des corrections puis dans le
calcul existant du plan. Une acceptation antérieure ne donne pas le droit de
continuer une correction après l’apparition d’un format HDR. Les événements de
format doivent être attribuables au média courant par leur période, leur fenêtre
et le tag d’ouverture ; un événement ancien ou sans origine suffisamment établie
ne remplace pas la protection courante.

En comparaison, une correction valide reste acceptée et mémorisée, avec la
disposition `MASKED_BY_ORIGINAL_COMPARISON`. Le plan neutralise les corrections,
les marges et le redimensionnement détaillé comme auparavant. Lorsque la
comparaison est désactivée, les valeurs demandées redeviennent éligibles sous les
protections courantes.

Activer ou désactiver la comparaison reste possible sous HDR si le moteur expose
la capacité. La disposition est alors `MASKED_BY_HDR` : cette commande ne lève
jamais la protection. Une capacité inconnue ou indisponible demeure un motif de
refus, y compris pour la comparaison. L’absence de détection HDR ne constitue
pas une preuve que le format est SDR.

Le cadrage `Presentation`, dont l’étirement et le remplissage avec recadrage,
conserve ses règles et son ordre dans le plan. La comparaison ne signifie donc
pas que toute la géométrie de présentation est annulée. Les protections de cadre,
le zoom et l’analyse des marges restent des fonctions distinctes.

## Vocalisation et audio préservés

Ce port ne décide ni d’une lecture, ni d’une pause, ni d’un déplacement temporel,
ni d’une demande de synthèse. Les règles de narration, les identités des
occurrences, la préparation, le cache, les reprises et les fondus audio restent
ceux des étapes précédentes. Un changement graphique réel conserve
l’interruption explicite du benchmark comparatif existant.

Les fichiers d’algorithmes protégés restent `NarrationMachine.kt`,
`SpeechTools.kt`, `AdaptiveTools.kt`, `SubtitleText.kt`, `AndroidSpeech.kt`,
`CueAhead.kt`, `SsaOccurrenceParser.kt`, `FullSubtitleIndexer.kt`,
`SpeechBandProcessor.kt` et `CinemaVideoRenderer.kt`. Les régressions précédentes
doivent continuer à vérifier la pause, la fin de vidéo, les callbacks périmés et
chaque événement textuel légitime.

## Vérifications exigées

| Niveau | Couverture et effectifs attendus |
|---|---|
| Noyau, quatre tests ajoutés | `ImageCommandsTest` : bornes, valeurs non finies, atomicité, priorités de refus, générations, fermeture, disponibilité, HDR/bypass, idempotence et absence de soumission implicite. |
| JVM application, cinq tests ajoutés | `ImageCommandsRobolectricTest` : vrais composants délégués et voix contrôlée active ; port raccordé, plans envoyés, refus sans mutation ou arrêt du benchmark, regroupement, générations, vieux dialogue et protections, sans commande vocale ou de transport induite. |
| Android, un scénario ajouté | `ImageCommandsAndroidTest` : vrais boutons, activité, Media3 et service TTS de fixture ; pause, position, volume et préférences inchangés ; trace vocale limitée aux deux essais explicites encadrant les commandes. |
| Suites debug et release | **44 tests du noyau et 46 tests JVM de l’application** à chaque passage, lint et compilation de l’application et de ses tests. |
| Instrumentation | **Deux passages complets de 70 tests** sur Android 36 x86_64, avec la même paire d’APK release. |
| Intégrité et provenance | Structure, manifestes, permissions, signatures, alignement, empreintes, sources inchangées pendant les contrôles et comparaison des algorithmes protégés. |

Les résultats doivent être lus dans l’exécution associée au commit testé et dans
sa demande de fusion. Cette liste fixe des critères ; elle n’atteste pas qu’ils
ont réussi. Les rapports refusent les tests manquants, ignorés, dupliqués ou
échoués. Les détails de procédure et les préconditions des scénarios précédents
restent dans [TESTING.md](../TESTING.md).

Cette étape n’ajoute pas d’oracle de pixels. Les tests graphiques antérieurs
restent exigés, notamment ceux qui vérifient le rendu et le cadrage réels.

Une réussite des tests du contrat ne mesure pas la qualité d’une voix externe,
les pixels HDR réels, TalkBack sur le téléphone cible, la chauffe ou la fluidité
d’une séance prolongée. Media3 demeure le seul moteur intégré ; aucun décodeur
alternatif, filtre graphique supplémentaire ou moteur vocal n’est ajouté ici.

## Suite proposée après validation

L’incrément suivant proposé est un écran expert d’image construit sur ce port,
avec affichage clair des demandes, refus et soumissions. Il pourra utiliser les
bornes et protections établies sans inventer d’observations. L’extension à
l’audio, puis l’intégration éventuelle d’autres moteurs de lecture, doivent
faire l’objet d’étapes séparées avec leurs propres contrats et validations.
