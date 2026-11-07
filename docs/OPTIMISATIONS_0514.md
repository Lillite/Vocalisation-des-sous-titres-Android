# Stabilité, mémoire et accélérateurs — suivi de l’audit 0.5.14

[Accueil](../README.md) · [Hexagon](NPU_HEXAGON_0514.md) · [Validation](../TESTING.md)

Cette page suit les **28 pistes** étudiées après l’interruption d’une préparation Vulkan à 17 occurrences sur 25. Les modifications de la candidate sont en cours de validation. Un changement de code, une estimation mémoire et un résultat sur fixture ne constituent pas une mesure de gain sur le OnePlus. La cause exacte de l’interruption initiale reste distincte des défauts observés dans le diagnostic.

## Contrats à préserver

Chaque occurrence, panneau, répétition légitime, identifiant et minutage reste conservé. Les poids choisis, le contexte de 2 048 tokens et le plafond de génération ne sont pas réduits silencieusement. Le cache Q8, les politiques Flash Attention, la compatibilité Qualcomm et les politiques de buffers CPU sont explicites, identifiés et rapportés. Une Pause ou une annulation utilisateur invalide toute reprise automatique autorisée par le calcul.

## Matrice des pistes

| N° | Sujet de l’audit | Mise en œuvre ou condition de qualification |
| --- | --- | --- |
| 1 | Cause d’interruption masquée | Journal distinguant cause initiale, annulation volontaire et fermeture secondaire ; une fermeture ne doit pas écraser la première erreur. |
| 2 | Admission mémoire des modes explicites | Garde commune CPU/OpenCL/Vulkan/Hexagon avec réserve indicative ; ce refus préventif n’est pas une preuve d’OOM Android. |
| 3 | Budget Vulkan | Interrogation et observation du budget lorsqu’il est disponible, sans addition naïve au RSS de la mémoire partagée. |
| 4 | Estimation poids/contexte/calcul | Plan `no_alloc` et ventilation encapsulant l’API interne de la révision épinglée ; allocations du plan libérées avant le modèle réel. |
| 5 | Mesures par phase | Série bornée et composantes natives, distinguant estimations, instantanés et pics observés. |
| 6 | Arrêt avant pression excessive | Contrôle aux frontières sûres, préfixe sauvegardé, libération acquittée et reprise bornée. |
| 7 | Limites Android 17 | Motif système corrélé, marqueur mémoire éventuel, aucune limite numérique universelle inventée. |
| 8 | Deux processus et résumé d’étape | Motifs moteur et application conservés séparément ; corrélation avec les résumés disponibles, sans texte privé. |
| 9 | Recréation d’écran | Propriétaire retenu du job et observateur UI détachable ; annulation explicite toujours prioritaire. |
| 10 | Récupération du moteur | Tentatives limitées, même configuration, attente de libération ; aucun recyclage aveugle toutes les N phrases. |
| 11 | Thermique | Sondes bornées et pauses du parcours SRT, sans modifier le texte ; aucune dépendance exclusive aux anciens callbacks mémoire. |
| 12 | Micro-lots | Préréglage mémoire plus prudent et comparaisons explicites des profils, sans diminution de contexte. |
| 13 | PR Vulkan 30821 | Contournements quantifiés Qualcomm dans un profil expérimental ; provenance et révision des adaptations conservées. |
| 14 | PR Vulkan 30824 | Adaptation RMS_NORM Qualcomm expérimentale, à comparer aux opérateurs de référence. |
| 15 | PR Vulkan 30826 | Politique conservatrice pour les réductions de sous-groupes du pilote Qualcomm dans le même profil. |
| 16 | PR Vulkan 30829 | Politique Flash Attention scalaire Qualcomm expérimentale ; aucun facteur d’accélération amont transposé au téléphone. |
| 17 | PR Vulkan 30487 | Contrôle des tailles cumulées du scratch K/V ; ce cas n’explique pas à lui seul l’ancien essai F16/2048. |
| 18 | FA, allocations et profils Vulkan | Politique FA explicite, suivi des allocations et refus d’un changement de profil global non appliqué avant redémarrage contrôlé du moteur. |
| 19 | Réserves OpenCL | Suppression des demandes initiales inutilisées et allocation à la demande de l’espace d’activation ; octets demandés distingués de RAM physique. |
| 20 | Transfert Q6_K | Voie de transfert bornée et exacte, sans conversion globale des poids en F16 ; maintien des cas nécessitant la disposition historique. |
| 21 | Pools et espaces temporaires OpenCL | Durées de vie liées au backend, libération synchronisée des scratchs et compteurs de réserves, transferts et replis. |
| 22 | Kernels et vues OpenCL | Contrats de vues et vérifications ciblées ; aucune fuite ni supériorité de tous les commutateurs présumée. |
| 23 | CPU natif et repacking | Option explicite désactivant les buffers de repacking supplémentaires, pour comparer le chemin natif au chemin accéléré sans modifier le fichier GGUF. |
| 24 | K/V Q8 | Option expérimentale existante conservée ; compatibilité FA vérifiée, cache distinct et fidélité à relire. |
| 25 | Hexagon/HTP | Backend intégré, cinq programmes DSP compilés et vérifiés, observations NPU séparées ; accès et modèle réel à qualifier sur matériel. |
| 26 | MLC/MNN/ExecuTorch/QNN | Comparaison de faisabilité ci-dessous. Leur chaîne d’export et la compatibilité du modèle exact doivent être établies avant ajout d’un moteur utilisable. Aucun bouton sans moteur opérationnel. |
| 27 | Coût des validations de poids | MMAP et empreinte privée déjà présents. La validation native des tenseurs reste conservée : ni le seul nom de fichier ni une date de modification ne justifient sa suppression. Une optimisation exige une preuve d’intégrité au moins équivalente et une mesure utile. |
| 28 | Comparaison bornée de profils | Essais synthétiques séquentiels, provenance de chaque configuration, mémoire et temps séparés ; aucune sélection silencieuse de précision ou déclaration de fidélité automatique. |

