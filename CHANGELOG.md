# Journal des changements

Ce journal présente les étapes principales vérifiables dans l’historique du dépôt. Les rapports détaillés restent accessibles dans l’[index documentaire](docs/README.md). Les numéros ci-dessous désignent les versions de l’application ; les correspondances avec les publications GitHub figurent dans l’[historique](docs/HISTORIQUE_BRANCHES.md).

## En préparation — Commandes du son du film, étape 5

- Point de retour de l’étape 4 conservé au commit `8feafda50a272cbca9972cf1c948dce5afd269e7`, sur `backup/etape-4-validee-20261023-8feafda` ; travail séparé sur `work/etape-5-commandes-audio-20261023`.
- Contrat audio indépendant d’Android pour les deux niveaux, les trois durées et l’activation du filtre spectral existant ; validation de la durée de vie, des valeurs finies et bornées et de la capacité spectrale.
- Écriture groupée des préférences après acceptation, refus sans effet partiel et retour immédiat pour les demandes identiques. Les niveaux modifiés remplacent le fondu courant ; les durées modifiées concernent les transitions suivantes.
- Raccordement de Volumes rapides, des réglages avancés, des deux curseurs généraux et du geste de volume. Descriptions détaillées, pas existants, remises aux valeurs par défaut par paramètre et état du son en lecture seule.
- Protection des demandes issues d’une ancienne ouverture, y compris à URI identique, et conservation des préférences audio entre les médias. Les volumes enregistrés non finis reviennent à une valeur par défaut finie à la lecture.
- Maintien du processeur PCM, des règles de narration, de synthèse, d’extraction et de rendu ; aucun nouveau moteur, dépendance ou changement de version.
- Contrôles de validation, fondus non nuls, files vocales, répétitions légitimes, interruptions, paramètres et cycle de vie. Les résultats sont attachés aux sources réellement exécutées dans la demande de fusion et dans `TESTING.md`.
- La revue a retiré le nouveau rafraîchissement général du chemin des commandes audio : dans l’activité, il pouvait aussi déclencher une préparation vocale. Les contrôles audio actualisent leur propre présentation. Un refus des curseurs généraux est affiché dans leur dialogue, sans passer par le message global qui pouvait quitter le plein écran.

Le guide [Commandes du son du film — étape 5](docs/COMMANDES_AUDIO_ETAPE_5.md)
décrit chaque paramètre et ses limites. L’étape reste séparée de `main`, sans
livraison d’APK. La suite proposée est une saisie audio précise au-dessus du même
contrat, avec validation des pourcentages et des millisecondes.

## En préparation — Interface d’image, étape 4

- Point de retour de l’étape 3 conservé au commit `c96b687f91b2c9f7fadfc44383c8d346047cd30e`, sur `backup/etape-3-validee-20261023-c96b687` ; travail séparé sur `work/etape-4-interface-image-20261023`.
- Réglages fins du contraste et de la luminosité de l’image par pas de 0,01, saisie décimale avec point ou virgule, remise à zéro individuelle ou conjointe ; maintien des raccourcis existants.
- Vérification décimale des bornes avant conversion numérique, refus visibles sans écrêtage, conservation de la saisie en cas d’erreur et maintien du ticket d’ouverture dans les sous-dialogues.
- Affichage distinct des valeurs demandées, du dernier plan transmis, de la disponibilité et des protections ; actualisation en lecture seule par le parcours d’interface existant.
- Descriptions détaillées de chaque paramètre d’image, de cadrage, de marges, de redimensionnement, de zoom, de déplacement et de luminosité de l’écran, avec unités, bornes, procédure et limites.
- Conservation des algorithmes de vocalisation, du mixage, du rendu et du regroupement existant des effets ; aucune bibliothèque supplémentaire ni changement de version.
- Sept scénarios JVM et un scénario Android ajoutés aux fichiers de tests d’image existants, avec conservation des tests précédents ; effectifs exigés 44, 53 et 71.
- Premier passage Android documenté avec deux échecs répétés : défilement avant le second clic de comparaison et synchronisation avec le retour à l’état prêt après envoi des effets. Les contrôles de voix, de transport, d’audio et d’état final restent exigés.

