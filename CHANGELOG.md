# Journal des changements

Ce journal présente les étapes principales vérifiables dans l’historique du dépôt. Les rapports détaillés restent accessibles dans l’[index documentaire](docs/README.md). Les numéros ci-dessous désignent les versions de l’application ; les correspondances avec les publications GitHub figurent dans l’[historique](docs/HISTORIQUE_BRANCHES.md).

## 0.5.0 — Confort intégral, séances et traduction progressive

**Validation et publication en cours.** [Guide complet](docs/CONFORT_INTEGRAL_050.md) · [Notes de version](docs/releases/0.5.0.md). Les résultats du commit effectivement distribué seront attachés dans [TESTING.md](TESTING.md).

- Réglages répartis entre huit rubriques et recherche sans accents vers les commandes réelles ; champs numériques précis, pas et présélections partagent la validation.
- Effet immédiat explicite : Fermer conserve les réglages ; Annuler modifications restaure ceux de l’ouverture sans prétendre annuler suppressions, exports ou appels déjà confirmés.
- Profils de préparation avec actualisation immédiate ; valeurs de départ 512 Mio, 256 segments et 120 secondes, bornes relevées à 8 192 Mio, 4 096 segments et 7 200 secondes.
- Profil Lecture intégrale, Confort/Adaptatif et compréhension confortable préservent les phrases ; le mode Continu demande une confirmation distincte permettant les coupures.
- Journal par occurrence avec identifiants hachés, temps, états et compteurs ; aucune phrase dans le journal ordinaire, bilan distinct des répétitions manuelles et relecture d’une interruption en pause.
- Séances par vidéo conservant position, pistes, fichier externe, moteur/voix/mode, paramètres, image, synchronisation et métadonnées de traduction ; réouverture en pause et réparation guidée des accès.
- Repère distinct pour la file vocale réellement inachevée : une reprise après fermeture du processus récupère les répliques expirées à l’écran, sans restaurer les files volontairement abandonnées par déplacement.
- Décalages séparés des sous-titres et de la narration, correction à deux points et interpolation continue ; portions explicites avec début/fin source et décalage signé pour les scènes ajoutées/supprimées, aperçu transactionnel et conservation des identités et des répliques traversant une limite.
- Conservation vocale durable par œuvre, estimation d’espace, fichiers validés, préparation reprenable et suppression séparée des voix/textes/index ; disponibilité hors ligne conditionnée à une source complète.
- Segmentation tenant compte de la langue et réutilisation des durées audio déjà mesurées pour améliorer l’anticipation.
- Reprise durable des lots de traduction validés, marqueur d’appel au résultat incertain et plafond local de nouveaux caractères.
- Traduction progressive facultative avec tampon initial d’une minute et arrêt avant une occurrence indisponible ; aucune reprise automatique et aucune substitution par l’original.
- Options DeepL pour contexte, glossaire distant/révision, modèle et registre ; interrogation réelle du quota et comparaison d’échantillon explicitement confirmée.
- Proposition locale de langue à confirmer, alertes linguistiques heuristiques, correction d’une occurrence, affichage original/français/bilingue/masqué et exports SRT/VTT réimportables.
- Identité Android conservée depuis la 0.4.0 ; même certificat durable exigé pour la mise à jour. Aucun OCR, runtime local de traduction, nouveau moteur vocal intégré ou service de lecture durable en arrière-plan n’est annoncé.

La 0.5.0 conserve les fonctions des étapes précédentes. Les preuves de la 0.4.0 ci-dessous restent historiques et ne valident pas ces nouvelles sources. Le détail des limites figure dans le guide et les résultats réellement exécutés dans TESTING.md.

## 0.4.0 — Traduction avant lecture et consolidation des étapes 1 à 6

**Publiée le 24 octobre 2026.** [Télécharger la version](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.4.0) · [Sources exactes `32864ba`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/tree/32864ba732d834e71d87b887ae63f0d7bc09eb8c) · [Notes de version](docs/releases/0.4.0.md).

