# Documentation de Sous-titres en voix

[Accueil](../README.md) · [Changements](../CHANGELOG.md) · [Notes 1.1.1](releases/1.1.1.md) · [Validation](../TESTING.md) · [Signaler un problème](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/issues/new/choose)

La documentation courante décrit les **sources 1.1.1 / code 25**. Les [notes de version](releases/1.1.1.md#validation-et-publication) distinguent les critères de livraison et les essais sur appareil. Le corps de la release GitHub fournit les preuves propres au binaire.

## Utiliser l’application

| Besoin | Page courante |
| --- | --- |
| Installer et effectuer un premier essai synthétique | [Accueil et démarrage](../README.md#installer-et-commencer-sur-android) |
| Comprendre les quatre onglets, voix, modes, réglages, séances et hors-ligne | [Guide utilisateur](guide-utilisateur.md) |
| Ouvrir stockage partagé/SD, gérer les permissions ou configurer un serveur | [Sources locales et réseau](sources-medias.md) |
| Importer un GGUF, choisir un profil/contexte, interpréter cache et mesures | [Modèles de traduction](modeles-traduction.md) — également ouvert depuis l’application |
| Configurer Google, Azure, DeepL ou LibreTranslate | [Moteurs et comptes](GUIDE_MOTEURS_TRADUCTION.md) ; conditions/offres datées à revérifier auprès du fournisseur |
| Comprendre appels temporaires, gratuité et limites de service | [Traduction gratuite et limites](TRADUCTION_GRATUITE_ET_LIMITES.md) ; enquête datée, pas une garantie d’offre actuelle |
| Régler l’image et le son | [Image](INTERFACE_IMAGE_ETAPE_4.md), [audio](COMMANDES_AUDIO_ETAPE_5.md), [audio expert](AUDIO_EXPERT_ETAPE_6.md) — références détaillées de leur introduction |
| Choisir une voix Android externe | [Catalogue des voix](VOIX_CATALOGUE_2026.md), daté ; disponibilité et conditions à contrôler |

Les sources HLS/DASH/RTSP peuvent être lisibles sans permettre un index textuel complet. Les backends GGUF expérimentaux, modèles, codecs et serveurs demandent une qualification réelle. Le [guide utilisateur](guide-utilisateur.md#dépannage) commence le dépannage ; un rapport doit rester expurgé de secrets et de contenu personnel.

## Développer et vérifier

| Besoin | Référence |
| --- | --- |
| Comprendre pipeline, propriété des tâches, identités, index/narration et annulation | [Architecture et carte du code](ARCHITECTURE.md) |
| Préparer une PR et intégrer sans perdre les preuves | [Contribution](../CONTRIBUTING.md), [consignes](../AGENTS.md) |
| Compiler, inventorier, tester un APK et distinguer les niveaux de preuve | [Validation](../TESTING.md) |
| Signer avec le certificat durable et publier le même contenu applicatif | [Contribution : publication](../CONTRIBUTING.md#publier-un-apk-vérifié), [validation : signature](../TESTING.md#fusion-reconstruction-et-signature) |
| Identifier versions et licences tierces | [Attributions](../ATTRIBUTIONS.md), [chaîne native](../app/src/main/cpp/README.md) |
| Comprendre les contrats Media3/adaptateurs | [Modularisation](MODULARISATION_ETAPE_1.md), [capacités et observations](CAPACITES_ETAPE_2.md) |
| Retrouver les contrats détaillés d’image/audio | [Commandes d’image](COMMANDES_IMAGE_ETAPE_3.md), [audio](COMMANDES_AUDIO_ETAPE_5.md), [DSP](AUDIO_EXPERT_ETAPE_6.md) |
| Retrouver SDK/backends et l’audit antérieur | [Hexagon/HTP](NPU_HEXAGON_0514.md), [matrice des optimisations](OPTIMISATIONS_0514.md), [étude de l’accélération](ETUDE_ACCELERATION.md) |

## Corpus et preuves

- [Démonstration SRT](../examples/demonstration.srt), [essai anglais](../examples/essai-traduction-en.srt), [corpus inédit de fidélité et critères de relecture](CORPUS_FIDELITE_0510.md).
- [Notes 1.0.0 : validation et limites](releases/1.0.0.md#validation-et-publication), [preuve native hôte de préparation](verification/1.0.0-native-local.json) ; [workflow Android](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/workflows/android.yml).
- Preuves historiques : [0.5.15 natif local](verification/0.5.15-native-local.json), [0.5.1](verification/0.5.1.json), [0.5.0](verification/0.5.0.json), [charge du noyau 0.5.0](verification/0.5.0-additional-stress.json), [relevés Android 0.5.0](verification/0.5.0-device-metrics.json).

Une compilation, un test hôte, une instrumentation et un essai matériel sont des preuves différentes. Ni une ancienne réussite ni le numéro stable ne démontrent la fidélité d’un modèle ou les performances d’un téléphone.

## Versions et archives

Les notes suivantes conservent le périmètre et les constats de chaque version ; leurs formulations de candidate, recommandation ou prochaine étape décrivent leur époque. Pour le comportement courant, commencer par les pages ci-dessus.

| Version | Périmètre des notes |
| --- | --- |
| [1.1.1](releases/1.1.1.md) | Démarrage des fichiers réseau progressifs et conservation des occurrences reçues pendant la lecture |
| [1.1.0](releases/1.1.0.md) | Lecture intégrale vérifiée, bilan et rattrapage, explorateur et historique réseau ; preuves de la distribution conservées |
| [1.0.0](releases/1.0.0.md) | Version 1.0 ; navigation, Explorer/réseau, contexte Index, diagnostics et documentation ; preuves propres à la distribution dans le corps de la release GitHub |
| [0.5.15-loading](releases/0.5.15-loading.md) | Chargement sans seuil sur le poids du GGUF et diagnostics mémoire ; publication historique issue de la PR #31 |
| [0.5.14-hexagon](releases/0.5.14-hexagon.md) | NPU Hexagon/HTP expérimental, mémoire et causes d’interruption |
| [0.5.13-memory](releases/0.5.13-memory.md) | Buffers réutilisables, K/V optionnel et journal de tentative SRT |
| [0.5.12-runtime](releases/0.5.12-runtime.md) | Qwen3.5, Vulkan et diagnostic du chargement |
| [0.5.11-models](releases/0.5.11-models.md) | Index-Translate et gabarits intégrés reconnus |
| [0.5.10-quality](releases/0.5.10-quality.md) | Stratégies de contexte, contrôles et fidélité |
| [0.5.9-local](releases/0.5.9-local.md) | Format, contexte et cache exact |
| [0.5.8-benchmark](releases/0.5.8-benchmark.md) | Comparatif étendu et profilage du chargement |
| [0.5.7-directcl](releases/0.5.7-directcl.md) | Accès direct au pilote OpenCL facultatif |
| [0.5.6-opencl](releases/0.5.6-opencl.md) | Causes d’échec GPU |
| [0.5.5-perf](releases/0.5.5-perf.md) | Premières optimisations GGUF et GPU |
| [0.5.4-local](releases/0.5.4-local.md) | Traduction GGUF locale |
| [0.5.3-diagnostic](releases/0.5.3-diagnostic.md) | Diagnostic local, appareil/vidéo/modèle |
| [0.5.2](releases/0.5.2.md) | Google et lecture pendant la traduction |
| [0.5.1](releases/0.5.1.md), [0.5.0](releases/0.5.0.md) | Réglages, résilience, Confort intégral |
| [0.4.0](releases/0.4.0.md) | Série Traduction ; identité conservée depuis cette édition |
| [0.3.2](releases/0.3.2.md) | Ancienne application à identité distincte |
| [0.3.1 Cinéma](releases/0.3.1-cinema-test.md), [0.2.0 Cinéma](releases/0.2.0-cinema-test.md), [0.1.0](releases/0.1.0-test.md) | Premières séries |

Références historiques thématiques :

| Sujet | Documents |
| --- | --- |
| Vues précédentes | [README avant 1.0](archives/README-avant-1.0.0.md), [architecture détaillée](archives/architecture-avant-1.0.0.md), [validation détaillée](archives/verification-avant-1.0.0.md) |
| Traduction et parcours | [Étape 7](TRADUCTION_ETAPE_7.md), [Confort intégral](CONFORT_INTEGRAL_050.md), [Google 0.5.2](TRADUCTION_GOOGLE_052.md), [parcours téléphone 0.5.2](TESTS_TELEPHONE.md) |
| Évolution GGUF | [0.5.4](TRADUCTION_GGUF_054.md), [0.5.5](TRADUCTION_GGUF_055.md), [diagnostic 0.5.6](DIAGNOSTIC_OPENCL_056.md), [OpenCL direct](OPENCL_DIRECT_057.md), [comparatif](COMPARATIF_LOCAL_058.md), [contexte/cache](TRADUCTION_LOCALE_059.md), [qualité](TRADUCTION_QUALITE_0510.md), [profils](COMPATIBILITE_GGUF_0511.md), [runtime 0.5.12](TRADUCTION_RUNTIME_0512.md), [mémoire 0.5.13](TRADUCTION_RUNTIME_0513.md) |
| Études | [Traduction locale](ETUDE_TRADUCTION_LOCALE.md), [accélération](ETUDE_ACCELERATION.md), [diagnostic local](DIAGNOSTIC_LOCAL_053.md) |
| Demandes et révisions | [Couverture 0.5.0](COUVERTURE_DEMANDES_050.md), [couverture 0.3.1](COUVERTURE_DEMANDES_031.md), [révision 0.3](REVISION_03.md), [révision 0.3.1](REVISION_031.md), [régression Android initiale](REGRESSION_ANDROID.md) |
| Dépôt | [Historique des branches et versions](HISTORIQUE_BRANCHES.md) |
