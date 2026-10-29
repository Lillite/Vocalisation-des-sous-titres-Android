# Documentation de Sous-titres en voix

[Accueil et téléchargement](../README.md) · [Changements](../CHANGELOG.md) · [Validation](../TESTING.md) · [Signaler un problème](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/issues/new/choose)

**Documentation 0.5.2 — Google et traduction pendant la lecture.** [Télécharger l’APK](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/download/v0.5.2/SousTitresVoix-0.5.2-google.apk) · [Notes 0.5.2](releases/0.5.2.md) · [Guide Google](TRADUCTION_GOOGLE_052.md) · [Validation GitHub Actions](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/workflows/android.yml). Les résultats antérieurs restent attachés à leurs versions.

**Candidate 0.5.11-models en préparation.** [Compatibilité des modèles GGUF et profils](COMPATIBILITE_GGUF_0511.md) · [Notes et validation](releases/0.5.11-models.md). La [0.5.10-quality publiée](releases/0.5.10-quality.md) a réussi 800 tests, dont 111 sous Android 17. Ces preuves historiques ne valident pas les nouvelles familles de modèles. La 0.5.2 reste le téléchargement stable recommandé.

## Installer et utiliser sur téléphone

| Besoin | Guide |
| --- | --- |
| Installer, choisir une voix et ouvrir une vidéo | [Démarrage rapide](../README.md#installer-et-commencer-sur-android) |
| Retrouver le diagnostic local publié en 0.5.3 | [Diagnostic local : appareil, vidéo et modèle](DIAGNOSTIC_LOCAL_053.md) |
| Comprendre Google, la lecture progressive et le correctif audio | [Nouveautés 0.5.2](releases/0.5.2.md) |
| Retrouver les corrections de défilement et de DeepL | [Correctifs 0.5.1](releases/0.5.1.md) |
| Réglages, lecture intégrale, séances, synchronisation et stockage hors ligne | [Guide Confort intégral](CONFORT_INTEGRAL_050.md) — fonctions introduites en 0.5.0 et conservées |
| Régler cadrage, zoom, bandes, contraste et luminosité | [Interface d’image](INTERFACE_IMAGE_ETAPE_4.md) |
| Régler les volumes, fondus et l’atténuation du film | [Commandes audio](COMMANDES_AUDIO_ETAPE_5.md) |
| Régler préampli, égaliseur, balance, mono et limiteur | [Audio expert](AUDIO_EXPERT_ETAPE_6.md) |
| Choisir un moteur vocal externe | [Catalogue des voix et moteurs](VOIX_CATALOGUE_2026.md) |
| Vérifier la mise à jour sur un téléphone réel | [Parcours manuel 0.5.2](TESTS_TELEPHONE.md) |

La série 0.5 conserve l’identité Android de l’édition Traduction 0.4.0 ; chaque publication doit vérifier son certificat durable avant de permettre la mise à jour. La 0.3.2 possède une autre identité. Les versions publiées et leurs consignes d’installation restent accessibles depuis l’accueil. Les originaux vidéo ne sont pas modifiés.

## Traduire les sous-titres

| Besoin | Guide |
| --- | --- |
| Traduire avec un GGUF choisi sur le téléphone | [Parcours publié 0.5.4](TRADUCTION_GGUF_054.md) ; [optimisations publiées 0.5.5](TRADUCTION_GGUF_055.md) ; [diagnostic GPU publié 0.5.6](DIAGNOSTIC_OPENCL_056.md) ; [accès direct publié 0.5.7](OPENCL_DIRECT_057.md) ; [comparatif étendu publié 0.5.8](COMPARATIF_LOCAL_058.md) ; [contexte et cache publiés 0.5.9](TRADUCTION_LOCALE_059.md) ; [qualité et fiabilité publiées 0.5.10](TRADUCTION_QUALITE_0510.md) ; [modèles et profils préparés 0.5.11](COMPATIBILITE_GGUF_0511.md) |
| Configurer Google en ligne/local et regarder pendant la traduction | [Guide Google 0.5.2](TRADUCTION_GOOGLE_052.md) |
| Choisir une offre gratuite, comprendre Google et les limites DeepL | [Traduction gratuite et limites](TRADUCTION_GRATUITE_ET_LIMITES.md) |
| Configurer les moteurs Google, Azure, DeepL ou LibreTranslate depuis Android | [Moteurs, comptes, options et dépannage](GUIDE_MOTEURS_TRADUCTION.md) |
| Reprise, affichage, correction, conservation et export | [Moteurs et parcours courants](GUIDE_MOTEURS_TRADUCTION.md) ; [référence historique 0.5.0](CONFORT_INTEGRAL_050.md#traduction-complète-reprise-et-mode-progressif) |
| Comprendre le contrat initial des occurrences et de traduction complète | [Référence historique 0.4.0](TRADUCTION_ETAPE_7.md) |
| Utiliser le moteur local intégré ou étudier d’autres modèles | [ML Kit intégré](TRADUCTION_GOOGLE_052.md#google-local--ml-kit) ; [étude antérieure des autres candidats](ETUDE_TRADUCTION_LOCALE.md) |
| Essayer une piste synthétique anglaise | [Exemple SRT](../examples/essai-traduction-en.srt) ; [corpus inédit de fidélité et critères de relecture](CORPUS_FIDELITE_0510.md) |

**ML Kit apporte une traduction locale dans les langues compatibles après téléchargement de ses modèles. La préversion publiée 0.5.4 ajoute l’exécution directe d’un GGUF local choisi explicitement ; la préversion publiée 0.5.5 apporte ses optimisations, la préversion publiée 0.5.6 précise les échecs OpenCL, la préversion publiée 0.5.7 apporte un accès direct au pilote, la préversion publiée 0.5.8 apporte un comparatif étendu, la préversion publiée 0.5.9 corrige le contexte et le cache, la préversion publiée 0.5.10 apporte des stratégies explicites de contexte et une reprise plus efficace, et la candidate 0.5.11 ajoute Index-Translate et le gabarit intégré de modèles instruct compatibles.** Elle ne parcourt pas Hugging Face et ne télécharge pas de poids automatiquement. Aucun OCR, accès Google Cloud/Apps Script ou service de lecture durable en arrière-plan n’est intégré. Réutiliser un texte traduit conservé reste une fonction distincte d’une nouvelle traduction locale. Les comptes et quotas des services en ligne demeurent à vérifier auprès du fournisseur.

## Développer, vérifier et contribuer

| Besoin | Document |
| --- | --- |
| Résultats réellement exécutés, provenance des APK et procédure de validation | [TESTING.md](../TESTING.md) |
| Référence historique : validation et publication 0.5.1 | [Preuve structurée 0.5.1](verification/0.5.1.json) |
| Fusion avec validation préalable, puis livraison sans répéter les suites | [Contribution](../CONTRIBUTING.md), [consignes de développement](../AGENTS.md) |
| Invariants et composition des composants | [Architecture actuelle](ARCHITECTURE.md) |
| Versions, licences et copyrights du calcul GGUF natif | [Attributions](../ATTRIBUTIONS.md) |
| Interfaces de session et responsabilités des composants | [Modularisation — étape 1](MODULARISATION_ETAPE_1.md) |
| Capacités déclarées et observations effectives | [Capacités — étape 2](CAPACITES_ETAPE_2.md) |
| Contrat de validation des commandes d’image | [Commandes d’image — étape 3](COMMANDES_IMAGE_ETAPE_3.md) |
| Distinguer CPU/GPU/NPU et mesures nécessaires | [Étude de l’accélération](ETUDE_ACCELERATION.md) |
| Demandes et régressions ajoutées en 0.5.0 | [Matrice de couverture 0.5.0](COUVERTURE_DEMANDES_050.md) |
| Preuves conservées de la 0.5.0 | [Inventaires et publication](verification/0.5.0.json), [charge du noyau](verification/0.5.0-additional-stress.json), [relevés Android](verification/0.5.0-device-metrics.json) |
| Commits exacts, branches et archives | [Historique du dépôt](HISTORIQUE_BRANCHES.md) |

Les **524 tests de la 0.5.1**, référence historique, se répartissent en 178 tests noyau, 254 JVM application et 92 tests instrumentés Android 36. Les preuves et effectifs anciens restent attachés à leurs propres sources. Les tests sur émulateur ne remplacent pas TalkBack réel, l’écoute d’une voix externe, l’évaluation d’une traduction, l’autonomie ou les performances du téléphone.

## Versions et archives

| Version ou document | Statut et périmètre |
| --- | --- |
| [0.5.11-models — modèles et profils GGUF](releases/0.5.11-models.md) | Candidate en préparation ; Index-Translate et gabarit intégré des modèles instruct nativement compatibles |
| [0.5.10-quality — qualité et fiabilité](releases/0.5.10-quality.md) | Préversion publiée ; contexte explicite, journal incrémental, contrôles numériques et suivi accessible ; 800 tests réussis dont 111 sous Android 17 |
| [0.5.9-local — traduction locale et cache](releases/0.5.9-local.md) | Préversion publiée ; format TranslateGemma, paires de contexte complètes, cache exact et fermeture coordonnée ; 741 tests réussis dont 110 sous Android 17 |
| [0.5.8-benchmark — comparatif GGUF étendu](releases/0.5.8-benchmark.md) | Préversion publiée ; séries variées, profilage du chargement et observations de lecture ; 724 tests réussis dont 110 sous Android 17 |
| [0.5.7-directcl — accès OpenCL direct](releases/0.5.7-directcl.md) | Préversion publiée ; 708 tests réussis, dont 110 sous Android 17 ; calcul Adreno observé sur le OnePlus |
| [0.5.6-opencl — diagnostic GPU](releases/0.5.6-opencl.md) | Préversion publiée ; causes bornées et durées explicites ; 706 tests réussis dont 110 sous Android 17 |
| [0.5.5-perf — performances GGUF](releases/0.5.5-perf.md) | Préversion publiée ; CPU, GPU facultatif, cache exact, correctif Hy-MT2 ; 698 tests réussis sur ses sources |
| [0.5.4-local — traduction GGUF locale](releases/0.5.4-local.md) | Préversion publiée le 28 octobre 2026 ; reprise et export ; 662 tests réussis sur ses sources |
| [0.5.3-diagnostic — diagnostic local](releases/0.5.3-diagnostic.md) | Préversion publiée le 28 octobre 2026 ; CPU GGUF synthétique et sondes locales ; 600 tests réussis sur ses sources |
| [0.5.2 — Google et lecture pendant la traduction](releases/0.5.2.md) | Moteurs Google, tampon progressif réglable, correction du panneau audio ; [preuves](../TESTING.md) |
| [0.5.1 — réglages stables et traduction résiliente](releases/0.5.1.md) | Publication historique du 27 octobre 2026 ; correctifs conservés |
| [0.5.0 — Confort intégral](releases/0.5.0.md) | Publication historique du 26 octobre 2026 ; fonctions conservées |
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