- Nouvelle édition `fr.soustitresvoix.player.translation`, installable à côté de la stable 0.3.2. La clé de signature de cette dernière n’étant plus accessible, la 0.4.0 utilise une signature distincte sauvegardée hors du dépôt. Les réglages et fichiers importés privés sont propres à chaque édition ; aucune désinstallation de la 0.3.2 n’est nécessaire.
- Traduction complète de la piste textuelle choisie vers le français avant lecture : Azure Translator, DeepL avec choix explicite du point d’accès, et serveur LibreTranslate HTTPS. La narration reçoit uniquement la traduction validée ; l’affichage vidéo conserve la piste originale.
- Indexation sans réseau, langue source explicite, descriptions et liens propres à chaque moteur, estimation du volume puis confirmation avant tout envoi. Aucun compte ou abonnement créé automatiquement.
- Identités, minutages, panneaux, occurrences simultanées et répétitions légitimes conservés. Lots bornés, réponses contrôlées et activation atomique ; une occurrence absente ou divergente provoque un refus perceptible sans substitution par le texte original.
- Cache privé atomique de 32 Mio, lié au contenu, aux langues et au moteur/serveur. Réutilisation sans API, sans clé et hors ligne lorsqu’une piste complète correspondant aux paramètres est encore disponible.
- Mémorisation facultative des clés par Android Keystore/AES-GCM ; séparation des clés et caches LibreTranslate selon le serveur, absence de relance automatique, HTTPS sans redirection et erreurs expurgées.
- Annulations et changements de vidéo/piste protégés par génération et tâche. Correction de la réplique déjà visible lors de l’activation : remise en file explicite pendant la pause, sans reprise automatique.
- Correction de la perte de la réplique courante après le seul changement de piste audio ; une ancienne réplique n’est pas réutilisée après un changement de piste textuelle. Les callbacks normaux pendant l’attente d’une traduction ne produisent plus une fausse erreur de correspondance.
- Guide détaillé des comptes, moteurs et candidats locaux, installation et limites depuis Android. Aucun runtime de traduction local ni OCR intégré dans cette version.
- Consolidation de la modularisation, des diagnostics, des commandes et interfaces image/audio des étapes 1 à 6 ; le DSP du film conserve son activation facultative et ses limites PCM16 mono/stéréo.
- Correction du mécanisme de publication GitHub : négociation JSON pour le téléchargement d’un artefact Actions, téléchargement binaire pour les assets et CDN, et jeton limité à l’API GitHub. Vérification du commit source, de la paire d’APK testée, du contenu hors signatures, de l’identité, du certificat et des fichiers téléchargés avant publication.
- Fusion des travaux précédents en conservant leur historique, archivage par tags des 14 branches de travail intégrées puis retrait de ces branches. Les six branches de sauvegarde restent disponibles ; les repères figurent dans l’[historique des branches](docs/HISTORIQUE_BRANCHES.md).

Les sept étapes sont comprises dans le même APK :

| Étape | Fonction consolidée |
| --- | --- |
| 1 — Modularisation | Composition et adaptateurs explicites pour lecture, narration, image, audio et indexation ; maintien des règles de pause, reprise et conservation des occurrences. |
| 2 — Capacités et observations | Diagnostic distinguant demandes, configuration et observations attribuables ; invalidation des faits après changement de média. |
| 3 — Commandes d’image | Validation atomique, protections HDR, tickets de génération, comparaison et soumissions d’effets distinctes des mesures de pixels. |
| 4 — Interface d’image | Pas fins et saisie précise, explications des réglages, cadrage protégé, marges, zoom et luminosité de l’écran. |
| 5 — Son du film | Volumes, fondus, atténuation spectrale facultative et commandes qui conservent le transport et la narration. |
| 6 — Audio expert | Préampli, cinq bandes d’égaliseur fréquence/gain/Q, balance, mono et limiteur de crêtes lié ; traitement désactivé par défaut et limité au PCM16 mono/stéréo reçu. |
| 7 — Traduction | Préparation complète vers le français avant vocalisation, trois fournisseurs et quatre choix de point d’accès, cache privé et clés facultativement chiffrées. |

