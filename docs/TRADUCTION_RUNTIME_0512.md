# Calcul local, Qwen3.5 et chargement des grands modèles — 0.5.12

[Accueil](../README.md) · [Documentation](README.md) · [Notes de version](releases/0.5.12-runtime.md) · [Validation](../TESTING.md)

La candidate **0.5.12-runtime / code 19** prépare un profil Qwen3.5 avec réponse directe, un moteur Vulkan facultatif, des réglages d’allocation mémoire et un diagnostic conservé même lorsqu’un modèle ne termine pas son chargement. Les sources et les essais doivent être vérifiés avant distribution. La **0.5.2 reste la dernière stable recommandée**.

Le fichier GGUF, son architecture, le profil de conversation, les ressources disponibles, le moteur réellement utilisé et la fidélité de la traduction sont des éléments distincts. Un profil reconnu ou une réponse terminée ne démontre pas une traduction exacte.

## Choisir le profil du modèle

Les profils TranslateGemma, Hy-MT / Hy-MT2 et Index-Translate restent disponibles. Pour les Qwen3.5 instruct reconnus, le nouveau profil fournit explicitement le début d’une réponse sans raisonnement. Le mode automatique conserve la priorité des familles spécialisées, notamment Index-Translate ; une architecture `qwen35` seule ne transforme pas un modèle Base ou personnalisé en modèle instruct reconnu.

Le profil générique **Gabarit intégré au GGUF — formats reconnus** conserve son rôle : il utilise les formats de conversation que le moteur natif sait reconnaître. Il n’exécute pas une logique Jinja arbitraire et ne sait pas automatiquement appliquer toutes les options particulières de tous les modèles.

Les nouvelles requêtes Qwen3.5 utilisent une identité de configuration **v5**. La même séparation s’applique lorsque les métadonnées du mode automatique sont absentes ou ambiguës et que Qwen3.5 ne peut être reconnu qu’après chargement : une ancienne réponse générique ne doit pas être réutilisée à la place du nouveau profil. Les familles historiques clairement identifiées conservent leur identité **v4** lorsque leurs réglages restent inchangés ; les réglages de chargement non standards font également partie de l’identité de calcul.