Le périmètre, les explications et les contrôles figurent dans
[Interface d’image — étape 4](docs/INTERFACE_IMAGE_ETAPE_4.md). Les résultats
doivent être rattachés au commit et à l’exécution réellement testés. Cette étape
ne livre pas d’APK et reste séparée de `main` jusqu’à une instruction ultérieure.
La suite proposée concerne les commandes expertes de l’audio du film, puis
l’évaluation séparée d’autres moteurs de lecture.

## En préparation — Commandes d’image, étape 3

- Point de retour de l’étape 2 conservé au commit `0319dcb9b0cfd71be4231849306f0b248e1dbe02`, sur `backup/etape-2-validee-20261022-0319dcb` ; travail sur `work/etape-3-commandes-image-20261023`.
- Contrat indépendant d’Android pour régler atomiquement contraste et luminosité de l’image, puis activer ou désactiver explicitement la comparaison avec l’original.
- Validation des valeurs finies et des bornes existantes, de la génération du média, de la fermeture, de la disponibilité et de la protection HDR ; refus explicites sans modification partielle.
- Idempotence des demandes identiques et distinction entre réglages acceptés, plan effectivement soumis et pixels non mesurés ; invalidation des tickets et des faits de soumission au remplacement du média.
- Raccordement des contrôles d’image existants, avec conservation du regroupement des effets, du bypass, du cadrage `Presentation` et des algorithmes de voix et d’audio.
- Attribution des événements de format au média courant avant de mettre à jour la protection d’image ; maintien du rejet des callbacks anciens ou non attribuables.
- Quatre nouveaux tests du noyau, cinq tests JVM de l’application et un scénario Android à exécuter avec toutes les régressions précédentes : effectifs exigés 44, 46 et 70.

Les critères et limites figurent dans [Commandes d’image — étape 3](docs/COMMANDES_IMAGE_ETAPE_3.md).
Les résultats doivent être rattachés au commit testé et à sa demande de fusion.
Cette étape conserve le numéro de version et ne publie pas d’APK ni ne fusionne
le travail dans `main`. Après validation, la suite proposée est un écran expert
d’image sur ce contrat ; l’audio et les moteurs alternatifs sont des chantiers distincts.

## En préparation — Capacités et observations, étape 2

- Point de retour de l’étape 1 conservé au commit `a093e791b1413aefef6add027225b88c20657c6c` ; nouvelle branche de travail séparée.
- Modèle de diagnostic sans dépendance Android ou Media3, avec disponibilité, provenance et faits demandés/configurés/observés distincts.
- Raccordement au diagnostic existant et au panneau des codecs, sans commande de lecture, de traitement ou de synthèse déclenchée par la consultation.
- Identité propre à chaque ouverture, même pour une URI identique ; invalidation des observations lors du remplacement et de la fermeture, avec rejet des événements anciens ou non attribuables.
- Déclarations du matériel, métadonnées du média, configuration graphique et compteurs audio explicitement limités à ce qu’ils établissent.
- Quatre nouveaux tests du noyau, trois tests JVM de l’application et un scénario Android de consultation/copie ; maintien des tests antérieurs et des algorithmes de vocalisation.

Cette étape ne crée pas de version ni de publication APK. Les contrôles exigés et
les limites sont décrits dans [Capacités et observations — étape 2](docs/CAPACITES_ETAPE_2.md).

## En préparation — Modularisation, étape 1

- Point de retour conservé au commit `32e988654fc44faa80e1fb69c98cf9ca29e9f5b0`, sur une branche de sauvegarde ; travail séparé avant la livraison demandée ultérieurement.
- Composition explicite des composants de lecture, audio du film, rendu vidéo, extraction/indexation et sortie vocale.
- Assemblage ExoPlayer déplacé dans un adaptateur Media3 ; interface `Player` commune aux vues et accès vidéo spécifiques isolés.
- Adaptateurs autour de `NarrationMachine` et `AndroidSpeech`, sans réécriture de leurs règles, callbacks, cache ou planification.
- Chemins d’extraction en lecture et d’indexation complète reliés au même contrat, en conservant les identités et horaires des occurrences.
- Contrôles de composition et de conservation des transitions, avec maintien des régressions SSA et des tests Android existants.

Cette étape ne crée pas de version ni de publication APK. Voir le
[périmètre et les preuves à vérifier](docs/MODULARISATION_ETAPE_1.md).

## Organisation du dépôt — 22 octobre 2026