La validation de `main` a réussi dans le [run 40053284969](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40053284969) : **612 exécutions JVM/Android de 306 méthodes distinctes**, sans échec, erreur ni méthode ignorée. Les outils totalisent **39 tests Python** réussis ; lint signale **zéro erreur et 51 avertissements dans chaque variante**. Le [run 40054994161](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40054994161) atteste la publication réussie. L’APK mesure 5 066 480 octets ; son SHA-256 est `30d1126392a9fd995867f599b12436b1e2d9e8e1d41bc60d20dca76c9b88f298`.

Les résultats, les erreurs intermédiaires et leurs corrections figurent dans [TESTING.md](TESTING.md). Les API personnelles ou payantes, les voix commerciales, la fidélité linguistique, TalkBack et les performances sur téléphone restent à essayer séparément. Aucun essai contrôlé n’établit une absence absolue de défauts. [Guide de traduction](docs/GUIDE_MOTEURS_TRADUCTION.md) · [Contrat d’intégration](docs/TRADUCTION_ETAPE_7.md).

### Maintenance du dépôt après publication

- Page d’accueil et index documentaire actualisés pour la 0.4.0 ; instructions sur téléphone, exemple SRT anglais, historique des archives et repères des six étapes précédentes organisés après la publication.
- Correction de la reconnaissance d’une fusion documentaire avec l’API GitHub 2026 : vérification indépendante de la PR fermée et du commit Git à deux parents, en conservant les contrôles de tête, de validation Android et d’archive. Le champ `merge_commit_sha`, retiré par GitHub, n’est plus utilisé par ce rangement.
- Trois régressions ajoutées aux sept tests de rangement existants ; l’outillage courant totalise 42 tests Python. Les résultats de la référence APK restent ceux de ses sources publiées (39 tests d’outils), avec leurs preuves dans [TESTING.md](TESTING.md).

Ces changements de documentation et d’outillage ne remplacent pas l’APK publié et ne déplacent pas son tag. Les contrôles CI de chaque changement restent attachés à son propre commit.

## Historique des étapes 1 à 6 — incluses dans la 0.4.0

Les sections suivantes détaillent leur développement antérieur à la publication. Les noms `work/…` sont des noms historiques de branches désormais archivées ; les sauvegardes `backup/…` restent des points de retour. Les étapes ne nécessitent pas l’installation d’APK séparés.

### Étape 6 — Saisie et traitements audio avancés

- Point de retour de l’étape 5 conservé au commit `647de93e99ef778cfa3199f3c225e1fc0b759baf`, sur `backup/etape-5-validee-20261023-647de93` ; travail séparé sur `work/etape-6-audio-expert-20261023`.
- Saisie des pourcentages décimaux et des millisecondes entières : point ou virgule, validation décimale avant conversion, bornes explicites, annulation sans mutation et conservation du texte refusé. Maintien des pas et des retours aux défauts historiques.
- Nouveau processeur du film après le filtre spectral historique : préampli, cinq bandes paramétriques avec fréquence/gain/Q, balance, mélange mono et limiteur lié entre les canaux. Traitement général désactivé par défaut, paramètres persistants et retours aux défauts ciblés.
- Traitement limité au PCM16 mono/stéréo réellement reçu. Les bandes au-delà du domaine du format sont signalées sans déplacement automatique de leur fréquence ; le multicanal, le flottant, le passthrough et l’offload ne sont pas annoncés comme traités.
- Transitions de chaîne sur 20 ms avec deux jeux de filtres stables ; conservation des états lors d’un changement du seul limiteur ou d’un paramètre latent. Plafond numérique de limiteur effectif dès consommation, relâchement exponentiel et retour progressif au bypass exact.
- Paramètres regroupés dans une configuration immuable et validée. Persistance versionnée indépendante des préférences vocales ; configuration enregistrée invalide ramenée aux défauts avec DSP désactivé.
- Observations séparant soumission, consommation, cible, transition, format du dernier flux, bandes inapplicables, compteurs et crête PCM quantifiée. Ces observations ne sont pas présentées comme attribuées automatiquement à la nouvelle vidéo ni comme une mesure acoustique.
- Propriété explicite des sous-dialogues, ticket d’ouverture conservé, refus visibles et fermeture des enfants avec leur panneau propriétaire. Les réglages compagnons sont relus avant une modification individuelle.
- Inclusion de tous les paramètres DSP canoniques dans l’empreinte des réglages du banc d’essai ; distinction des configurations et restauration de l’identité avec les mêmes préférences.
- Tests de parsing, politique, réponse fréquentielle, canaux, limiteur, transitions, format et intégration, à exécuter avec les régressions de narration existantes. Les résultats et corrections doivent être attachés aux sources réellement testées dans la demande de fusion et `TESTING.md`.

