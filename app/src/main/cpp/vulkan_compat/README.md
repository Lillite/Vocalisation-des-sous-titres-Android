# Qualification Vulkan Qualcomm

Les cinq propositions amont sont conservées avec leurs SHA, état observé au
7 novembre 2026 et empreinte du patch dans `provenance.json`. La source llama.cpp
reste épinglée à `8ca4060b7e30e76baaa5670ee1ec1b760b7f8552`. Les transformations
s'appliquent uniquement à sa copie extraite de compilation, avec refus de tout
contenu source, patch ou résultat préexistant différent.

- **30821, 30824 et 30826** sont des propositions ouvertes en brouillon.
- **30829** est ouverte, sans statut de brouillon au contrôle.
- **30487** a été fusionnée le 5 novembre 2026 : sa vérification de la taille
  combinée K+V est un correctif de bornes appliqué indépendamment du profil.

Le choix expérimental `SUBTITLE_VULKAN_QUALCOMM_COMPAT=1` est établi avant le
premier accès au registre Vulkan du processus moteur. Les adaptations de RMS,
arithmétique de sous-groupes et taille des groupes de Flash Attention sont
conditionnées à ce choix et au fournisseur/pilote Qualcomm concerné. Un changement
de profil exige de recréer le processus moteur : il est refusé si les anciens
objets et pipelines pourraient être réétiquetés comme une autre configuration.

Le portage de **30821** utilise les helpers GLSL et la constante de spécialisation
63. Profil désactivé, ces helpers sélectionnent les instructions `pack32` et
`unpack8` historiques ; profil actif sur Qualcomm, ils utilisent les extractions
et décalages entiers explicites. Les petites tables partagées IQ/MXFP passent de
valeurs `int8` à des `int32` représentant exactement les mêmes constantes dans
[-128,127]. Le fichier GLSL et le SPIR-V sont donc modifiés même profil désactivé :
aucune identité binaire avec l'ancien shader n'est revendiquée. Les contrôles des
conversions entières ne prouvent pas la correction du compilateur du téléphone.

Les poids, leur quantification et la précision des états K/V ne sont pas abaissés
par ce profil. Les changements d'ordre de calcul possibles, les propositions
encore ouvertes et les particularités du pilote exigent néanmoins des tests
d'opérateurs et une relecture de fidélité sur le matériel.

La télémétrie observe les allocations des objets de buffer Vulkan complets et
leur vraie destruction. Elle comprend l'alignement demandé au pilote, distingue
les imports et les buffers visibles par l'hôte et conserve un maximum dans le
processus. Les imports et la visibilité hôte peuvent se chevaucher et ne doivent
pas être additionnés au total. Ces compteurs ne mesurent ni la résidence physique,
ni l'intégralité de la mémoire interne du pilote, ni le pic mémoire du téléphone.

Une observation distincte capture `VK_EXT_memory_budget` lors de la requête au
périphérique sélectionné. Elle conserve au plus seize heaps retenus par le
backend, leurs budgets plafonnés à leur capacité, usages estimés et marges. Sans
l'extension, le rapport indique une requête observée mais un budget indisponible :
la capacité d'un heap n'est pas présentée comme de la mémoire libre. Une requête
échouée invalide l'échantillon précédent. La marge Vulkan et `MemAvailable` sont
des contraintes séparées et ne constituent pas deux réserves à additionner.

La création des buffers est transactionnelle avant le transfert à leur propriétaire
partagé. Une erreur d'allocation, de mapping, de binding ou d'adresse libère ses
ressources partielles, sans décrémenter un compteur d'objet complet inexistant et
sans laisser le destructeur effectuer une seconde libération.

`tests/test_vulkan_compat.py` vérifie les refus de dérive, l'idempotence, les
conversions entières, le verrouillage du profil et les compteurs de propriété en
code C++ réel, l'absence de budget sans extension et les échecs simulés de mapping
ou binding. Avec `--glslc`, il compile six shaders représentatifs et vérifie la
présence de leurs constantes de spécialisation dans le SPIR-V. Aucune exécution
du GPU physique n'est déduite de ces contrôles.

Pour mettre à jour une copie de dépendance déjà qualifiée après une modification
de la recette, arrêter sa compilation puis utiliser `prepare_vulkan_compat.py
<copie> --restore-from <source-amont-vierge>`. Les anciennes sorties et les sources
de référence sont d'abord vérifiées ; les mtimes des shaders octet-identiques sont
conservés pour ne pas les recompiler inutilement. L'archive source reste inchangée.
