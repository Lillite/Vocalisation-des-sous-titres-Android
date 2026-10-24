# Capacités et observations — étape 2

[Index](README.md) · [Architecture](ARCHITECTURE.md) · [Validation](../TESTING.md)

## Objectif et état de développement

Cette étape prépare un langage commun pour décrire les possibilités du lecteur
et ce qui est effectivement connu pendant une session. Media3 reste le moteur
opérationnel. Le diagnostic existant utilise ce modèle ; aucun moteur alternatif
ni nouveau traitement audio/vidéo n’est ajouté dans cette étape.

La demande du 22 octobre 2026 prévoit plusieurs étapes et réserve la livraison de
l’application à une instruction ultérieure. Le travail reste sur
`work/etape-2-capacites-20261022`, avec une demande de fusion en brouillon, sans
fusion dans `main`, nouvelle version ou publication APK.

| Point de retour | Référence conservée |
|---|---|
| État avant modularisation | `32e988654fc44faa80e1fb69c98cf9ca29e9f5b0`, `backup/avant-modularisation-20261022-32e9886` |
| Étape 1 | `a093e791b1413aefef6add027225b88c20657c6c`, `backup/etape-1-validee-20261022-a093e79` |

La [validation de l’étape 1](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/39848413116)
concerne seulement son arbre `900277aca9fa4febebbdca8ecb10c6e9584e9c67`.
Elle ne remplace pas la nouvelle validation exigée pour l’étape 2.

## Données communes

Le modèle du module `core` ne dépend d’aucun type Android ou Media3. Chaque entrée
possède un identifiant, une disponibilité (`SUPPORTED`, `CONDITIONAL`,
`UNAVAILABLE`, `UNKNOWN`), une justification et sa provenance. Trois faits
facultatifs sont conservés séparément :

| Fait | Signification | Exemple |
|---|---|---|
| Demandé | Préférence choisie | Priorité logicielle souhaitée |
| Configuré | État transmis ou rapporté par le composant | Stratégie d’adaptation de fréquence configurée |
| Observé | Événement, état ou mesure effectivement rapporté, avec sa portée | Nom de la dernière initialisation vidéo attribuable au média |

Une préférence n’établit pas une observation. Une valeur absente s’affiche comme
« non établi ». Les décimales non finies sont refusées ; les instantanés copient
leurs listes et les exposent en lecture seule ; les identifiants dupliqués sont
refusés lors de la publication.

Les seize entrées actuelles couvrent le décodeur et sa classification, le
déplacement, la position, la durée, le format vidéo, le HDR source, les corrections
d’image, le redimensionnement, la stratégie de fréquence, le traitement audio
expérimental, le volume du film, la sélection et l’index de sous-titres, la
disponibilité vocale et la file de narration.

## Portée exacte des informations

- **Décodeur :** l’événement rapporte la dernière initialisation attribuable au
  média. Il ne garantit pas que ce codec soit encore actif au moment de la copie.
  Une préférence logicielle reste distincte. La classification matériel/logiciel
  vient des déclarations Android mémorisées lors de la sélection déjà existante,
  à partir de l’API 29 ; aucun scan supplémentaire n’est déclenché par la lecture.
- **Vidéo :** résolution, cadence et HDR décrivent les métadonnées de la source.
  Le plan graphique décrit la dernière configuration transmise pendant la session.
  Le rendu final, le tone mapping et la fréquence réelle de l’écran ne sont pas
  mesurés par ce modèle.
- **Audio :** le volume rapporté est une configuration. Le nombre d’échantillons
  PCM16 est un cumul de session, incluant les passages sans atténuation ; il ne
  prouve pas une atténuation instantanée ni une séparation des sources.
- **Sous-titres :** la sélection et le nombre d’entrées de l’index restent distincts
  d’une preuve d’exhaustivité. L’état d’indexation d’un ancien média n’est pas
  réattribué au nouveau.
- **Voix :** disponibilité et taille de file sont lues sans synthèse supplémentaire.
  Le contenu et les identifiants des demandes ne sont pas inclus dans ce modèle.

