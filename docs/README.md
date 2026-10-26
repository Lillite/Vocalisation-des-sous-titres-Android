# Documentation de Sous-titres en voix

[Accueil du projet](../README.md) · [Guide Confort intégral 0.5.0](CONFORT_INTEGRAL_050.md) · [Validation](../TESTING.md)

Les sources **0.5.0 — Confort intégral** sont en cours de validation et de publication. Le dernier APK publié reste la [0.4.0](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.4.0) tant que la publication 0.5.0 n’est pas confirmée. Les guides distinguent le fonctionnement actuel des sources, les résultats exécutés et l’historique des étapes.

## Utiliser la version 0.5.0 sur téléphone

| Besoin | Document |
| --- | --- |
| Installer, choisir une voix et ouvrir une vidéo | [Présentation et démarrage rapide](../README.md) |
| Trouver les huit rubriques, rechercher et régler précisément | [Guide Confort intégral](CONFORT_INTEGRAL_050.md#réglages--huit-rubriques-et-une-recherche) |
| Conserver les phrases, consulter le bilan et relire une interruption | [Lecture et suivi](CONFORT_INTEGRAL_050.md#suivi-des-occurrences-et-bilan) |
| Retrouver la séance ou réparer l’accès à un fichier | [Séances et réparation](CONFORT_INTEGRAL_050.md#séances-mémorisées-et-réparation-daccès) |
| Corriger décalage, dérive et portions | [Synchronisation](CONFORT_INTEGRAL_050.md#synchronisation--décalage-dérive-et-portions) |
| Préparer les voix et vérifier la disponibilité hors ligne | [Conservation hors ligne](CONFORT_INTEGRAL_050.md#conserver-pour-une-écoute-hors-ligne) |
| Traduire avant lecture ou progressivement, reprendre et exporter | [Traduction 0.5.0](CONFORT_INTEGRAL_050.md#traduction-complète-reprise-et-mode-progressif) |
| Configurer les comptes Azure, DeepL et LibreTranslate | [Guide des moteurs de traduction](GUIDE_MOTEURS_TRADUCTION.md) |
| Choisir un moteur vocal externe | [Catalogue des voix et moteurs](VOIX_CATALOGUE_2026.md) |
| Vérifier les parcours sur un téléphone réel | [Tests sur téléphone](TESTS_TELEPHONE.md) |
| Lire tous les changements de la version | [Notes 0.5.0](releases/0.5.0.md), [journal des changements](../CHANGELOG.md) |

La 0.5.0 garde l’identité de l’édition Traduction 0.4.0 et doit être signée avec son certificat durable pour la mettre à jour. La stable 0.3.2 reste une application distincte. Les originaux vidéo ne sont pas modifiés. L’affichage propose original, français, bilingue ou masqué ; la narration utilise la piste choisie indépendamment.

## Références des fonctions conservées

| Sujet | Document |
| --- | --- |
| Cadrage, bandes, corrections, zoom, luminosité et saisie précise | [Interface d’image — étape 4](INTERFACE_IMAGE_ETAPE_4.md) |
| Volumes, fondus et atténuation du film | [Commandes audio — étape 5](COMMANDES_AUDIO_ETAPE_5.md) |
| Préampli, égaliseur, balance, mono et limiteur | [Audio expert — étape 6](AUDIO_EXPERT_ETAPE_6.md) |
| Intégrité des occurrences et contrat initial de traduction complète | [Traduction — référence 0.4.0](TRADUCTION_ETAPE_7.md) |
| CPU/GPU/NPU, ressources et mesures nécessaires | [Étude de l’accélération](ETUDE_ACCELERATION.md) |
| Modèles locaux, catalogues, runtimes et limites | [Étude de traduction locale](ETUDE_TRADUCTION_LOCALE.md) |

Le guide 0.5.0 prévaut pour le mode progressif, la reprise des lots, la conservation, les exports et le choix d’affichage ajoutés après la 0.4.0. Les contrats d’étape conservent leurs résultats et limites historiques.

**Aucun OCR, moteur local de traduction, import Hugging Face ou service de lecture durable en arrière-plan n’est intégré ici.** Réutiliser une traduction conservée sans réseau ne constitue pas une nouvelle traduction locale. Les profils de préparation ne prouvent pas un usage neuronal du GPU/NPU.

## Développer, tester et contribuer

| Besoin | Document |
| --- | --- |
| Vérifier la couverture de toutes les demandes 0.5.0 et les tests associés | [Matrice de couverture 0.5.0](COUVERTURE_DEMANDES_050.md) |
| Résultats attachés au commit distribué, compilation et contrôle APK | [TESTING.md](../TESTING.md) |
| Architecture et invariants de lecture | [Architecture](ARCHITECTURE.md) |
| Interfaces et composition des composants | [Modularisation — étape 1](MODULARISATION_ETAPE_1.md) |
| Capacités déclarées, demandes, configuration et observations | [Capacités — étape 2](CAPACITES_ETAPE_2.md) |
| Contrat des commandes d’image | [Commandes d’image — étape 3](COMMANDES_IMAGE_ETAPE_3.md) |
| Fusion des modifications vérifiées dans main | [Contribution](../CONTRIBUTING.md), [AGENTS.md](../AGENTS.md) |
| Archives, commits, branches et sources des APK précédents | [Historique](HISTORIQUE_BRANCHES.md) |
| Démonstration anglaise de cinq occurrences | [SRT synthétique](../examples/essai-traduction-en.srt) |

La validation de la 0.5.0 reste à terminer. Les chiffres de la 0.4.0 et de ses maintenances ne sont pas des résultats de ces nouvelles sources. Un test sur émulateur ne déclare pas réussis TalkBack réel, l’écoute d’une voix commerciale, un compte API personnel, la fidélité linguistique, l’autonomie ou les performances du OnePlus 15.

## Versions et développement antérieurs

| Version ou document | Repère |
| --- | --- |
| [0.4.0 — Traduction](releases/0.4.0.md) | Version publiée le 24 octobre 2026 ; sept étapes consolidées |
| [0.3.2 — ancienne stable](releases/0.3.2.md) | Identité et signature distinctes |
| [0.3.1 — préversion Cinéma](releases/0.3.1-cinema-test.md) | Cadrage et corrections de cette série |
| [0.2.0 — préversion Cinéma](releases/0.2.0-cinema-test.md) | Première série Cinéma |
| [0.1.0 — première préversion](releases/0.1.0-test.md) | Première livraison |
| [Révision 0.3](REVISION_03.md) | Adaptatif, préparation, cadrage et audio de l’époque |
| [Révision 0.3.1](REVISION_031.md) | Corrections et constats de validation |
| [Couverture des demandes 0.3.1](COUVERTURE_DEMANDES_031.md) | Correspondance demandes/code/preuves |
| [Régression Android initiale](REGRESSION_ANDROID.md) | Défaut d’expressions régulières et contrôles ajoutés |

Les anciens numéros, signatures, branches et effectifs restent historiques. Pour l’APK actuel, suivre le lien disponible en tête du README et vérifier les preuves de cette publication.
