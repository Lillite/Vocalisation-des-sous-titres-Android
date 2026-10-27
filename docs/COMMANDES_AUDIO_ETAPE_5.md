# Commandes du son du film — étape 5

> **Référence historique : cette étape a été intégrée à `main` et à la [version 0.4.0 — édition Traduction](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.4.0).** Les mentions ci-dessous de travail séparé, de livraison future, d’ancienne version ou d’effectifs intermédiaires décrivent le développement de cette étape. La branche de travail est conservée par son [tag d’archive](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/tree/archive/20261024/work/etape-5-commandes-audio-20261023). Ces fonctions sont conservées en 0.5.1. Pour installer et vérifier la version actuelle : [accueil](../README.md), [notes 0.5.1](releases/0.5.1.md) et [TESTING.md](../TESTING.md).

[Index](README.md) · [Architecture](ARCHITECTURE.md) · [Validation](../TESTING.md)

## Objectif et point de retour

Cette étape donne aux **six réglages audio du film déjà présents** une frontière
de commande typée et validée, accompagnée d’explications visibles. Media3 reste
le lecteur opérationnel. Le traitement spectral utilise le même processeur que
précédemment ; les algorithmes de vocalisation, d’extraction des sous-titres et
de traitement PCM ne font pas partie des modifications prévues.

Le point de retour est le commit de l’étape 4
[`8feafda50a272cbca9972cf1c948dce5afd269e7`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/8feafda50a272cbca9972cf1c948dce5afd269e7),
conservé par `backup/etape-4-validee-20261023-8feafda`. Le travail de cette étape
reste séparé de `main`. La livraison de l’application demeure réservée à une
instruction ultérieure ; ce guide n’annonce aucune publication d’APK.

Les rapports de l’étape 4 prouvent uniquement les résultats de leurs sources.
Les modifications de cette étape doivent être vérifiées à nouveau sur leur
commit exact. Les effectifs et résultats réellement exécutés sont à consigner
dans la demande de fusion et dans [TESTING.md](../TESTING.md), une fois les
vérifications terminées.

## Les six paramètres

Les pourcentages ci-dessous sont des consignes du lecteur. Ils ne représentent
ni des décibels ni une mesure de la puissance ou du volume acoustique perçu.
Les millisecondes décrivent des durées demandées à l’ordonnanceur de
l’application, pas une mesure garantie du délai matériel à la sortie audio.

| Paramètre | Plage et défaut | Commandes et effet |
|---|---|---|
| **Volume normal du film** | 0 à 100 %, défaut **100 %** | Les boutons de volume partent du pourcentage entier affiché et ajoutent ou retirent **5 points**, avec arrêt aux bornes. 53 % + 5 points donne 58 %. Zéro coupe le son du film. Une modification remplace le fondu ou l’attente en cours par la nouvelle cible, calculée selon l’état de narration. La réinitialisation rétablit 100 %. |
| **Son du film pendant la voix** | 0 à 100 %, défaut **25 %** du volume normal | Même pas de **5 points** depuis la valeur entière affichée. C’est un multiplicateur : volume normal 60 % × ratio 25 % = cible 15 %. Zéro coupe le film pendant la voix ; 100 % supprime la baisse uniforme. La réinitialisation rétablit 25 %. Le filtre spectral, s’il est activé et applicable, reste distinct. |
| **Durée de baisse du film** | 0 à 2 000 ms, défaut **160 ms** | Boutons de **40 ms** avec arrêt aux bornes. Fixe le temps demandé pour la prochaine transition vers le niveau réduit. Zéro demande d’atteindre la cible dès le premier callback de cette transition. La réinitialisation rétablit 160 ms. |
| **Durée de remontée du film** | 0 à 2 000 ms, défaut **500 ms** | Boutons de **100 ms** avec arrêt aux bornes. Fixe le temps demandé pour revenir au volume normal après l’attente. Zéro supprime ce fondu, mais conserve l’attente préalable. La réinitialisation rétablit 500 ms. |
| **Attente avant remontée** | 0 à 2 000 ms, défaut **350 ms** | Boutons de **50 ms** avec arrêt aux bornes. Définit un minimum avant la prochaine remontée. L’anticipation d’une réplique proche peut prolonger cette attente, même si le minimum vaut zéro. La réinitialisation rétablit 350 ms. |
| **Atténuation spectrale expérimentale** | Activée ou désactivée, défaut **désactivée** | Cocher demande le filtre existant du film en PCM16 autour de **250 à 3 200 Hz**, pendant le signal de parole. Décocher ou réinitialiser le désactive. Les fréquences et l’intensité internes restent fixes. Ce filtre affecte aussi la musique et ne sépare pas les sources. |

