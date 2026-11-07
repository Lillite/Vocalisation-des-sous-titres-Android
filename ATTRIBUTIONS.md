# Attributions du calcul GGUF natif

[Accueil](README.md) · [Documentation](docs/README.md) · [Sources de compilation épinglées](app/src/main/cpp/CMakeLists.txt)

Le travail propre à cette application est réalisé par IA à partir des demandes et retours de son utilisateur. Les composants ci-dessous sont les créations de leurs auteurs respectifs. Leurs textes de licence et mentions de copyright sont conservés dans les assets de l’APK ; cette page ne les remplace pas.

| Composant tiers | Source utilisée | Licence et mentions conservées |
| --- | --- | --- |
| llama.cpp / ggml | Révision `8ca4060b7e30e76baaa5670ee1ec1b760b7f8552`, [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp/tree/8ca4060b7e30e76baaa5670ee1ec1b760b7f8552) | [MIT, auteurs ggml](app/src/main/assets/licenses/llama.cpp.txt) |
| OpenCL-Headers | Version `v2025.07.22`, [KhronosGroup/OpenCL-Headers](https://github.com/KhronosGroup/OpenCL-Headers/tree/v2025.07.22) | [Apache 2.0](app/src/main/assets/licenses/OpenCL-Headers-2025.07.22.txt) ; [copyrights des sources](app/src/main/assets/licenses/OpenCL-Headers-2025.07.22-COPYRIGHT-NOTICES.txt) |
| Vulkan-Headers / Vulkan-Hpp | Version `v1.4.341`, [KhronosGroup/Vulkan-Headers](https://github.com/KhronosGroup/Vulkan-Headers/tree/v1.4.341) | [MIT](app/src/main/assets/licenses/Vulkan-Headers-1.4.341-MIT.txt), choix permis par les en-têtes sous double licence ; [copyrights des sources](app/src/main/assets/licenses/Vulkan-Headers-1.4.341-COPYRIGHT-NOTICES.txt) |
| SPIRV-Headers | Version `vulkan-sdk-1.4.341.0`, [KhronosGroup/SPIRV-Headers](https://github.com/KhronosGroup/SPIRV-Headers/tree/vulkan-sdk-1.4.341.0) | [Licence et mentions de l’archive](app/src/main/assets/licenses/SPIRV-Headers-1.4.341.0.txt) ; [copyrights des en-têtes](app/src/main/assets/licenses/SPIRV-Headers-1.4.341.0-COPYRIGHT-NOTICES.txt) |
| KleidiAI | Archive source `v1.32.0`, [ARM-software/kleidiai](https://github.com/ARM-software/kleidiai/tree/v1.32.0) | [Apache 2.0](app/src/main/assets/licenses/KleidiAI-1.32.0-Apache-2.0.txt) et, pour les parties concernées, [BSD 3-Clause](app/src/main/assets/licenses/KleidiAI-1.32.0-BSD-3-Clause.txt) ; [copyrights des sources](app/src/main/assets/licenses/KleidiAI-1.32.0-COPYRIGHT-NOTICES.txt) |

Les versions et SHA-256 des archives de compilation sont fixés dans CMake. Depuis la 0.5.7, une liaison directe facultative propre à l’application appelle le pilote OpenCL fourni par le téléphone ; ce pilote n’est pas copié dans l’APK. Le chargeur Khronos ICD des versions 0.5.5 et 0.5.6 n’est plus lié à cette compilation. Ses notices conservées dans les assets documentent ces versions historiques. OpenCL et KleidiAI sont activés pour la compilation Android ARM64 concernée ; l’émulateur x86_64 garde son chemin CPU.

Depuis la 0.5.12, le backend Vulkan facultatif ARM64 utilise des en-têtes épinglés : archive Vulkan-Headers SHA-256 `8876aad926b2e72bdefddc34885472d5332e2d20aa3ed57313466f0bc982cf3f` et archive SPIRV-Headers SHA-256 `cab0a654c4917e16367483296b44cdb1d614e3120c721beafcd37e3a8580486c`. Les shaders sont compilés lors de la construction avec le `glslc` hôte fourni par le NDK `27.2.12479018`. Le compilateur et les en-têtes ne sont pas téléchargés ni exécutés par l’application sur le téléphone. Le chargeur Vulkan système est chargé dynamiquement seulement après une demande explicite ; ni ce chargeur ni un pilote GPU ne sont distribués dans l’APK.

Cette liste concerne le calcul GGUF natif. Android, Kotlin, Media3, ML Kit et les autres dépendances gardent également leurs auteurs et conditions propres. Aucun modèle de production n’est distribué : le choix et l’import d’un GGUF ne remplacent pas ses conditions d’utilisation. Les licences tierces n’accordent pas de licence générale au code propre de ce dépôt.

## Hexagon/HTP dans la candidate 0.5.14

Les cinq programmes DSP v73/v75/v79/v81/v85 proviennent des sources MIT de llama.cpp/ggml épinglées et sont construits avant Gradle par [l’outil de préparation](tools/prepare-hexagon.py). Le SDK sert uniquement à la compilation : archive publique Snapdragon Toolchain **6.7.0.0**, SHA-256 **00725fc449916d7b9af2c2a3c6b54d61f3c872d5dbc1d3ca3fd2f839b1d5dc70**, outils Hexagon **21.0.04**. Les métadonnées internes du SDK indiquent 6.7.0.1 ; elles ne changent pas l’identité de l’archive contrôlée.

L’APK conserve les notices du compilateur/runtime `hexagon-tools-NOTICE.txt` et le texte MIT `llama-cpp-MIT.txt` dans ses assets de licences. Les notices spécifiques ne constituent pas une affirmation de licence libre de l’ensemble du SDK. Les programmes sont liés avec les composants runtime de cette chaîne de compilation ; le SDK complet, ses outils et le pilote constructeur FastRPC ne sont pas redistribués dans l’APK. Les bibliothèques `libcdsprpc.so` employées à l’exécution restent celles du téléphone.