La fiche officielle [Qwen3.5-9B](https://huggingface.co/Qwen/Qwen3.5-9B) indique que le modèle raisonne par défaut et que le simple texte `/nothink` n’est pas une option officiellement prise en charge. La désactivation doit être exprimée par le gabarit adapté. Le nouveau profil suit le format produit avec `enable_thinking=false` ; il ne supprime pas après coup un raisonnement qui ferait partie de la réponse reçue.

Le candidat retenu pour un futur comparatif est le [GGUF Q6_K publié par Unsloth](https://huggingface.co/unsloth/Qwen3.5-9B-GGUF/blob/main/Qwen3.5-9B-Q6_K.gguf) :

| Élément | Valeur |
| --- | --- |
| Fichier | `Qwen3.5-9B-Q6_K.gguf` |
| Taille annoncée par le dépôt | Environ 7,46 Go |
| SHA-256 publié | `91898433cf5ce0a8f45516a4cc3e9343b6e01d052d01f684309098c66a326c59` |
| Usage dans ce parcours | Texte uniquement ; aucun projecteur `mmproj` nécessaire |

Ce choix est un candidat à mesurer, pas un classement universel ni une preuve de supériorité sur Hy-MT2. La taille du fichier ne représente pas toute sa mémoire d’exécution. Aucun poids de production n’est inclus ni téléchargé automatiquement par l’application.

## Choisir CPU, OpenCL ou Vulkan

Le calcul CPU reste indépendant de l’initialisation des pilotes GPU. OpenCL conserve son accès direct au pilote et les optimisations Adreno existantes. Vulkan est une option supplémentaire sur l’édition ARM64 ; sa présence dans l’APK ou sa détection par Android ne prouvent pas qu’un modèle donné y calcule.

Les modes GPU explicites sont stricts : un échec doit rester visible, et une exécution CPU ne doit pas être présentée comme un résultat Vulkan ou OpenCL. Automatique conserve son comportement OpenCL avec éventuel repli CPU signalé ; l’ajout de Vulkan ne remplace pas silencieusement ce choix.

Le comparatif permet de choisir le moteur GPU à confronter au CPU. Lire le moteur et l’appareil observés, les nœuds de calcul GPU, les temps et les sorties. Les mêmes poids, le même contexte et les mêmes limites doivent être utilisés pour comparer les parcours. Les modèles sont chargés successivement avec une libération confirmée entre les modes.

Vulkan et OpenCL peuvent avoir des allocations temporaires, des kernels et des contraintes de pilote différents. Aucun des deux ne réduit à lui seul la taille des poids ou n’ajoute de RAM physique. Le changement de moteur peut modifier les arrondis et donc une génération ; un débit supérieur ne certifie pas la fidélité du texte.

La compilation utilise **glslc 2022.3 fourni par le NDK 27.2**. Ce compilateur ne reconnaît pas sept extensions optionnelles récentes ; les variantes correspondantes restent désactivées. Les kernels standards ont passé une compilation ARM64 isolée, ce qui ne démontre ni leur exécution sur ce téléphone ni un gain par rapport à OpenCL. Le comparatif doit mesurer ce résultat sur le pilote réellement installé.

## Réduire la pression mémoire sans remplacer les poids

Les réglages de chargement portent sur les allocations du calcul, pas sur une suppression du texte source.

| Réglage | Effet recherché et limite |
| --- | --- |
| Lot logique et micro-lot | Réduire les blocs de calcul temporaires, au prix possible d’un traitement initial plus lent. Les paramètres historiques sont 512 et 128 tokens. Chaque entrée reste traitée intégralement. |
| Nombre de couches GPU | Répartir les couches entre CPU et GPU et réduire certaines contraintes d’allocation du pilote. CPU et GPU partagent la mémoire physique du téléphone ; cette répartition ne garantit pas que le modèle tienne en RAM. |
| Cache compact d’attention locale | Pour les modèles compatibles, réserver le cache selon leur fenêtre d’attention locale plutôt que partout selon le contexte complet. Ce choix est explicite et expérimental ; le cache global et la précision F16 sont conservés. |

La capacité native de contexte reste **2 048 tokens**, la borne d’une génération reste **512 tokens**, et les poids et leur quantification restent ceux du fichier choisi. Ces réglages ne remplacent pas la [stratégie de contexte des sous-titres](TRADUCTION_QUALITE_0510.md) : deux échanges précédents, occurrence seule et blocs de six restent des choix séparés.

La réutilisation du préfixe exige que ses positions soient réellement encore disponibles. Si le cache d’attention ne peut plus fournir l’état requis, le moteur doit recalculer l’entrée complète plutôt que réutiliser un état absent ou approximatif. Les essais doivent notamment couvrir un dépassement de la fenêtre locale suivi d’un changement de préfixe.

Les six requêtes de la fixture miniature d’attention locale ont produit les mêmes tokens avec l’allocation compacte et complète. Cette vérification ne prouve pas l’équivalence bit à bit : un petit écart entre logits a été mesuré. Les mesures détaillées figurent dans [TESTING.md](../TESTING.md). Le réglage reste désactivé par défaut et doit être évalué séparément avec chaque modèle de production.

## Lire un diagnostic d’échec

Un rapport doit être disponible dès le début de l’essai, même si aucune occurrence n’est validée. Il distingue le mode demandé, les dernières étapes reçues, le temps écoulé et les échecs natifs ou de communication. Une étape reçue est une observation ; l’absence de l’étape suivante ne prouve pas à elle seule la cause de l’arrêt.

Un dépassement du délai et la disparition du processus sont des résultats distincts. Android peut fournir un motif d’arrêt de son processus de calcul : manque de mémoire, crash natif, signal ou autre cause. L’application doit rapprocher ce relevé de son propre processus et de la bonne tentative. Un signal `SIGKILL` seul ne permet pas d’affirmer un manque de mémoire ; un relevé absent ou ambigu reste indéterminé.

Les valeurs RSS/PSS du système sont ponctuelles et peuvent manquer ; elles ne représentent pas nécessairement toutes les allocations GPU ni le pic exact précédant l’arrêt. Les étapes et rapports ne doivent contenir ni texte de sous-titres, ni chemin personnel, ni secret.

## Parcours conseillé sur téléphone

1. Installer la nouvelle APK seulement après sa publication vérifiée, par-dessus l’édition Traduction actuelle, sans désinstaller.
2. Conserver Hy-MT2 comme référence déjà essayée et garder le SRT original ainsi que sa traduction.
3. Pour le nouveau modèle, sélectionner le profil approprié et commencer par un texte synthétique court. Le chargement réussi, le calcul GPU et la fidélité sont vérifiés séparément.
4. Comparer CPU et le moteur GPU choisi avec les mêmes réglages et des modèles jamais chargés simultanément. Séparer le premier chargement des appels suivants.
5. Essayer ensuite les réglages mémoire individuellement pour pouvoir attribuer leur effet. Conserver le rapport, y compris lorsqu’il contient zéro occurrence validée.
6. Comparer les SRT sur plusieurs scènes inédites : omissions, ajouts, négations, nombres, pronoms, objets, cohérence et naturel du français. La conservation des 24 blocs d’un petit exemple ne suffit pas à certifier un film entier.

L’application préserve chaque occurrence, son identifiant et son minutage. Les traductions validées restent conservées pour une reprise explicite. Le lecteur ne doit pas contourner une traduction indisponible ou reprendre après une Pause volontaire. **L’OCR de film et le NPU restent hors de cette étape.**