Les six valeurs sont enregistrées pour les prochaines vidéos et les prochaines
ouvertures de l’application. Changer de média ne les réinitialise pas. Une
réinitialisation individuelle ne remet pas les autres paramètres à leur défaut.
Les boutons de volume n’augmentent pas la valeur de cinq pour cent de sa valeur
actuelle : ils déplacent la consigne affichée de cinq points de pourcentage.

Le volume de la voix conserve son réglage séparé dans « Volumes rapides » et son
comportement existant pour le prochain segment. Il n’appartient pas aux six
commandes du film. Le volume système, le moteur vocal, son débit, le mode de
narration, les pistes et les règles de reprise restent des réglages distincts.

## Où les modifier

Le panneau **Volumes rapides** présente les six réglages du film, leurs
descriptions visibles et les retours aux valeurs par défaut. Il conserve aussi
le réglage de volume de la voix déjà présent. Les commandes du film peuvent être
utilisées pendant une phrase : leur application ne demande ni pause, ni
nouvelle synthèse, ni reprise de lecture.

Les réglages généraux **Voix et confort** conservent leurs deux curseurs de
volume et leur mise en pause historique. Leur section avancée réutilise les
contrôles des durées et du filtre spectral. La fermeture de ce panneau conserve
le parcours existant d’application des réglages vocaux ; cette étape ne présente
donc pas ce panneau général comme une interface utilisable sans pause.

Le geste vertical à droite de la vidéo continue à modifier le volume du film
dans sa plage, par la même frontière de commande. Les commandes audio ne
nécessitent pas ce geste ; les boutons restent disponibles. Les propriétés
d’accessibilité vérifiables dans l’interface ne remplacent pas une vérification
de TalkBack sur le téléphone de l’utilisateur.

## Quand les changements prennent effet

### Volume normal et ratio pendant la voix

Le niveau cible est calculé ainsi :

```text
voix signalée active : volume normal × ratio pendant la voix
voix non signalée active : volume normal
```

Une modification réelle de l’un de ces deux réglages retire le callback du
fondu courant puis applique immédiatement la cible calculée, comme le parcours
de mélange existant. Elle peut donc interrompre une baisse, une attente ou une
remontée. Modifier le ratio alors que la voix n’est plus active conserve la
cible normale, même si un ancien fondu était encore en attente.

Une commande demandant exactement la valeur déjà enregistrée ne retire pas ce
callback. Elle ne crée pas non plus une nouvelle écriture ou une interruption
artificielle du banc d’essai. Les signes positif et négatif de zéro désignent
le même niveau numérique.

### Durées et attente

La transition existante capture sa cible, sa durée et son attente lorsqu’elle
est construite. Modifier un des trois paramètres temporels affecte la
**prochaine transition planifiée**. Une baisse déjà commencée, une attente déjà
programmée ou une remontée déjà en cours conserve ses valeurs capturées.

La progression actuelle est programmée par pas de 20 ms. Le premier callback
de remontée est programmé après l’attente ; il marque le début du fondu. Le
temps réel dépend de l’exécution des callbacks. Une phrase terminée n’implique
donc pas que le film soit déjà revenu à son volume normal.

Lorsque la narration ne signale plus de parole, l’attente est au moins celle
enregistrée. Si l’intention de lecture reste active et que le prochain début
connu de la piste de sous-titres sélectionnée se trouve dans les 1 200 ms, le
lecteur retient le plus grand des deux délais suivants :

- le minimum demandé par le réglage ;
- le temps jusqu’à ce prochain début, augmenté de 150 ms.

La valeur zéro du réglage supprime seulement le minimum ; elle ne désactive pas
cette anticipation. L’attente concerne la remontée du son du film. Elle ne
déplace pas volontairement le début de la vocalisation. Un nouveau signal de
parole conserve le parcours existant d’annulation et de remplacement du fondu.

