# Documentation de Sous-titres en voix

[Accueil et téléchargement](../README.md) · [Changements](../CHANGELOG.md) · [Validation](../TESTING.md) · [Signaler un problème](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/issues/new/choose)

**Version actuelle : 0.5.1, publiée le 27 octobre 2026.** **[Télécharger l’APK Android](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/download/v0.5.1/SousTitresVoix-0.5.1-correctifs.apk)** · [Notes de version](releases/0.5.1.md). Les sources ont réussi 524 tests avant fusion ; la construction après fusion a vérifié le même contenu applicatif sans répéter les suites, puis la publication signée a été contrôlée.

## Installer et utiliser sur téléphone

| Besoin | Guide |
| --- | --- |
| Installer, choisir une voix et ouvrir une vidéo | [Démarrage rapide](../README.md#installer-et-commencer-sur-android) |
| Comprendre les corrections des réglages et de DeepL | [Nouveautés 0.5.1](releases/0.5.1.md) |
| Réglages, lecture intégrale, séances, synchronisation et stockage hors ligne | [Guide Confort intégral](CONFORT_INTEGRAL_050.md) — fonctions introduites en 0.5.0 et conservées |
| Régler cadrage, zoom, bandes, contraste et luminosité | [Interface d’image](INTERFACE_IMAGE_ETAPE_4.md) |
| Régler les volumes, fondus et l’atténuation du film | [Commandes audio](COMMANDES_AUDIO_ETAPE_5.md) |
| Régler préampli, égaliseur, balance, mono et limiteur | [Audio expert](AUDIO_EXPERT_ETAPE_6.md) |
| Choisir un moteur vocal externe | [Catalogue des voix et moteurs](VOIX_CATALOGUE_2026.md) |
| Vérifier la mise à jour sur un téléphone réel | [Parcours manuel 0.5.1](TESTS_TELEPHONE.md) |

La série 0.5 conserve l’identité Android et le certificat durable de l’édition Traduction 0.4.0 ; la 0.3.2 possède une autre identité. Les versions publiées et leurs consignes d’installation restent accessibles depuis l’accueil. Les originaux vidéo ne sont pas modifiés.

## Traduire les sous-titres

| Besoin | Guide |
| --- | --- |
| Choisir une offre gratuite, comprendre Google et les limites DeepL | [Traduction gratuite et limites](TRADUCTION_GRATUITE_ET_LIMITES.md) |
| Configurer Azure, DeepL ou LibreTranslate depuis Android | [Moteurs, comptes, options et dépannage](GUIDE_MOTEURS_TRADUCTION.md) |
| Traduction complète/progressive, reprise, affichage, correction, conservation et export | [Parcours Confort intégral](CONFORT_INTEGRAL_050.md#traduction-complète-reprise-et-mode-progressif) |
| Comprendre le contrat initial des occurrences et de traduction complète | [Référence historique 0.4.0](TRADUCTION_ETAPE_7.md) |
| Étudier un futur traducteur exécuté sur téléphone | [Traduction locale : candidats et limites](ETUDE_TRADUCTION_LOCALE.md) |
| Essayer une piste synthétique anglaise | [Exemple SRT](../examples/essai-traduction-en.srt) |

**Aucun OCR, traducteur local, import Hugging Face ou service de lecture durable en arrière-plan n’est intégré.** Une traduction conservée peut être réutilisée sans réseau ; cette réutilisation ne réalise pas une nouvelle traduction locale. Les comptes et quotas réels restent à vérifier auprès du fournisseur.

## Développer, vérifier et contribuer

| Besoin | Document |
| --- | --- |
| Résultats 0.5.1, provenance des APK et procédure de validation | [TESTING.md](../TESTING.md) |
| Repères durables de la validation et de la publication 0.5.1 | [Preuve structurée 0.5.1](verification/0.5.1.json) |
| Fusion avec validation préalable, puis livraison sans répéter les suites | [Contribution](../CONTRIBUTING.md), [consignes de développement](../AGENTS.md) |
| Invariants et composition des composants | [Architecture actuelle](ARCHITECTURE.md) |
| Interfaces de session et responsabilités des composants | [Modularisation — étape 1](MODULARISATION_ETAPE_1.md) |
| Capacités déclarées et observations effectives | [Capacités — étape 2](CAPACITES_ETAPE_2.md) |
| Contrat de validation des commandes d’image | [Commandes d’image — étape 3](COMMANDES_IMAGE_ETAPE_3.md) |
| Distinguer CPU/GPU/NPU et mesures nécessaires | [Étude de l’accélération](ETUDE_ACCELERATION.md) |
| Demandes et régressions ajoutées en 0.5.0 | [Matrice de couverture 0.5.0](COUVERTURE_DEMANDES_050.md) |
| Preuves conservées de la 0.5.0 | [Inventaires et publication](verification/0.5.0.json), [charge du noyau](verification/0.5.0-additional-stress.json), [relevés Android](verification/0.5.0-device-metrics.json) |
| Commits exacts, branches et archives | [Historique du dépôt](HISTORIQUE_BRANCHES.md) |

Les **524 tests de la 0.5.1** se répartissent en 178 tests noyau, 254 JVM application et 92 tests instrumentés Android 36. Les preuves et effectifs anciens restent attachés à leurs propres sources. Les tests sur émulateur ne remplacent pas TalkBack réel, l’écoute d’une voix externe, l’évaluation d’une traduction, l’autonomie ou les performances du téléphone.

## Versions et archives

| Version ou document | Statut et périmètre |
| --- | --- |
| [0.5.1 — réglages stables et traduction résiliente](releases/0.5.1.md) | Version actuelle ; publiée le 27 octobre 2026 |
| [0.5.0 — Confort intégral](releases/0.5.0.md) | Publiée le 26 octobre 2026 ; fonctions conservées par la 0.5.1 |
| [0.4.0 — Traduction](releases/0.4.0.md) | Publiée le 24 octobre 2026 ; consolidation des sept étapes |
| [0.3.2 — ancienne stable](releases/0.3.2.md) | Identité et signature distinctes de la série Traduction |
| [0.3.1 — préversion Cinéma](releases/0.3.1-cinema-test.md) | Cadrage et corrections de cette série |
| [0.2.0 — préversion Cinéma](releases/0.2.0-cinema-test.md) | Première série Cinéma |
| [0.1.0 — première préversion](releases/0.1.0-test.md) | Première livraison |
| [Révision 0.3](REVISION_03.md) | Adaptatif, préparation, cadrage et audio de l’époque |
| [Révision 0.3.1](REVISION_031.md) | Corrections et constats de cette révision |
| [Couverture des demandes 0.3.1](COUVERTURE_DEMANDES_031.md) | Correspondance historique entre demandes, code et preuves |
| [Régression Android initiale](REGRESSION_ANDROID.md) | Défaut d’expressions régulières et contrôles ajoutés |

Les documents d’étape et de version gardent leurs constats d’origine. Les pages courantes — accueil, moteurs de traduction, services gratuits, architecture, tests et contribution — indiquent le fonctionnement à retenir aujourd’hui. Pour installer, suivre le lien disponible en tête de l’accueil.
