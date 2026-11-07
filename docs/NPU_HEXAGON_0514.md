# NPU Hexagon expérimental — 0.5.14

[Accueil](../README.md) · [Documentation](README.md) · [Notes](releases/0.5.14-hexagon.md) · [Validation](../TESTING.md)

La candidate **0.5.14-hexagon / code 21** ajoute un choix explicite du backend Qualcomm **Hexagon/HTP** de la révision llama.cpp déjà épinglée. Cette intégration doit être compilée et validée sur ses propres sources avant distribution. La **0.5.2 reste la stable recommandée**.

## Choix et preuve de calcul

Dans le parcours GGUF, choisir **NPU Hexagon — expérimental ; sans repli CPU** demande ce backend. Le CPU, OpenCL et Vulkan restent des choix distincts. `AUTO` conserve sa politique existante et n'active pas Hexagon implicitement.

Le nom Qualcomm HTP désigne le chemin de calcul Hexagon, utilisant les opérations HVX/HMX prises en charge par le backend. Sa présence dans un menu, la compilation de la bibliothèque ou le chargement d'une session ne prouvent pas que le modèle y a calculé. Le rapport sépare les observations **NPU** des observations **GPU** : un succès Hexagon exige des opérations HTP observées et une synchronisation réussie. Certaines opérations peuvent rester sur CPU ; ce mode n'est pas une garantie d'exécution de chaque opération sur l'accélérateur.

Si le backend, les bibliothèques du téléphone ou le modèle sont incompatibles, l'erreur reste perceptible. Une exécution CPU seule n'est jamais présentée comme une réussite NPU. Le mode demandé reste enregistré et possède sa propre identité de configuration et de cache.

## Bibliothèques et confidentialité

Les programmes HTP sont construits à partir des sources épinglées pour les variantes Hexagon prévues et intégrés à l'APK. Leurs fichiers sont préparés dans un répertoire privé de l'application après vérification des noms, tailles et SHA-256 du manifeste embarqué, seulement pour une demande Hexagon. Le chemin ne provient ni d'un fichier de sous-titres ni d'un paramètre utilisateur. Aucun SDK, poids de production ou pilote constructeur n'est téléchargé par l'application.

Le SDK Hexagon sert uniquement à la compilation. Les accès FastRPC utilisent les bibliothèques fournies par le téléphone. L'absence de cet accès sur un autre constructeur, un appareil non compatible ou un émulateur doit rester un échec explicite du mode Hexagon ; elle n'empêche pas le démarrage du CPU. Le modèle choisi et ses conditions d'utilisation restent indépendants de cette intégration.

## Modèles et mémoire

La dépendance épinglée comprend des chemins Q4_K et Q6_K pour Hexagon. Cela ne valide pas automatiquement TranslateGemma 12B Q6_K dans son intégralité, ses formes d'opérateurs, son cache F16 ou Q8, ni le pilote de chaque téléphone. Le fichier GGUF et la précision du cache ne sont pas changés automatiquement.

Le backend dispose d'un mécanisme de remappage des buffers de poids pour les sessions à espace d'adressage limité. Cette fonctionnalité ne crée pas de RAM supplémentaire et ne garantit pas le chargement d'un fichier de 9,66 Go. La mémoire du modèle, les allocations temporaires, la mémoire Android et les limites du pilote doivent être mesurées séparément. Un nombre de couches déportées n'est pas une mesure du nombre d'opérations exécutées.

L'intégration Hexagon ne constitue pas une correction démontrée de l'interruption observée après 17 occurrences en Vulkan. Aucun classement de vitesse, consommation, qualité ou stabilité des backends n'est établi par la seule compilation.

## Premier essai sur téléphone

1. Installer l'APK de cette version par-dessus l'édition Traduction actuelle, sans désinstaller, après publication vérifiée.
2. Ouvrir **Traduire un fichier SRT hors ligne** et utiliser d'abord l'exemple synthétique intégré ou le fichier de contrôle déjà disponible.
3. Sélectionner explicitement Hexagon, conserver le modèle choisi et F16 pour une première comparaison si ce modèle est compatible, puis confirmer le calcul local.
4. Consulter les dernières mesures : moteur demandé et rapporté, opérations NPU observées, progression et erreurs. Un refus d'ouverture est aussi un résultat utile ; il ne doit pas être masqué en CPU.
5. Exporter seulement le résultat complet. Une préparation partielle reste reprise explicitement avec les mêmes modèle, source, langue et configuration ; la vidéo et la vocalisation ne démarrent pas seules.

Les tests hôte et l'émulateur vérifient les contrats et les refus, sans exécuter le NPU physique du OnePlus. Le premier résultat matériel doit être distingué de ces contrôles. La fidélité linguistique doit être relue sur des phrases inédites.

## Références amont

- [Backend Snapdragon et modes HTP](https://github.com/ggml-org/llama.cpp/blob/8ca4060b7e30e76baaa5670ee1ec1b760b7f8552/docs/backend/snapdragon/README.md).
- [Q4_K et Q6_K Hexagon](https://github.com/ggml-org/llama.cpp/pull/28994).
- [Sources de l'intégration native](../app/src/main/cpp/CMakeLists.txt) et [attributions](../ATTRIBUTIONS.md).