### Filtre spectral

La commande change le drapeau d’activation du processeur installé dans le sink
du lecteur. Elle ne construit pas un second processeur, ne recrée pas le sink,
ne vide pas ses buffers et ne change pas le signal `speaking`, qui appartient
à la narration. Le processeur conserve son lissage lors de l’activation et de
la désactivation.

Le traitement existant ne s’applique que si des échantillons PCM16 traversent
effectivement ce processeur. L’acceptation d’une activation ne certifie ni le
parcours audio final, ni une modification audible. Le filtre est appliqué au
film et peut toucher voix originales, musique et autres sons situés dans sa
bande. La voix narrée utilise son propre parcours. Un ratio uniforme de 100 %
n’annule pas un filtre spectral activé.

## Frontière de commande et validation

`PlaybackSession` possède la frontière audio, car elle relie deux composants
déjà existants : le volume configuré sur son `Player` et l’activation du
processeur de son `MovieAudio`. Le validateur du noyau ne dépend ni d’Android
ni de Media3. Le contexte de durée de vie et de disponibilité est fourni par
la session ; un appelant ne peut pas l’utiliser pour lever une protection.

Le contrat
[`AudioCommands.kt`](../core/src/main/kotlin/fr/soustitresvoix/core/AudioCommands.kt)
définit trois commandes explicites, exposées par `PlaybackSession.audioCommands` :

| Commande | Valeurs validées ensemble |
|---|---|
| `SetLevels(movieVolume, duckVolume)` | Les deux niveaux finis, chacun de 0 à 1. L’interface les exprime en pourcentages de 0 à 100. |
| `SetDuckingTiming(attackMs, releaseMs, holdMs)` | Les trois durées entières de 0 à 2 000 ms. |
| `SetSpectralDucking(enabled)` | L’activation ou la désactivation explicite, avec contrôle de capacité lors d’une nouvelle activation. |

Les commandes utilisent `AudioCommandRequest(generation, command)`.
`AudioCommandPolicy.validate()` produit `AudioCommandDecision.Accepted` avec
les valeurs demandées et `changed`, ou `Rejected` avec le ticket d’origine et
un motif. Le bouton de remise à zéro d’un champ relit les autres champs du
groupe et les conserve dans sa demande.

Chaque demande porte son ticket d’origine. La validation précède tout effet
secondaire : session fermée, ticket périmé, valeur non finie, valeur hors plage
et indisponibilité applicable conduisent à un refus explicite. Une demande
invalide n’est pas écrêtée silencieusement pour devenir acceptable. Lorsque
plusieurs valeurs sont demandées ensemble, elles sont validées ensemble avant
toute application ; un refus ne conserve pas une moitié de la demande.

Le contrôle de capacité concerne une **nouvelle activation** spectrale :
`UNAVAILABLE` et `UNKNOWN` la refusent ; `SUPPORTED` et `CONDITIONAL` permettent
la demande, sans certifier son effet acoustique. Désactiver reste permis même
si la capacité n’est pas déclarée. Répéter une préférence déjà active est une
acceptation inchangée ; cela ne réactive pas un composant et ne certifie pas
sa disponibilité. Les commandes de niveaux et de durées ne dépendent pas de
la disponibilité du filtre spectral.

Le résultat distingue refus, acceptation avec modification et acceptation sans
modification. Un refus laisse les réglages, le fondu, le processeur et le banc
d’essai intacts. Une modification réelle termine un banc d’essai en cours afin
de ne pas attribuer une même mesure à deux configurations différentes. Elle
n’appelle pas `refreshSettings()`, qui conserve ses effets historiques sur la
pause et les préférences vocales.

L’interface expose les refus dans un texte compréhensible et retrouve les
valeurs réellement conservées. Les pas de boutons s’arrêtent aux bornes de
l’interface ; ils n’autorisent pas une requête externe hors plage.

## Durée de vie et conservation des réglages

Une interface capture son ticket lorsqu’elle est créée. Un changement de média
l’invalide, y compris si le même URI est rouvert. Une commande provenant d’un
ancien panneau doit être refusée sans être attribuée au nouveau média. Une
simple consultation de l’état ne renouvelle pas l’autorisation de cet ancien
panneau.