- Consolidation du travail terminé dans `main`, à partir des sources de la version 0.3.2.
- Conservation de l’historique des branches, avec explication des variantes remplacées et des workflows d’import devenus inutiles.
- Réécriture de la page d’accueil : présentation, mention de réalisation intégrale par IA, téléchargement, démarrage, modes vocaux, cadrage, limites et navigation.
- Index documentaire, règles de contribution, modèles de signalement et consigne durable de fusion du travail terminé dans `main`.
- Regroupement des vérifications dans un workflow Android courant, déclenché sur les demandes de fusion vers `main` et sur `main`.
- Fermeture explicite des dialogues dans le test Android de confidentialité du diagnostic, après une erreur de sélection de fenêtre pendant son nettoyage ; toutes les assertions sont conservées et une erreur de nettoyage ne masque plus l’échec initial.
- Normalisation des titres, tags, descriptions et statuts des quatre publications existantes, avec vérification des APK et des commits sources.

Cette consolidation n’ajoute pas de nouvelle version de l’application : le numéro reste **0.3.2**. Les changements d’organisation ne constituent pas une nouvelle mesure de performance ou de confort sur téléphone.

## 0.3.2 — Correctifs de fiabilité

Sources de référence : [`311d990`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/311d99052d287bb2227e85255740de201234e770). [Publication 0.3.2](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.3.2).

- Correction des répétitions artificielles de panneaux ASS/SSA, avec conservation des occurrences distinctes et des véritables répétitions.
- Régressions couvrant le moteur vocal choisi devenu indisponible, la remise à zéro des marges, les arrondis des volumes, la validation du cache WAV, la pause et la reprise.
- Renforcement des diagnostics et des scénarios de non-perte des sous-titres.
- Identité d’installation `fr.soustitresvoix.player.stable`, versionCode 5 et signature configurable hors du dépôt.
- Validation de la variante release : tests JVM, lint, manifeste, signatures, alignement et deux passages complets des tests instrumentés.

L’[exécution de référence du commit 311d990](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/39532451903) est conservée dans GitHub Actions. Elle concerne ce commit et cet environnement ; les essais de voix réelles, d’accessibilité complète et de performances sur le téléphone restent distincts.

## 0.3.1 — Cadrage et préparation approfondis

Sources de référence : [`b0f32d3`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/b0f32d39fd94cbfef1b018322d5132235dfbb590). [Préversion Cinéma 0.3.1](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.3.1-cinema-test).

- Étirement intégral protégé et modes de cadrage explicites.
- Fiabilisation de la préparation, des changements d’effets et de l’anticipation vocale.
- Diagnostic du cadrage, mesures de lecture et scénarios instrumentés supplémentaires.
- Catalogue documentaire des moteurs vocaux et distinction des fonctions disponibles et non intégrées.

Voir le [compte rendu 0.3.1](docs/REVISION_031.md) et la [couverture détaillée des demandes](docs/COUVERTURE_DEMANDES_031.md).

## 0.3 — Adaptation et diagnostics

Sources de référence : [`0f9b275`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/0f9b2759cff849ffb76c829510788c12965dde32). Voir le [compte rendu 0.3](docs/REVISION_03.md).

- Adaptation de la vitesse même sans cache vocal.
- Indexation explicite et préparation de la vidéo, annulables.
- Analyse prudente des marges encodées avec aperçu et confirmation.
- Diagnostics et vérifications de cycle de vie complémentaires.

Cette étape est conservée dans l’historique des sources ; aucune publication APK distincte 0.3 n’est ajoutée par la réorganisation.

## 0.2 — Édition Cinéma

Sources de référence : [`32ac63d`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/32ac63d7ac226a29f09bc728ad982721f120b333). [Préversion Cinéma 0.2.0](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.2.0-cinema-test).

- Plein écran immersif et commandes de lecture cinéma.
- Choix explicite du moteur TTS, anticipation vocale et cache.
- Mode Adaptatif, réglages de mixage et tests Android étendus.

## 0.1.0 — Première édition

Sources de référence : [`8028f5d`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/8028f5dd7619826c41cb3bfb1b1c3e1648eb2761). [Préversion 0.1.0](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.1.0-test).

- Lecture locale et vocalisation de sous-titres textuels avec les modes Confort et Continu.
- Démonstration synthétique, noyau testable et premières régressions Android.
- Première chaîne de compilation et APK de test.