## Moteurs alternatifs : critères d’entrée

| Candidat | Travail nécessaire avant intégration d’un modèle utilisable |
| --- | --- |
| Hexagon dans llama.cpp | Conserve le parcours GGUF ; SDK de compilation et accès FastRPC qualifiés séparément. Candidat retenu pour cette intégration. |
| MLC | Préparer le runtime et une bibliothèque de modèle compilée, puis vérifier les opérations et le tokenizer du modèle exact. |
| MNN | Exporter le modèle dans son format et vérifier les restrictions de quantification du backend visé. Un export 4 bits n’est pas équivalent au GGUF Q6_K demandé. |
| ExecuTorch / ONNX Runtime QNN | Export, délégation des opérateurs, format des poids et chaînes Qualcomm à qualifier avant mesure de stabilité et de fidélité. |

Une architecture seulement prise en charge en théorie ne valide pas TranslateGemma 12B. Ajouter un sélecteur qui échouerait systématiquement pour ce fichier n’est pas une intégration du modèle. Aucune nouvelle dépendance distante, acquisition de poids ou conversion de précision n’est activée implicitement.

## Décision à partir des mesures

Les profils sont comparés sur les mêmes sources synthétiques, avec les mêmes poids, contexte et précision. Une passe échouée, un nettoyage non acquitté ou un backend demandé sans opération observée exclut une réussite. Le rapport conserve chargement, préfill, génération, temps total et mémoire séparément. Une réponse déjà mise en cache ne devient pas une mesure d’inférence fraîche.

Le profil le plus rapide sur huit phrases n’est pas automatiquement recommandé pour un film. La stabilité prolongée, les omissions, nombres, négations, références contextuelles et répétitions doivent être vérifiés sur un corpus inédit. Les changements de précision et les différences de calcul doivent être relus, même si les poids du fichier restent identiques.

Les sources amont, les tests exécutés et les limites matérielles sont référencés dans les [preuves de validation](../TESTING.md) et les [notes de version](releases/0.5.14-hexagon.md). Les gains et la disparition des interruptions ne sont annoncés qu’après mesure correspondante.