La fermeture invalide les commandes restantes et retire les callbacks de la
session. Lire l’état fermé ne doit pas interroger le `Player` libéré. Les
préférences audio persistent indépendamment de ce ticket : il protège les
demandes tardives, mais ne les efface pas à chaque ouverture.

Les commandes s’utilisent sur le thread propriétaire de la session, comme les
autres opérations du lecteur. La validation pure ne transforme pas le lecteur
Android en objet utilisable sans coordination depuis plusieurs threads.

## Ce que montrent les observations

| Information | Portée et limite |
|---|---|
| Volume normal demandé | Préférence du film enregistrée, indépendamment de la phase du fondu. |
| Ratio demandé pendant la voix | Multiplicateur du volume normal, pas un volume acoustique autonome. |
| Trois durées demandées | Valeurs destinées aux prochaines transitions. Elles ne décrivent pas nécessairement les valeurs capturées par une transition déjà planifiée. |
| Volume configuré dans le lecteur | Valeur rapportée à cet instant par `Player.volume`, éventuellement intermédiaire pendant un fondu. Elle ne mesure pas la sortie matérielle. |
| Activation spectrale configurée | Drapeau transmis au processeur installé. Il ne prouve pas que le format ou le parcours courant permette le traitement. |
| Signal de parole | Signal fourni par la narration au processeur. Il n’est pas un paramètre modifiable par l’interface audio. |
| Échantillons traités | Compteur cumulatif de session, totalisant les échantillons de tous les canaux, y compris le passage sans atténuation. Une trame stéréo compte pour deux échantillons. Ce n’est pas une preuve d’atténuation. |

Le compteur n’est pas remis à zéro à chaque nouvelle vidéo. Une lecture de
diagnostic ne demande pas une synthèse, un nouveau mixage, un changement de
volume ou un traitement de buffers. Une observation indisponible reste absente ;
elle n’est pas remplacée par une mesure fictive de zéro. Les données présentées
ne remplacent pas l’écoute du résultat sur la sortie réellement utilisée.

`AudioCommandSnapshot` expose les préférences dans `context.requested`, puis
`configuredVolume`, `spectralEnabled`, `speaking` et `processedSamples` pour les
observations. Ces quatre observations valent `null` après fermeture. Le panneau
indique qu’il s’agit de l’état à la dernière actualisation ; son bouton de
consultation ne prétend pas constituer une mesure acoustique continue.

## Protection de la vocalisation

Les commandes audio ne doivent pas changer l’identifiant d’une phrase en cours,
les textes en attente, les identités d’occurrences, le nombre de demandes de
synthèse, le cache, les pistes, la position ou l’intention de lecture. Les
répétitions légitimes de sous-titres doivent conserver leur vocalisation.

Une modification après une pause explicite, une perte de focus audio, une
déconnexion ou la fin du fichier ne doit jamais reprendre la lecture. Un
callback vocal ancien doit garder les règles de refus déjà présentes. Le
signal de parole continue d’être fourni exclusivement par la narration.

Les dix fichiers sensibles suivants doivent conserver leurs empreintes de
l’étape 4 : `NarrationMachine.kt`, `SpeechTools.kt`, `AdaptiveTools.kt`,
`SubtitleText.kt`, `AndroidSpeech.kt`, `CueAhead.kt`, `SsaOccurrenceParser.kt`,
`FullSubtitleIndexer.kt`, `SpeechBandProcessor.kt` et `CinemaVideoRenderer.kt`.
Une empreinte inchangée complète les tests d’intégration ; elle ne prouve pas
à elle seule l’absence de régression dans leurs appelants.

## Contrôles requis et résultats à consigner

Cette section décrit les preuves à produire. **Elle ne constitue pas une
déclaration de réussite des tests de l’étape 5.** Les résultats finaux doivent
identifier le commit et les sources exactes, les effectifs réellement exécutés,
les échecs éventuellement corrigés et les limites de l’environnement.

