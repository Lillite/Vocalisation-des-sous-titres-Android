# Modèles de traduction locale — 1.0.0

[Index](README.md) · [Guide utilisateur](guide-utilisateur.md) · [Moteurs et comptes](GUIDE_MOTEURS_TRADUCTION.md) · [Notes 1.0.0](releases/1.0.0.md)

Le bouton **Guide des modèles de traduction** de l’onglet Traduction ouvre cette page dans le navigateur, uniquement sur action explicite. Il ne télécharge pas de poids et n’importe aucun modèle.

## Ce qui est intégré

Deux parcours locaux sont distincts : **Google local — ML Kit**, avec modèles téléchargés sur demande dans les langues prises en charge par le SDK, et **GGUF local**, où l’utilisateur choisit un fichier de poids. llama.cpp assure directement le calcul GGUF dans un processus séparé du lecteur. Aucun serveur, PocketPal, compte Hugging Face ni clé d’API n’est requis pour ce calcul local.

Le modèle de production n’est pas inclus. L’import copie le fichier dans l’espace privé et calcule son empreinte ; conserver assez d’espace pour cette copie, les caches et les sorties. Le nom du fichier n’établit pas son contenu. Vérifier sa provenance, son SHA-256, sa licence et ses conditions d’usage auprès de son éditeur.

Une prise en charge doit être établie à plusieurs niveaux :

| Niveau | Vérification |
| --- | --- |
| Conteneur GGUF | Métadonnées et tenseurs lisibles, fichier intègre |
| Architecture native | Architecture et types de tenseurs pris en charge par la révision llama.cpp épinglée |
| Profil de traduction | Consigne et encadrement correspondant au modèle |
| Format de conversation | Profil spécialisé ou gabarit intégré reconnu par le runtime |
| Ressources | Mémoire disponible pendant chargement, préfill et génération ; place et état thermique |
| Exécution | Backend demandé, sélection réelle et opérations observées |
| Fidélité | Relecture bilingue des sorties, noms, nombres, négations, registre et contexte |

Passer un niveau ne valide pas les suivants. **Aucun nouveau modèle n’est recommandé comme meilleur modèle universel par cette version.** Hy-MT/HY-MT reste une compatibilité conservée dans le code et une piste étudiée historiquement ; il n’est pas le modèle retenu pour le parcours demandé.

## Importer et commencer sans vidéo

1. Ouvrir **Traduction → Traduire un fichier SRT hors ligne**.
2. Importer explicitement le GGUF souhaité et attendre la vérification de sa copie privée.
3. Sélectionner profil, mode de calcul, langues, threads et réglages de mémoire. Un changement d’option ne lance pas de traduction.
4. Charger l’**exemple SRT anglais intégré** : huit occurrences synthétiques. Le **SRT de contrôle de fidélité — phrases inédites** en contient dix-huit ; ses [critères de relecture](CORPUS_FIDELITE_0510.md) sont séparés du texte transmis au modèle.
5. Examiner l’estimation, confirmer le calcul, puis suivre les étapes et les occurrences validées. Annuler reste possible.
6. Relire les résultats puis exporter vers un nouveau fichier. Un résultat syntaxiquement valide n’est pas une certification linguistique.

Pour une vidéo, indexer la piste puis confirmer la traduction. Les identifiants, numéros et minutages sont possédés par le lecteur : le modèle ne reconstruit pas le SRT. Une erreur, une annulation ou une sortie à la limite de génération ne doit jamais être enregistrée comme traduction complète.

## Profils et gabarits

Le choix **Automatique** conserve la priorité des familles spécialisées reconnues, notamment TranslateGemma, Index-Translate et Qwen3.5. Le profil spécifique fixe les consignes adaptées ; l’architecture seule ne détermine pas le prompt de traduction.

Le profil **Gabarit intégré au GGUF — formats reconnus** utilise `llama_chat_apply_template` pour les modèles instruct nativement compatibles. Il reconnaît un format pris en charge par le runtime, rend un message utilisateur et amorce la réponse assistant. Il n’exécute pas une logique Jinja arbitraire. Un gabarit absent, inconnu ou en erreur provoque un refus perceptible, sans repli silencieux en texte brut. Les modèles Qwen3.5 utilisent leur profil sans raisonnement ; sa présence ne garantit pas la qualité du fichier importé.

La [référence historique des profils](COMPATIBILITE_GGUF_0511.md) décrit leur introduction ; les règles et limites courantes ci-dessous prévalent pour la 1.0.0.

## Choisir le contexte