Le guide [Saisie et traitements audio avancés — étape 6](docs/AUDIO_EXPERT_ETAPE_6.md)
décrit tous les paramètres, les règles d’application et les limites. Aucun
algorithme de vocalisation n’est remplacé. Cette étape n’avait pas de publication
APK séparée ; elle est maintenant intégrée à la 0.4.0. La dynamique contrôlée,
les profils/comparaisons et les formats multicanaux restent des propositions à décider et à valider séparément.

### Étape 5 — Commandes du son du film

- Point de retour de l’étape 4 conservé au commit `8feafda50a272cbca9972cf1c948dce5afd269e7`, sur `backup/etape-4-validee-20261023-8feafda` ; travail séparé sur `work/etape-5-commandes-audio-20261023`.
- Contrat audio indépendant d’Android pour les deux niveaux, les trois durées et l’activation du filtre spectral existant ; validation de la durée de vie, des valeurs finies et bornées et de la capacité spectrale.
- Écriture groupée des préférences après acceptation, refus sans effet partiel et retour immédiat pour les demandes identiques. Les niveaux modifiés remplacent le fondu courant ; les durées modifiées concernent les transitions suivantes.
- Raccordement de Volumes rapides, des réglages avancés, des deux curseurs généraux et du geste de volume. Descriptions détaillées, pas existants, remises aux valeurs par défaut par paramètre et état du son en lecture seule.
- Protection des demandes issues d’une ancienne ouverture, y compris à URI identique, et conservation des préférences audio entre les médias. Les volumes enregistrés non finis reviennent à une valeur par défaut finie à la lecture.
- Maintien du processeur PCM, des règles de narration, de synthèse, d’extraction et de rendu ; aucun nouveau moteur, dépendance ou changement de version.
- Contrôles de validation, fondus non nuls, files vocales, répétitions légitimes, interruptions, paramètres et cycle de vie. Les résultats sont attachés aux sources réellement exécutées dans la demande de fusion et dans `TESTING.md`.
- La revue a retiré le nouveau rafraîchissement général du chemin des commandes audio : dans l’activité, il pouvait aussi déclencher une préparation vocale. Les contrôles audio actualisent leur propre présentation. Un refus des curseurs généraux est affiché dans leur dialogue, sans passer par le message global qui pouvait quitter le plein écran.

Le guide [Commandes du son du film — étape 5](docs/COMMANDES_AUDIO_ETAPE_5.md)
décrit chaque paramètre et ses limites. Cette étape est maintenant intégrée à
`main` et à la 0.4.0 ; la saisie audio précise proposée ensuite est apportée par
l’étape 6, avec validation des pourcentages et des millisecondes.

### Étape 4 — Interface d’image

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
restent rattachés au commit et à l’exécution réellement testés. Cette étape est
maintenant intégrée à `main` et à la 0.4.0. Les commandes audio ont ensuite été
réalisées aux étapes 5 et 6 ; l’évaluation d’autres moteurs de lecture reste distincte.