| Contrôle | Propriété recherchée |
|---|---|
| Politique pure | Bornes inclusives, valeurs non finies, refus atomiques, tickets, fermeture et demandes identiques. |
| Session réelle sur JVM | Voix active et file conservées ; changement de volume pendant baisse, attente et remontée ; durées prises au prochain plan ; callback remplacé ; diagnostic sans mutation. |
| Transitions de narration | Attente anticipée, répétitions légitimes, pause, reprise explicite, callbacks périmés, fin et fermeture. |
| Interfaces | Valeurs, unités, pas et arrondis, remises à zéro ciblées, descriptions visibles, refus compréhensibles, panneau périmé et contrôles à grande police. |
| Régressions existantes | Arrondis à 53/59 %, intégration des composants, traitement PCM, sous-titres, vocalisation, image, cache et diagnostic. |
| Instrumentation Android | Vraie activité, Media3, service vocal de fixture et trace IPC ; pas de synthèse supplémentaire causée par une commande audio. Deux passages complets sur la même paire d’APK. |
| Construction et intégrité | Suites JVM debug/release, lint comparé, compilation des APK, manifeste, permissions, signatures, alignement et empreintes des sources et fichiers protégés. |

Les essais instrumentés avec une voix de fixture ne valident pas la qualité
acoustique d’un moteur vocal externe. Un émulateur ne remplace pas les essais
sur le téléphone cible, TalkBack en usage réel, une sortie Bluetooth ou une
écoute prolongée. Les résultats ne doivent pas être présentés comme une
garantie universelle d’absence de bug ou de fonctionnement de tous les codecs
et parcours audio Android.

## Sources des descriptions

Les règles décrites ici sont celles du projet, vérifiées dans ses sources :

- [`PlaybackSession.kt`](../app/src/main/java/fr/soustitresvoix/player/PlaybackSession.kt) : propriété du lecteur et du processeur, cible de mélange, fondu, anticipation, pause, focus, fermeture et banc d’essai.
- [`PlayerPreferences.kt`](../app/src/main/java/fr/soustitresvoix/player/PlayerPreferences.kt) : valeurs enregistrées, plages et défauts historiques.
- [`SpeechBandProcessor.kt`](../app/src/main/java/fr/soustitresvoix/player/SpeechBandProcessor.kt) : PCM16, bande, lissage, traitement par canal et compteur de session.
- [`Media3PlaybackBackend.kt`](../app/src/main/java/fr/soustitresvoix/player/Media3PlaybackBackend.kt) et [`PlaybackBackend.kt`](../app/src/main/java/fr/soustitresvoix/player/PlaybackBackend.kt) : installation de l’unique processeur et déclarations de capacité.
- [`CueAhead.kt`](../app/src/main/java/fr/soustitresvoix/player/CueAhead.kt) : recherche du prochain début sur la piste sélectionnée.
- [`QuickMixDialog.kt`](../app/src/main/java/fr/soustitresvoix/player/QuickMixDialog.kt), [`AdvancedSettings.kt`](../app/src/main/java/fr/soustitresvoix/player/AdvancedSettings.kt), [`MainActivity.kt`](../app/src/main/java/fr/soustitresvoix/player/MainActivity.kt) et [`CinemaView.kt`](../app/src/main/java/fr/soustitresvoix/player/CinemaView.kt) : accès aux réglages et comportement des interfaces.
- [`AudioSettingsDescriptions.kt`](../app/src/main/java/fr/soustitresvoix/player/AudioSettingsDescriptions.kt) : textes visibles des six paramètres et limites des observations, sans accès au lecteur.
- [`AudioCommands.kt`](../core/src/main/kotlin/fr/soustitresvoix/core/AudioCommands.kt) et [`AudioSettingsControls.kt`](../app/src/main/java/fr/soustitresvoix/player/AudioSettingsControls.kt) : contrat typé, validation, pas, remises à zéro et restitution des résultats.

Ces réglages ne prétendent pas exposer tous les traitements matériels, ajouter
un nouvel égaliseur ou contrôler le GPU, le NPU et les moteurs vocaux externes.
L’évaluation d’un autre moteur de lecture reste un chantier distinct après la
validation des frontières déjà mises en place.

## Étape suivante proposée

Après validation de cette étape, ajouter une **saisie audio précise** au-dessus
du même port : entrer directement un pourcentage ou une durée, avec unités,
bornes, erreur visible, annulation et vérification du ticket au moment de
valider. Les pas rapides et les retours aux défauts resteraient disponibles.
Cette évolution permettrait notamment de choisir une durée qui n’est pas
atteignable par le pas courant, sans ajouter de filtre ni modifier la narration.
La livraison resterait réservée à une instruction explicite.
