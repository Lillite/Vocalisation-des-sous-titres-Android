# Documentation de Sous-titres en voix

[Accueil du projet](../README.md) · [Télécharger la version 0.4.0](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.4.0)

La version publiée **0.4.0 — édition Traduction** rassemble les **sept étapes** de développement : modularisation, diagnostics, commandes d’image, interface d’image, commandes audio, traitements audio avancés et traduction avant vocalisation. Les guides ci-dessous distinguent l’utilisation actuelle, les preuves de validation et l’historique du développement.

## Commencer sur Android

| Besoin | Document |
|---|---|
| Télécharger l’APK, installer l’édition et découvrir ses commandes | [Présentation et démarrage rapide](../README.md) |
| Choisir un traducteur, créer son compte et saisir ses paramètres | [Guide des moteurs de traduction](GUIDE_MOTEURS_TRADUCTION.md) |
| Vérifier pas à pas la voix, la traduction, l’image et le son sur son téléphone | [Parcours de tests sur téléphone](TESTS_TELEPHONE.md) |
| Essayer une piste anglaise synthétique de cinq occurrences | [Fichier SRT de démonstration](../examples/essai-traduction-en.srt) |
| Choisir un moteur vocal Android et comprendre les modèles proposés | [Catalogue des voix et moteurs](VOIX_CATALOGUE_2026.md) |
| Lire la description exacte de cette livraison | [Notes de la version 0.4.0](releases/0.4.0.md) |

L’édition Traduction s’installe **à côté de la 0.3.2** sous l’identité `fr.soustitresvoix.player.translation`. Choisir de nouveau les fichiers, le moteur vocal et les réglages dans cette édition. Une voix française est nécessaire pour vocaliser la traduction. Les textes affichés dans la vidéo restent ceux de la piste originale.

## Comprendre les fonctions et leurs limites

| Sujet | Document de référence |
|---|---|
| Minutages et occurrences, validation complète, annulation, cache et clés API | [Traduction — étape 7](TRADUCTION_ETAPE_7.md) |
| Azure, DeepL et serveur LibreTranslate : accès, paramètres, confidentialité et limites | [Guide des moteurs de traduction](GUIDE_MOTEURS_TRADUCTION.md) |
| Hugging Face, formats de modèles, licences et runtimes possibles | [Étude de traduction locale](ETUDE_TRADUCTION_LOCALE.md) |
| CPU, GPU et NPU : possibilités réelles et contraintes | [Étude de l’accélération](ETUDE_ACCELERATION.md) |
| Cadrage, bandes, saisie précise et paramètres de l’image | [Interface d’image — étape 4](INTERFACE_IMAGE_ETAPE_4.md) |
| Volumes, atténuation du film et fondus | [Commandes audio — étape 5](COMMANDES_AUDIO_ETAPE_5.md) |
| Préampli, cinq bandes paramétriques, balance, mono et limiteur | [Audio avancé — étape 6](AUDIO_EXPERT_ETAPE_6.md) |

**Aucun moteur local de traduction, import de modèle Hugging Face ou OCR n’est intégré dans la 0.4.0.** Une traduction complète déjà en cache peut être réutilisée sans réseau ; cela ne constitue pas une nouvelle traduction locale. Les études présentent des candidats et les travaux nécessaires, pas des fonctions disponibles. Le moteur vocal Android est externe au lecteur ; son installation ne crée pas de moteur de traduction.

## Développer et vérifier

| Besoin | Document |
|---|---|
| Comprendre les composants et les invariants de lecture | [Architecture](ARCHITECTURE.md) |
| Retrouver les interfaces de composants | [Modularisation — étape 1](MODULARISATION_ETAPE_1.md) |
| Distinguer capacités déclarées, paramètres demandés et observations | [Capacités et observations — étape 2](CAPACITES_ETAPE_2.md) |
| Comprendre les commandes d’image et leur validation | [Commandes d’image — étape 3](COMMANDES_IMAGE_ETAPE_3.md) |
| Compiler, exécuter les tests et contrôler l’APK signé | [Procédure et résultats de validation](../TESTING.md) |
| Préparer une modification et sa fusion dans `main` | [Contribution](../CONTRIBUTING.md) |
| Lire les consignes de développement | [AGENTS.md](../AGENTS.md) |
| Retrouver les commits, fusions, archives et sources des APK | [Historique des branches et publications](HISTORIQUE_BRANCHES.md) |
| Parcourir les changements par version | [Journal des changements](../CHANGELOG.md) |

L’APK 0.4.0 correspond au commit [`32864ba`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/tree/32864ba732d834e71d87b887ae63f0d7bc09eb8c), validé par le [run Android 40053284969](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40053284969) : **612 exécutions réussies**, ainsi que **39 tests Python d’outillage** dans les workflows concernés. Lint relève zéro erreur et 51 avertissements dans chaque variante. Les nombres, environnements, erreurs corrigées et limites des preuves sont détaillés dans [TESTING.md](../TESTING.md).

Les tests sur émulateur ne déclarent pas réussis les essais manuels sur un téléphone réel, TalkBack, la qualité acoustique d’une voix ou la qualité linguistique des fournisseurs. Les guides des étapes 1 à 6 conservent leurs états intermédiaires avec un avertissement historique ; leurs fonctions sont regroupées dans la version publiée.

## Consulter les versions précédentes

| Version ou document | Objet conservé |
|---|---|
| [0.3.2 — ancienne stable](releases/0.3.2.md) | Édition antérieure, avec identité et signature distinctes |
| [0.3.1 — préversion Cinéma](releases/0.3.1-cinema-test.md) | Cadrage et correctifs de cette préversion |
| [0.2.0 — préversion Cinéma](releases/0.2.0-cinema-test.md) | Première série Cinéma |
| [0.1.0 — première préversion](releases/0.1.0-test.md) | Première livraison |
| [Révision 0.3](REVISION_03.md) | Adaptatif, préparation, cadrage, audio et limites de l’époque |
| [Révision 0.3.1](REVISION_031.md) | Corrections et constats des validations de cette série |
| [Couverture des demandes 0.3.1](COUVERTURE_DEMANDES_031.md) | Correspondance entre demandes, code, preuves et limites |
| [Régression Android initiale](REGRESSION_ANDROID.md) | Exception du moteur d’expressions régulières Android et contrôles ajoutés |

Pour installer la version actuelle, partir des liens de téléchargement en tête de page. Les anciens identifiants d’application, signatures, effectifs de tests et instructions de branche restent des informations historiques.
