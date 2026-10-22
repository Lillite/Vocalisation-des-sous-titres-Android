# Architecture et invariants de lecture

[Index de la documentation](README.md) · [Compilation et validation](../TESTING.md)

Cette vue décrit l’édition **0.3.2**. Le projet sépare la logique de narration indépendante d’Android, les adaptateurs du lecteur et l’interface. Les invariants ci-dessous sont des exigences à préserver lors des modifications ; les résultats de vérification restent attachés au commit réellement testé.

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

`VisualTools` et `CinemaVideoRenderer` appliquent le cadrage et les effets graphiques. Les deux modes « image entière » réinitialisent zoom, déplacements et marges retirées. Le recadrage volontaire et la suppression de marges demandent une action distincte. L’analyse de quelques images ne garantit pas qu’aucun texte ne figure dans une marge à un autre instant. Les traitements visuels concernés restent limités au SDR reconnu.

La baisse de volume agit sur le mix du film. Le traitement expérimental de bande vocale PCM16 n’est pas une séparation des voix et des ambiances. Les diagnostics et bancs d’essai sont locaux ; ils ne prouvent pas l’utilisation d’un backend vocal GPU/NPU ni une amélioration sur un appareil non mesuré.

## Fichiers et confidentialité

Le sélecteur Android accorde un accès ciblé aux documents choisis. Les sous-titres externes sont copiés dans le répertoire privé de l’application ; les fichiers originaux ne sont pas modifiés. Le lecteur ne demande pas d’autorisation Internet, de microphone, de caméra ou d’accès général au stockage. Les liens du catalogue s’ouvrent dans le navigateur sur demande ; le moteur vocal externe possède ses propres permissions.

La synthèse des sous-titres reste distincte des annonces de l’interface. Le passage de l’activité en arrière-plan arrête la lecture. Les règles d’accessibilité et l’absence de reprise inattendue doivent aussi être vérifiées sur téléphone.

## Vérification et provenance

La [procédure de validation](../TESTING.md) distingue tests JVM, Lint, compilation, instrumentation Android, contrôle du manifeste et vérification de signature. Les tests utilisent des sous-titres et médias synthétiques. Le service vocal de fixture permet de contrôler les requêtes et les transitions ; il ne mesure pas le naturel d’une voix externe.

La démonstration principale est générée par Gradle à partir du SRT versionné. Des petites fixtures ASS/MKV sont aussi conservées dans les ressources de test. FFmpeg sert à la génération de la démonstration dans l’environnement de compilation ; il n’est pas intégré au lecteur Android.

Pour l’APK 0.3.2, le commit source de référence est `311d99052d287bb2227e85255740de201234e770` et la [validation de livraison correspondante](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/39532451903) est identifiable séparément des futures validations de `main`. Les [essais sur téléphone](TESTS_TELEPHONE.md) complètent ces preuves pour les voix, TalkBack, les codecs, le rendu et les séances prolongées.
