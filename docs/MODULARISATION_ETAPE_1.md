# Modularisation — étape 1

> **Référence historique : cette étape a été intégrée à `main` et à la [version 0.4.0 — édition Traduction](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.4.0).** Les mentions ci-dessous de travail séparé, de livraison future, d’ancienne version ou d’effectifs intermédiaires décrivent le développement de cette étape. La branche de travail est conservée par son [tag d’archive](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/tree/archive/20261024/work/etape-1-modularisation-20261022). Ces fonctions sont conservées en 0.5.2. Pour installer et vérifier la version actuelle : [accueil](../README.md), [notes 0.5.2](releases/0.5.2.md) et [TESTING.md](../TESTING.md).

[Architecture](ARCHITECTURE.md) · [Validation](../TESTING.md) · [Index](README.md)

## Périmètre et sauvegarde

Cette étape répond à la demande du 22 octobre 2026 : préparer des composants
remplaçables en conservant Media3, avec priorité absolue à la vocalisation. Le
développement est effectué séparément avant une future livraison d’application.

- Base : `32e988654fc44faa80e1fb69c98cf9ca29e9f5b0`.
- Sauvegarde distante : [`backup/avant-modularisation-20261022-32e9886`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/tree/backup/avant-modularisation-20261022-32e9886).
- Branche de travail : `work/etape-1-modularisation-20261022`.
- La demande actuelle conserve la branche de référence et reporte la livraison ;
  elle précède la règle habituelle d’intégration décrite dans CONTRIBUTING.
- Identité d’installation, numéro de version et dépendances multimédia conservés.
  Aucun moteur supplémentaire, OCR ou service réseau n’est ajouté dans cette étape.

La branche de sauvegarde et le commit de base permettent de retrouver exactement
les sources initiales. Une nouvelle étape doit partir du travail vérifié, tout en
gardant ce point de retour.

## Changement technique

`PlaybackComponents` est le point de composition injecté à la construction de
`PlaybackSession`. Ses implémentations par défaut reproduisent le montage existant.
Les objets injectés sont réellement utilisés par les commandes, les vues, les
réglages, les diagnostics, la préparation et la fermeture.

1. `Media3PlaybackBackend` possède ExoPlayer et reprend son assemblage. Les vues
   reçoivent toujours la même instance via `Player` pour toute la session.
2. `Media3MovieAudio` possède un seul processeur de bande vocale : contrôles et
   compteur d’échantillons concernent celui qui est installé dans le sink.
3. `CinemaVideoRendererFactory` construit le renderer existant. `VideoOutput`
   transmet les effets, le format et la stratégie de fréquence au même lecteur.
4. `Media3SubtitleSource` fournit la source avec observation des sous-titres et
   l’indexeur complet. Les groupes Media3 et occurrences SSA transitent sans
   conversion ni déduplication supplémentaire.
5. `ExistingNarrationController` transmet chaque événement à `NarrationMachine`.
   Ses effets, identifiants, échéances et instantanés sont rendus sans modification.
6. `AndroidSpeechService` transmet les requêtes au même `AndroidSpeech`. Les
   fonctions de budget et de planification sont transmises telles quelles ; les
   objets de mesures et de durées gardent leur identité.

## Invariants protégés

Les fichiers d’algorithmes existants suivants doivent rester identiques à la base
pour cette étape :

- `core/src/main/kotlin/fr/soustitresvoix/core/NarrationMachine.kt` ;
- `core/src/main/kotlin/fr/soustitresvoix/core/SpeechTools.kt` ;
- `core/src/main/kotlin/fr/soustitresvoix/core/AdaptiveTools.kt` ;
- `core/src/main/kotlin/fr/soustitresvoix/core/SubtitleText.kt` ;
- `app/src/main/java/fr/soustitresvoix/player/AndroidSpeech.kt` ;
- `app/src/main/java/fr/soustitresvoix/player/CueAhead.kt` ;
- `app/src/main/java/fr/soustitresvoix/player/SsaOccurrenceParser.kt` ;
- `app/src/main/java/fr/soustitresvoix/player/FullSubtitleIndexer.kt` ;
- `app/src/main/java/fr/soustitresvoix/player/SpeechBandProcessor.kt` ;
- `app/src/main/java/fr/soustitresvoix/player/CinemaVideoRenderer.kt`.

