# Sonde native de modèle — étape 1

Cette bibliothèque exécute réellement une petite génération **CPU** avec
llama.cpp, révision `8ca4060b7e30e76baaa5670ee1ec1b760b7f8552`. L'archive et
son SHA-256 sont fixés dans CMake. Aucun poids entraîné, vidéo personnelle, appel réseau,
serveur ou moteur OCR n'est intégré à la sonde.

Le contexte de diagnostic est de **512 tokens** et la sortie est bornée à
64 tokens maximum. Ces bornes concernent la sonde, pas les futurs traitements
intégraux. Un succès prouve le calcul de cet essai précis, jamais la qualité
linguistique, la disponibilité d'un autre modèle, ni l'accélération GPU/NPU.
Les résultats indiquent les durées monotones, le nombre de tokens et le pic RSS
du processus séparé depuis son lancement, pas une mesure de la RAM du modèle
seul. Le modèle, le contexte et le sampler sont libérés après l'essai.

L'exécution Android doit utiliser `LocalModelProbeService` dans le processus
`:model_diagnostic` non exporté. Les fichiers sont limités à l'espace privé de
l'application. L'interface de diagnostic exige une action explicite et interdit
l'essai quand le lecteur ou la narration travaillent. Aucun texte personnel
n'est utilisé ; l'entrée synthétique est fixée dans le code.

Les architectures TranslateGemma identifiées par leur nom de métadonnées
utilisent le gabarit textuel anglais→français officiel. Les autres modèles
reçoivent uniquement une courte complétion synthétique : aucune affirmation
concernant leurs conversations ou leur traduction.

Compilation Android : NDK 27.2.12479018, CMake 3.22.1, ABIs arm64-v8a et x86_64.
Les dépendances sont téléchargées au build uniquement ; les bibliothèques
natives sont empaquetées dans l'APK. CPU portable (pas de flags natifs de l'hôte),
SSE4.2 sans AVX pour l'ABI x86_64, pas d'OpenCL, Vulkan ou Hexagon dans cette
étape. Les identifiants llama/ggml sont fixés à la source et les chemins du
compilateur sont normalisés pour ne pas dépendre du commit de l'application
ou du chemin de checkout.

Le dossier `tests` fournit un modèle minuscule entièrement synthétique et un
exécutable utilisant le même vrai moteur pour vérifier chargement, calcul,
annulation, concurrence et refus d'un GGUF corrompu, puis une nouvelle
génération après échec. Exécution depuis la racine du dépôt :

```sh
python3 app/src/main/cpp/tests/make_tiny_gguf.py /tmp/subtitle-synthetic.gguf
cmake -S app/src/main/cpp -B build/native-probe -DCMAKE_BUILD_TYPE=Release
cmake --build build/native-probe --target model-probe-smoke -j2
build/native-probe/model-probe-smoke /tmp/subtitle-synthetic.gguf
```

Ces contrôles hôte ne remplacent pas l'instrumentation Android ou les essais
sur OnePlus. L'instrumentation utilise également le vrai JNI et le processus
séparé de l'APK avec la petite fixture générée localement ; aucun poids entraîné
n'est téléchargé pour les tests.
