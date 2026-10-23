# Documentation de Sous-titres en voix

[Accueil du projet et téléchargement de l’APK](../README.md)

La documentation d’utilisation et de développement décrit l’édition **0.3.2**. Les notes de révision plus anciennes restent accessibles pour comprendre les changements et les vérifications effectuées à chaque étape.

## Utiliser et vérifier l’application

| Besoin | Document |
|---|---|
| Installer l’application, choisir une voix et comprendre les fonctions | [Présentation et démarrage rapide](../README.md) |
| Vérifier la lecture, le cadrage, les voix et l’accessibilité sur son téléphone | [Parcours de tests sur téléphone](TESTS_TELEPHONE.md) |
| Consulter les moteurs vocaux, modèles et sources documentaires | [Catalogue des voix et moteurs](VOIX_CATALOGUE_2026.md) |
| Comprendre les possibilités et limites CPU, GPU et NPU | [Étude de l’accélération](ETUDE_ACCELERATION.md) |
| Consulter les principales évolutions par version | [Historique des versions](../CHANGELOG.md) |

Le catalogue présente des informations datées et distingue moteurs Android, modèles et passerelles. La présence d’une référence ne prouve ni son installation, ni son fonctionnement hors ligne, ni sa qualité sur un téléphone donné. Le lecteur utilise un moteur vocal Android externe ; il n’intègre pas de nouveau moteur neuronal vocal GPU/NPU.

## Développer et valider

| Besoin | Document |
|---|---|
| Comprendre les composants et les invariants de lecture | [Architecture](ARCHITECTURE.md) |
| Suivre la première étape de modularisation préparatoire | [Modularisation — étape 1](MODULARISATION_ETAPE_1.md) |
| Comprendre les capacités déclarées et les observations du lecteur | [Capacités et observations — étape 2](CAPACITES_ETAPE_2.md) |
| Comprendre les commandes d’image validées, leurs refus et leurs limites | [Commandes d’image — étape 3](COMMANDES_IMAGE_ETAPE_3.md) |
| Utiliser et vérifier les réglages fins et les descriptions de l’image | [Interface d’image — étape 4](INTERFACE_IMAGE_ETAPE_4.md) |
| Comprendre les six commandes du son du film, les fondus et leurs limites | [Commandes audio — étape 5](COMMANDES_AUDIO_ETAPE_5.md) |
| Saisir les valeurs audio et régler égaliseur paramétrique, préampli, balance, mono et limiteur | [Audio avancé — étape 6](AUDIO_EXPERT_ETAPE_6.md) |
| Compiler, exécuter les tests et vérifier l’APK signé | [Procédure de validation](../TESTING.md) |
| Organiser une modification et son intégration dans `main` | [Contribution](../CONTRIBUTING.md) |
| Lire les consignes applicables aux agents de développement | [Consignes de développement](../AGENTS.md) |
| Comprendre la consolidation des branches et la provenance des versions | [Historique des branches](HISTORIQUE_BRANCHES.md) |

Les résultats de tests se rapportent à un commit et à un environnement précis. La [validation de livraison de la 0.3.2](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/39532451903) concerne le commit `311d99052d287bb2227e85255740de201234e770`. Elle ne remplace pas une nouvelle validation après modification, ni les essais manuels sur le téléphone.

## Notes historiques

Ces documents conservent les constats de leur époque, notamment les anciens identifiants d’application, branches, signatures, nombres de tests et échecs intermédiaires. Pour installer ou compiler la version actuelle, suivre les documents ci-dessus.

| Document | Périmètre conservé |
|---|---|
| [Révision 0.3](REVISION_03.md) | Adaptatif, préparation, cadrage, audio et limites introduites dans cette révision |
| [Révision 0.3.1](REVISION_031.md) | Corrections successives et constats des exécutions de livraison |
| [Couverture des demandes 0.3.1](COUVERTURE_DEMANDES_031.md) | Correspondance détaillée entre demandes, code, preuves et limites |
| [Régression Android initiale](REGRESSION_ANDROID.md) | Exception du moteur d’expressions régulières Android et contrôles ajoutés à l’époque |
