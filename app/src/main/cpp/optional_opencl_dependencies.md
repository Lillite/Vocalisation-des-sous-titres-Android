# Dépendances de l'accélération optionnelle

Le moteur llama.cpp reste à la révision `8ca4060b7e30e76baaa5670ee1ec1b760b7f8552`.
CMake active OpenCL et KleidiAI uniquement pour Android `arm64-v8a`. Les autres
ABIs conservent le moteur CPU et les fonctions `prepare_opencl()` / raison
indiquent que ce backend n'est pas compilé.

Les archives suivantes sont vérifiées par SHA-256 **pendant la compilation** :

| Projet | Version | SHA-256 |
|---|---|---|
| OpenCL-Headers | v2025.07.22 | `98f0a3ea26b4aec051e533cb1750db2998ab8e82eda97269ed6efe66ec94a240` |
| OpenCL-ICD-Loader | v2025.07.22 | `dff7a0b11ad5b63a669358e3476e3dc889a4a361674e5b69b267b944d0794142` |
| KleidiAI | v1.32.0 | `a48d373e7c5d721a0aecbf5ddaf0bdf245bc8064e6b84086f13db0e102b553a6` |

La déclaration anticipée de KleidiAI remplace le contrôle MD5 fourni par cette
révision de llama.cpp, tout en conservant sa version et ses sources. Les
licences et les notices de copyright sont dans `app/src/main/assets/licenses`.
Aucune dépendance ni aucun pilote ne sont téléchargés par l'application.

## Chargement optionnel

Le chargeur ICD Khronos est lié **statiquement**. L'APK ne distribue aucun
`libOpenCL.so`, aucun pilote Qualcomm et aucun noyau binaire propriétaire. Le
chargeur de l'application ouvre le `libOpenCL.so` fourni et exposé par Android,
uniquement lors d'une demande AUTO/OPENCL ; la déclaration Android de cette
bibliothèque est optionnelle. Un pilote compatible ICD est nécessaire.

`GGML_USE_OPENCL` est retiré des définitions de l'enregistrement automatique
`ggml`, tandis que le backend OpenCL reste compilé et lié. La fonction
`prepare_opencl()` enregistre ce backend explicitement après vérification du
pilote et de son inventaire. Cela permet une exécution CPU avant un essai GPU,
et une exécution CPU dans un nouveau processus après une défaillance du pilote,
sans que l'initialisation CPU réinterroge ce pilote.

La liaison `--exclude-libs,ALL` conserve les symboles OpenCL statiques à
l'intérieur de notre bibliothèque JNI pour éviter leur interposition dans les
appels internes du pilote. La validation de l'APK doit vérifier que sa
bibliothèque JNI ne possède aucune entrée `DT_NEEDED` vers `libOpenCL.so`.

## Compteur du backend

`optional_opencl_patch.py` produit une copie instrumentée du fichier OpenCL
pinné dans le répertoire de compilation ; il ne modifie pas l'archive amont.
Ses ancres vérifiées refusent une structure source inattendue. La copie appelle
`subtitle_opencl_graph_executed(uint64_t nodes)` après la soumission réussie des
opérations d'un graphe. Le compteur exclut les vues et les opérations non
calculées ; les fusions peuvent regrouper plusieurs opérations dans un noyau.

Le propriétaire doit remettre le compteur à zéro pour chaque requête et
synchroniser le contexte avec succès avant de présenter une exécution GPU comme
observée. Un inventaire de GPU ou une allocation GPU ne suffit pas. Aucun nom de
nœud, contenu de sous-titre, tenseur ou chemin de fichier n'entre dans ce
compteur.