Le modèle n’intègre pas d’URI, de nom de média, de libellé de piste ou de texte de
sous-titre. Le diagnostic conserve ses mesures de session antérieures, dont les
latences et compteurs, sans les présenter comme des mesures propres au nouveau
média. Les tests de confidentialité existants restent exigés.

## Invalidation et événements tardifs

Chaque ouverture reçoit une identité opaque neuve, y compris lors de la
réouverture de la même URI. Elle est attachée au `MediaItem` et comparée par
identité à celle de la fenêtre de l’événement. L’observateur exige aussi une
période non nulle, présente dans la timeline de cet événement, et appartenant à
la bonne fenêtre. Les événements anciens ou non attribuables sont ignorés.

Cette précaution traite le repli des analytics Media3 vers la timeline courante
lorsqu’une période d’origine n’est plus connue. Le seul tag de la fenêtre courante
ne suffit pas. Aucun ancien décodeur ou format n’est réutilisé sur la seule base
de l’URI. Si Media3 réutilise un codec sans nouvel événement d’initialisation,
l’information peut rester inconnue. La fermeture invalide définitivement le
magasin, y compris en cas de callback ultérieur.

## Protection de la vocalisation

L’observateur n’envoie aucune commande, ne lance aucun minuteur ni nouveau thread,
et ne déclenche ni inventaire de codecs, ni préparation, ni synthèse. Son stockage
est limité aux données de diagnostic. Les transitions et l’ordre des effets de la
session restent conservés.

Les dix fichiers suivants doivent rester identiques à l’étape 1 :
`NarrationMachine.kt`, `SpeechTools.kt`, `AdaptiveTools.kt`, `SubtitleText.kt`,
`AndroidSpeech.kt`, `CueAhead.kt`, `SsaOccurrenceParser.kt`, `FullSubtitleIndexer.kt`,
`SpeechBandProcessor.kt` et `CinemaVideoRenderer.kt`.

L’entrée dans les réglages conserve sa pause préexistante. L’absence d’effet
revendiquée et testée porte sur les actions de diagnostic : lire, afficher,
actualiser et copier. La fermeture du panneau conserve aussi son comportement
préexistant.

## Vérifications exigées pour cette étape

| Niveau | Couverture ajoutée et contrôle |
|---|---|
| Noyau, quatre nouveaux tests | Provenance, séparation des faits, décimales finies, générations, copie immuable, doublons, fermeture définitive |
| JVM application, trois nouveaux tests | Vraie session Media3 et boutons existants ; aucune commande aux frontières avec une voix active ; différences demandé/configuré/observé ; événements anciens et non attribuables ; réouverture de la même URI ; fermeture |
| Android, un nouveau scénario | Vraie activité et vrai presse-papiers ; afficher, actualiser, copier ; état et préférences inchangés ; observation de toutes les demandes TTS entre deux contrôles vocaux positifs ; invalidation après remplacement |
| Suites complètes | 40 tests JVM du noyau et 41 tests JVM application sur debug puis release, lint, compilation des deux variantes et de leurs APK de tests |
| Instrumentation | Deux passages complets de 69 tests sur Android 36 x86_64, avec la même paire d’APK release et vérification stricte des rapports |
| Intégrité | Structure, manifestes, permissions, signatures, alignement, sources et empreintes ; comparaison des fichiers protégés |

Le service vocal de fixture reçoit une action d’observation supplémentaire pour
toutes les synthèses ; le filtrage des événements de non-perte déjà existants et
le comportement de synthèse restent inchangés. Les deux demandes vocales
explicites du nouveau test assurent que l’observateur IPC est réellement connecté.

Ces effectifs sont des critères de validation. Le statut et les journaux de
l’exécution rattachée au commit de la demande de fusion constituent la preuve de
réussite ; cette liste seule n’affirme pas qu’une exécution a réussi. Les résultats
doivent refuser tout test manquant, ignoré, dupliqué ou échoué.

Les tests automatisés ne mesurent pas la qualité d’une voix externe, TalkBack sur
le téléphone cible, le HDR réel, la chauffe ou la fluidité sur OnePlus 15. Ces
essais physiques restent distincts. Aucun APK n’est livré à cette étape.