| Politique | Entrée de contexte | Disponibilité |
| --- | --- | --- |
| `RECENT_TWO` | Jusqu’à deux paires complètes source/traduction précédentes | Comportement initial conservé |
| `ISOLATED` | Occurrence courante seule | Référence sans historique |
| `BLOCKS_SIX` | Historique des occurrences précédentes dans le bloc courant de six | Expérimental, conservé |
| `SOURCE_WINDOW_TWO` | Jusqu’à deux sources précédentes et deux suivantes ; aucune traduction voisine | **Index-Translate uniquement**, nouvelle politique explicite |

La fenêtre de sources ajoute des informations d’interprétation, mais le résultat couvre **uniquement l’occurrence courante**. Elle ne doit pas traduire les voisines ni changer l’ordre de lecture. Le contexte provient des textes sources d’origine, y compris après une reprise ; il ne propage pas les erreurs d’une traduction voisine.

Les voisins sont conservés **entiers**. Le plafond complémentaire de **2 000 caractères** n’est pas une estimation de tokens. Le tokenizer natif reste l’autorité pour le budget réel ; si l’entrée dépasse celui-ci, les voisins les plus lointains sont retirés entiers, en équilibrant les côtés. Le texte courant n’est pas rogné. Le parcours existant peut scinder une occurrence longue en fragments réassemblés, en conservant l’ensemble de son texte et une seule identité/minutage ; une limite impossible à résoudre reste une erreur.

La fenêtre n’augmente pas le contexte natif : **2 048 tokens**, avec une limite de **512 tokens par génération** et le même choix **greedy**. Les autres familles refusent la politique source-only plutôt que de l’interpréter comme une conversation équivalente. Aucun bénéfice de fidélité ou de vitesse n’est déduit du seul ajout de contexte.

## Régler mémoire et accélération

| Réglage | Bornes et sens |
| --- | --- |
| Threads | 1 à 8, défaut 4 ; nombre explicitement choisi pour le calcul CPU, sans prouver l’utilisation du NPU |
| Couches GPU | `−1` = toutes ; sinon 1 à 1 024 ; ignoré en mode CPU |
| Lot logique | 16 à 512 tokens ; défaut 512 |
| Micro-lot | 16 à 128 tokens, au plus le lot logique ; défaut 128 |
| Cache d’attention locale compact | Désactivé initialement ; expérimental, peut exiger des recalculs |
| Cache K/V | F16 initial ; Q8_0 expérimental explicite, précision d’états différente |
| Flash Attention | AUTO, activée ou désactivée ; V quantifié incompatible avec une désactivation explicite |
| Compatibilité Vulkan Qualcomm / poids CPU natifs | Choix explicites, identifiés dans la configuration ; qualification matérielle distincte |

Des lots plus petits peuvent diminuer certaines allocations et ralentir le traitement de l’entrée. Répartir des couches ne crée pas de RAM. Le choix GPU « toutes » n’est pas le bouton Maximum numérique ; il ne promet pas de déporter toutes les opérations. Un cache K/V Q8_0 conserve les poids GGUF mais change la précision des états d’attention : aucune équivalence bit à bit ou linguistique avec F16 n’est affirmée.

Le chargement ne doit pas être refusé au seul motif de la taille du GGUF. Les gardes de mémoire disponible, la réserve système, les erreurs d’allocation et le budget du pilote lorsqu’il est observable restent nécessaires. Une hausse de RSS pendant le préfill peut compter des pages MMAP des poids ; elle ne justifie pas de réserver une seconde fois tout le modèle. Aucun remplacement automatique par des poids moins précis n’est autorisé.

**CPU, OpenCL, Vulkan et Hexagon/HTP** sont des modes distincts. Le mode Hexagon strict exige une exécution correspondante et ne présente pas un repli CPU comme réussite NPU. Les pilotes sont ceux du téléphone. Le backend compilé, le nom du périphérique, les couches demandées et la politique Flash Attention ne sont pas des mesures d’exécution.

## Comprendre les deux caches

Le **cache exact de traductions** conserve une réponse validée pour une requête linguistique strictement identique. Sa clé inclut modèle/configuration, langues, texte courant et contexte exact. Avec la nouvelle politique, les sources **suivantes** entrent aussi dans l’identité ; changer une voisine future ne doit pas réutiliser une réponse d’un autre contexte. Les clés historiques sans contexte futur restent compatibles. Le cache est local, borné à **8 Mio par parcours**, avec empreinte et écritures atomiques. Il ne remplace pas le journal durable de progression et ne fusionne jamais les occurrences.

Le **cache natif de préfixe** réutilise, lorsque cela reste exact, des états du calcul d’entrée. Une ressemblance sémantique, un préfixe textuel commun ou une fenêtre qui glisse ne suffisent pas. Les diagnostics conservent le nombre de tokens candidats, le préfixe effectivement réutilisé et les bornes valides avant remise à zéro.

