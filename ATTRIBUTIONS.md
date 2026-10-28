# Attributions du calcul GGUF natif

[Accueil](README.md) · [Documentation](docs/README.md) · [Sources de compilation épinglées](app/src/main/cpp/CMakeLists.txt)

Le travail propre à cette application est réalisé par IA à partir des demandes et retours de son utilisateur. Les composants ci-dessous sont les créations de leurs auteurs respectifs. Leurs textes de licence et mentions de copyright sont conservés dans les assets de l’APK ; cette page ne les remplace pas.

| Composant tiers | Source utilisée | Licence et mentions conservées |
| --- | --- | --- |
| llama.cpp / ggml | Révision `8ca4060b7e30e76baaa5670ee1ec1b760b7f8552`, [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp/tree/8ca4060b7e30e76baaa5670ee1ec1b760b7f8552) | [MIT, auteurs ggml](app/src/main/assets/licenses/llama.cpp.txt) |
| OpenCL-Headers | Version `v2025.07.22`, [KhronosGroup/OpenCL-Headers](https://github.com/KhronosGroup/OpenCL-Headers/tree/v2025.07.22) | [Apache 2.0](app/src/main/assets/licenses/OpenCL-Headers-2025.07.22.txt) ; [copyrights des sources](app/src/main/assets/licenses/OpenCL-Headers-2025.07.22-COPYRIGHT-NOTICES.txt) |
| OpenCL-ICD-Loader | Version `v2025.07.22`, [KhronosGroup/OpenCL-ICD-Loader](https://github.com/KhronosGroup/OpenCL-ICD-Loader/tree/v2025.07.22) | [Apache 2.0](app/src/main/assets/licenses/OpenCL-ICD-Loader-2025.07.22.txt) ; [copyrights des sources](app/src/main/assets/licenses/OpenCL-ICD-Loader-2025.07.22-COPYRIGHT-NOTICES.txt) |
| KleidiAI | Archive source `v1.32.0`, [ARM-software/kleidiai](https://github.com/ARM-software/kleidiai/tree/v1.32.0) | [Apache 2.0](app/src/main/assets/licenses/KleidiAI-1.32.0-Apache-2.0.txt) et, pour les parties concernées, [BSD 3-Clause](app/src/main/assets/licenses/KleidiAI-1.32.0-BSD-3-Clause.txt) ; [copyrights des sources](app/src/main/assets/licenses/KleidiAI-1.32.0-COPYRIGHT-NOTICES.txt) |

Les versions et SHA-256 des archives de compilation sont fixés dans CMake. Le chargeur ICD est intégré statiquement ; le pilote OpenCL reste fourni par le téléphone et n’est pas copié dans l’APK. OpenCL et KleidiAI sont activés pour la compilation Android ARM64 concernée ; l’émulateur x86_64 garde son chemin CPU.

Cette liste concerne le calcul GGUF natif. Android, Kotlin, Media3, ML Kit et les autres dépendances gardent également leurs auteurs et conditions propres. Aucun modèle de production n’est distribué : le choix et l’import d’un GGUF ne remplacent pas ses conditions d’utilisation. Les licences tierces n’accordent pas de licence générale au code propre de ce dépôt.