### Étape 3 — Commandes d’image

- Point de retour de l’étape 2 conservé au commit `0319dcb9b0cfd71be4231849306f0b248e1dbe02`, sur `backup/etape-2-validee-20261022-0319dcb` ; travail sur `work/etape-3-commandes-image-20261023`.
- Contrat indépendant d’Android pour régler atomiquement contraste et luminosité de l’image, puis activer ou désactiver explicitement la comparaison avec l’original.
- Validation des valeurs finies et des bornes existantes, de la génération du média, de la fermeture, de la disponibilité et de la protection HDR ; refus explicites sans modification partielle.
- Idempotence des demandes identiques et distinction entre réglages acceptés, plan effectivement soumis et pixels non mesurés ; invalidation des tickets et des faits de soumission au remplacement du média.
- Raccordement des contrôles d’image existants, avec conservation du regroupement des effets, du bypass, du cadrage `Presentation` et des algorithmes de voix et d’audio.
- Attribution des événements de format au média courant avant de mettre à jour la protection d’image ; maintien du rejet des callbacks anciens ou non attribuables.
- Quatre nouveaux tests du noyau, cinq tests JVM de l’application et un scénario Android à exécuter avec toutes les régressions précédentes : effectifs exigés 44, 46 et 70.

Les critères et limites figurent dans [Commandes d’image — étape 3](docs/COMMANDES_IMAGE_ETAPE_3.md).
Les résultats restent rattachés au commit testé et à sa demande de fusion.
Cette étape n’avait pas de publication APK séparée ; elle est maintenant intégrée
à `main` et à la 0.4.0. L’interface d’image a ensuite été réalisée à l’étape 4.

### Étape 2 — Capacités et observations

- Point de retour de l’étape 1 conservé au commit `a093e791b1413aefef6add027225b88c20657c6c` ; nouvelle branche de travail séparée.
- Modèle de diagnostic sans dépendance Android ou Media3, avec disponibilité, provenance et faits demandés/configurés/observés distincts.
- Raccordement au diagnostic existant et au panneau des codecs, sans commande de lecture, de traitement ou de synthèse déclenchée par la consultation.
- Identité propre à chaque ouverture, même pour une URI identique ; invalidation des observations lors du remplacement et de la fermeture, avec rejet des événements anciens ou non attribuables.
- Déclarations du matériel, métadonnées du média, configuration graphique et compteurs audio explicitement limités à ce qu’ils établissent.
- Quatre nouveaux tests du noyau, trois tests JVM de l’application et un scénario Android de consultation/copie ; maintien des tests antérieurs et des algorithmes de vocalisation.

Cette étape est maintenant intégrée à `main` et à la 0.4.0, sans APK séparé.
Les contrôles et les limites sont décrits dans [Capacités et observations — étape 2](docs/CAPACITES_ETAPE_2.md).

### Étape 1 — Modularisation

- Point de retour conservé au commit `32e988654fc44faa80e1fb69c98cf9ca29e9f5b0`, sur une branche de sauvegarde ; travail séparé avant la livraison demandée ultérieurement.
- Composition explicite des composants de lecture, audio du film, rendu vidéo, extraction/indexation et sortie vocale.
- Assemblage ExoPlayer déplacé dans un adaptateur Media3 ; interface `Player` commune aux vues et accès vidéo spécifiques isolés.
- Adaptateurs autour de `NarrationMachine` et `AndroidSpeech`, sans réécriture de leurs règles, callbacks, cache ou planification.
- Chemins d’extraction en lecture et d’indexation complète reliés au même contrat, en conservant les identités et horaires des occurrences.
- Contrôles de composition et de conservation des transitions, avec maintien des régressions SSA et des tests Android existants.

Cette étape est maintenant intégrée à `main` et à la 0.4.0, sans APK séparé.
Voir le [périmètre et les critères de contrôle](docs/MODULARISATION_ETAPE_1.md).

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