`WINDOW_EXPIRED` reste une **protection d’exactitude** : après expiration de la fenêtre, certains états nécessaires ne sont plus disponibles. Les architectures récurrentes Qwen3.5 et leur configuration `n_rs_seq = 0` ne permettent pas de prétendre restaurer arbitrairement l’état d’une requête antérieure. Le préfixe est alors recalculé. Aucun cache partagé forcé ni suppression de cette garde n’est annoncé comme optimisation. Une nouvelle politique de contexte peut donc accroître les recalculs ; cela doit être mesuré, pas caché.

## Essais matériels antérieurs rapportés

Ces observations de l’utilisateur concernent les fichiers et réglages indiqués, dans la série antérieure. **Elles ne valident pas le nouvel APK 1.0.0.** Elles ne constituent ni comparaison contrôlée de qualité, ni recommandation de précision moindre.

| Fichier identifié | Essai rapporté | Limite |
| --- | --- | --- |
| Index-Translate Q6_K, **7 558 901 184 octets** ; SHA-256 `5e47c636c75de6c5065f8550869a744e049451ac591a001968872d5c8b4b7898` | Mode NPU, 8 threads, lots 512/128, couches toutes, F16, compact désactivé, `BLOCKS_SIX` ; essai matériel utilisateur | Ne mesure pas la politique `SOURCE_WINDOW_TWO`, ni un gain NPU propre à la 1.0.0 |
| TranslateGemma 12B Q6_K, **9 660 827 392 octets** ; SHA-256 `c30995b3c145e6ef3b3a6fda63749186d83d9c8f16725ff1403c904b5e0ead8c` | OpenCL, 4 threads, lots 64/16, couches toutes, F16, compact activé, `RECENT_TWO` ; pas de crash rapporté sur 120 occurrences, lenteur signalée | 120 occurrences ne prouvent pas une stabilité prolongée ni un débit acceptable sur un film |
| Même TranslateGemma, essai Vulkan | Arrêt rapporté à 3/120, motif Android 6/ANR | Cause et correction matérielle non établies ; ce profil ne peut pas être conseillé pour un usage quotidien sur cette base |

Le motif Android rapporté ne permet pas à lui seul de déduire un OOM, une faute d’un pilote ou le lieu exact du calcul. Conserver le rapport des deux tentatives lorsque AUTO se replie sur CPU. Une tentative qui échoue avant réponse mesurée n’a pas de total de tokens inventé.

## Lire les mesures et les erreurs

Le cumul commence à la **première activité de la même séance du moteur** et utilise une horloge monotone. Une reprise contrôlée du worker SRT peut conserver cet accumulateur ; il ne mesure pas automatiquement toute la durée d’un fichier SRT ni le temps total d’ouverture de l’écran.

| Mesure | Interprétation |
| --- | --- |
| Chargements et millisecondes de chargement | Chargements effectivement mesurés, séparés du temps écoulé |
| Occurrences terminées / cache exact | Une occurrence complète ou une réponse réutilisée ; distinctes des appels natifs |
| Appels tentés / réponses mesurées | Un essai sans réponse mesurée peut figurer seulement parmi les tentatives ; les fragments sont des appels distincts |
| Tokens d’entrée | Total mesuré, part calculée et part réutilisée du préfixe |
| Tokens de sortie et tokens/s | Somme des sorties mesurées divisée par leur temps de génération ; pas le débit global d’un film |
| NPU/GPU observé, nœuds de graphes | Observations de réponses ; leur somme ne prouve pas le calcul intégral sur l’accélérateur |
| RSS relevée pendant la séance | Mémoire résidente du processus relevée ; distincte du pic de toute sa vie et de la mémoire totale des pilotes |
| Buffers texte/tokens réutilisables | Petits buffers du wrapper, comptés séparément ; pas les poids, le K/V ou toute la mémoire GPU/NPU |

Les étapes tokenisation, cache, préfill et génération décrivent l’avancement technique. La fin d’une phase n’est pas nécessairement la validation d’une occurrence. Une erreur de format, de gabarit, de mémoire, de processus ou de génération doit rester visible. Conserver les diagnostics sans y ajouter le texte d’un film personnel, ses chemins ou les secrets réseau.

Pour comparer des réglages, fixer même fichier/SHA, corpus, langues, politique, précision et température de départ ; séparer chargement, entrée, génération, cache exact et relecture linguistique. Un résultat synthétique CPU, même exact, ne valide ni le NPU physique ni la fidélité sur une œuvre complète.