La session conserve l’ordre des commandes et la responsabilité du focus audio.
L’intention de lecture reste distincte de l’état effectif du décodeur. Une pause
manuelle, une erreur vocale ou un ancien callback ne doivent pas provoquer de
reprise automatique.

Chaque événement textuel source conserve son identité, y compris un panneau,
deux textes identiques simultanés et une répétition légitime ultérieure. Les IDs
des pistes internes et externes suivent toujours les mêmes règles. À la fin du
film, les éléments vocaux encore en attente suivent la politique existante.

Les callbacks d’extraction restent filtrés par `mediaEpoch`. Une tâche d’indexation
annulée, remplacée ou appartenant à un ancien média ne peut remplacer l’index
actuel. La fermeture commence par pause/annulation avant de libérer la voix et le
lecteur. Les adaptateurs ne créent aucun thread ni délai de dispatch supplémentaire.

## Obligations des implémentations injectées

- Créer des instances propres à la session ; ne pas partager lecteur, processeur,
  contrôleur ou service vocal entre sessions.
- Garder stables le `Player`, la sortie vidéo et les objets de mesure exposés.
- Ne pas appeler la session depuis un constructeur ou par réentrance de `speak`.
  Les callbacks vocaux sont livrés sur le thread principal avec l’ID original.
- Les callbacks d’extraction peuvent provenir du thread de parsing ; la session
  assure l’unique passage au thread principal et le contrôle de génération.
- L’indexeur reste une tâche annulable. Une annulation ne change pas l’identité
  d’une autre tâche et ne publie pas son résultat à sa place.
- Transmettre intégralement les effets de narration dans l’ordre, sans filtrer les
  textes ni regrouper les demandes selon leur contenu.

## Validation

Conserver l’intégralité des tests existants et exécuter les commandes de
[TESTING.md](../TESTING.md), en distinguant la JVM, la compilation, lint et les
deux passages instrumentés sur Android 36. Les tests instrumentés SSA passent par
l’activité, la session de production, Media3 et le service vocal de fixture.

Les compléments de cette étape vérifient la délégation du contrôleur, la composition
effective de la session, les callbacks périmés, l’annulation et la pause/reprise.
Les helpers de la suite JVM SSA passent par le nouveau contrôleur tout en gardant
leurs attentes littérales. La barrière réelle du thread de lecture utilisée dans
les tests vidéo reste conservée ; elle ne devient pas un délai arbitraire.

Les résultats sont attachés au commit testé et aux contrôles de sa demande de
fusion. Une compilation des APK de validation ne constitue ni une livraison de
l’application ni une preuve d’exécution de l’instrumentation. Les preuves
automatiques ne remplacent pas les essais sur le OnePlus 15, les voix installées,
TalkBack ou les contenus HDR de l’utilisateur.

## Limites de cette étape

Les contrats de lecture, rendu et extraction conservent explicitement certains
types Media3 (`Player`, `MediaSource`, pistes, effets et analytics). Le protocole
n’est donc pas encore indépendant de cette bibliothèque. Un autre moteur devra
fournir l’adaptation correspondante ou faire évoluer cette frontière dans une
étape ultérieure, avec ses propres tests de compatibilité.

La voix et le film gardent leurs sorties actuelles ; cette étape ne crée pas de
mélangeur PCM commun. Elle ne modifie pas les modes vocaux, le comportement HDR,
les filtres, le nombre de décodeurs ni les performances annoncées. Sa réussite
se mesure à l’isolation des composants et à la conservation du comportement.
