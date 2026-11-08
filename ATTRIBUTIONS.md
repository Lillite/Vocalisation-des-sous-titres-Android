# Attributions des composants tiers

[Accueil](README.md) · [Documentation](docs/README.md) · [Sources de compilation épinglées](app/src/main/cpp/CMakeLists.txt)

Le travail propre à cette application est réalisé par IA à partir des demandes et retours de son utilisateur. Les composants ci-dessous sont les créations de leurs auteurs respectifs. Leurs textes de licence et mentions de copyright sont conservés dans les assets de l’APK ; cette page ne les remplace pas.

## Interface et sources réseau — 1.0.0

Les versions ci-dessous sont celles déclarées dans [Gradle](app/build.gradle.kts). Les licences ont été rapprochées des POM publiés par les éditeurs et des textes aux tags indiqués ; les notices Commons Net proviennent du JAR exact 3.13.0. Le [manifeste des notices](app/src/main/assets/licenses/ui-network-notices-SOURCES.json) conserve les URL primaires et SHA-256 des POM et fichiers embarqués. Ce périmètre des nouvelles dépendances directes n’est pas un inventaire exhaustif de toutes les dépendances transitives.

| Composant et auteurs | Version / rôle | Licence et notices embarquées |
| --- | --- | --- |
| [Material Components for Android](https://github.com/material-components/material-components-android), auteurs du projet Android/Google | `com.google.android.material:material:1.13.0` ; composants Material 3 | [Apache 2.0](app/src/main/assets/licenses/material-1.13.0-LICENSE.txt), [source 1.13.0](https://github.com/material-components/material-components-android/blob/1.13.0/LICENSE) |
| [Apache Commons Net](https://commons.apache.org/proper/commons-net/), Apache Software Foundation et contributeurs | `commons-net:commons-net:3.13.0` ; FTP/FTPS | [Apache 2.0](app/src/main/assets/licenses/commons-net-3.13.0-LICENSE.txt), [NOTICE](app/src/main/assets/licenses/commons-net-3.13.0-NOTICE.txt), extraits de `META-INF` du JAR publié |
| [SSHJ](https://github.com/hierynomus/sshj), contributeurs SSHJ | `com.hierynomus:sshj:0.41.2` ; SFTP/SSH | [Apache 2.0](app/src/main/assets/licenses/sshj-0.41.2-LICENSE.txt), [NOTICE complète](app/src/main/assets/licenses/sshj-0.41.2-NOTICE.txt), [source v0.41.2](https://github.com/hierynomus/sshj/tree/v0.41.2) |
| [SMBJ](https://github.com/hierynomus/smbj), contributeurs SMBJ | `com.hierynomus:smbj:0.14.0` ; SMB2/3 | [Apache 2.0](app/src/main/assets/licenses/smbj-0.14.0-Apache-2.0.txt), [en-tête officiel v0.14.0](app/src/main/assets/licenses/smbj-0.14.0-LICENSE_HEADER.txt), [POM 0.14.0](https://repo.maven.apache.org/maven2/com/hierynomus/smbj/0.14.0/smbj-0.14.0.pom) |
| [OkHttp](https://square.github.io/okhttp/), Square et contributeurs | `com.squareup.okhttp3:okhttp:5.3.0` ; WebDAV | [Apache 2.0](app/src/main/assets/licenses/okhttp-5.3.0-LICENSE.txt), [source parent-5.3.0](https://github.com/square/okhttp/blob/parent-5.3.0/LICENSE.txt) |

La NOTICE SSHJ conserve notamment les mentions des dérivations Apache MINA SSHD/Commons Net, les conditions JCraft et les mentions Bouncy Castle. La licence principale Apache 2.0 ne permet pas de retirer ces mentions spécifiques. Le tag SMBJ v0.14.0 contient un `LICENSE_HEADER` et sa licence est déclarée dans le POM ; le texte complet Apache joint provient de l’Apache Software Foundation, sans inventer un fichier `LICENSE` absent de ce tag.

Media3 et ses modules HLS/DASH/RTSP sont en version **1.11.1**, AndroidX Activity **1.11.0**, Tracing **1.3.0**, ML Kit Translation **17.0.3**. AndroidX/Media3 et les bibliothèques Kotlin conservent leurs licences et notices ; le SDK ML Kit et ses modèles gardent les [conditions Google](https://developers.google.com/ml-kit/terms). L’application conserve son attribution Google dédiée. Les noms de protocoles ne signifient pas que les auteurs de ces bibliothèques ont écrit ou validé l’intégration propre au lecteur.

## Calcul GGUF natif

| Composant tiers | Source utilisée | Licence et mentions conservées |
| --- | --- | --- |
| llama.cpp / ggml | Révision `8ca4060b7e30e76baaa5670ee1ec1b760b7f8552`, [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp/tree/8ca4060b7e30e76baaa5670ee1ec1b760b7f8552) | [MIT, auteurs ggml](app/src/main/assets/licenses/llama.cpp.txt) |
| OpenCL-Headers | Version `v2025.07.22`, [KhronosGroup/OpenCL-Headers](https://github.com/KhronosGroup/OpenCL-Headers/tree/v2025.07.22) | [Apache 2.0](app/src/main/assets/licenses/OpenCL-Headers-2025.07.22.txt) ; [copyrights des sources](app/src/main/assets/licenses/OpenCL-Headers-2025.07.22-COPYRIGHT-NOTICES.txt) |
| Vulkan-Headers / Vulkan-Hpp | Version `v1.4.341`, [KhronosGroup/Vulkan-Headers](https://github.com/KhronosGroup/Vulkan-Headers/tree/v1.4.341) | [MIT](app/src/main/assets/licenses/Vulkan-Headers-1.4.341-MIT.txt), choix permis par les en-têtes sous double licence ; [copyrights des sources](app/src/main/assets/licenses/Vulkan-Headers-1.4.341-COPYRIGHT-NOTICES.txt) |
| SPIRV-Headers | Version `vulkan-sdk-1.4.341.0`, [KhronosGroup/SPIRV-Headers](https://github.com/KhronosGroup/SPIRV-Headers/tree/vulkan-sdk-1.4.341.0) | [Licence et mentions de l’archive](app/src/main/assets/licenses/SPIRV-Headers-1.4.341.0.txt) ; [copyrights des en-têtes](app/src/main/assets/licenses/SPIRV-Headers-1.4.341.0-COPYRIGHT-NOTICES.txt) |
| KleidiAI | Archive source `v1.32.0`, [ARM-software/kleidiai](https://github.com/ARM-software/kleidiai/tree/v1.32.0) | [Apache 2.0](app/src/main/assets/licenses/KleidiAI-1.32.0-Apache-2.0.txt) et, pour les parties concernées, [BSD 3-Clause](app/src/main/assets/licenses/KleidiAI-1.32.0-BSD-3-Clause.txt) ; [copyrights des sources](app/src/main/assets/licenses/KleidiAI-1.32.0-COPYRIGHT-NOTICES.txt) |

Les versions et SHA-256 des archives de compilation sont fixés dans CMake. Depuis la 0.5.7, une liaison directe facultative propre à l’application appelle le pilote OpenCL fourni par le téléphone ; ce pilote n’est pas copié dans l’APK. Le chargeur Khronos ICD des versions 0.5.5 et 0.5.6 n’est plus lié à cette compilation. Ses notices conservées dans les assets documentent ces versions historiques. OpenCL et KleidiAI sont activés pour la compilation Android ARM64 concernée ; l’émulateur x86_64 garde son chemin CPU.

Depuis la 0.5.12, le backend Vulkan facultatif ARM64 utilise des en-têtes épinglés : archive Vulkan-Headers SHA-256 `8876aad926b2e72bdefddc34885472d5332e2d20aa3ed57313466f0bc982cf3f` et archive SPIRV-Headers SHA-256 `cab0a654c4917e16367483296b44cdb1d614e3120c721beafcd37e3a8580486c`. Les shaders sont compilés lors de la construction avec le `glslc` hôte fourni par le NDK `27.2.12479018`. Le compilateur et les en-têtes ne sont pas téléchargés ni exécutés par l’application sur le téléphone. Le chargeur Vulkan système est chargé dynamiquement seulement après une demande explicite ; ni ce chargeur ni un pilote GPU ne sont distribués dans l’APK.

Cette section concerne le calcul GGUF natif. Android, Kotlin et les autres dépendances gardent également leurs auteurs et conditions propres. Aucun modèle de production n’est distribué : le choix et l’import d’un GGUF ne remplacent pas ses conditions d’utilisation. Les licences tierces n’accordent pas de licence générale au code propre de ce dépôt.

## Hexagon/HTP — intégration conservée depuis la 0.5.14

Les cinq programmes DSP v73/v75/v79/v81/v85 proviennent des sources MIT de llama.cpp/ggml épinglées et sont construits avant Gradle par [l’outil de préparation](tools/prepare-hexagon.py). Le SDK sert uniquement à la compilation : archive publique Snapdragon Toolchain **6.7.0.0**, SHA-256 **00725fc449916d7b9af2c2a3c6b54d61f3c872d5dbc1d3ca3fd2f839b1d5dc70**, outils Hexagon **21.0.04**. Les métadonnées internes du SDK indiquent 6.7.0.1 ; elles ne changent pas l’identité de l’archive contrôlée.

L’APK conserve les notices du compilateur/runtime `hexagon-tools-NOTICE.txt` et le texte MIT `llama-cpp-MIT.txt` dans ses assets de licences. Les notices spécifiques ne constituent pas une affirmation de licence libre de l’ensemble du SDK. Les programmes sont liés avec les composants runtime de cette chaîne de compilation ; le SDK complet, ses outils et le pilote constructeur FastRPC ne sont pas redistribués dans l’APK. Les bibliothèques `libcdsprpc.so` employées à l’exécution restent celles du téléphone.
