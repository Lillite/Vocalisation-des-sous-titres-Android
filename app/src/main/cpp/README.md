# Moteur natif — diagnostic CPU et traduction CPU/OpenCL

Cette bibliothèque exécute réellement une petite génération **CPU** avec
llama.cpp, révision `8ca4060b7e30e76baaa5670ee1ec1b760b7f8552`. L'archive et
son SHA-256 sont fixés dans CMake. Aucun poids entraîné, vidéo personnelle, appel réseau,
serveur ou moteur OCR n'est intégré à la sonde.

Le contexte de diagnostic est de **512 tokens** et la sortie est bornée à
64 tokens maximum. Ces bornes concernent uniquement la sonde. Un succès prouve le calcul de cet essai précis, jamais la qualité
linguistique, la disponibilité d'un autre modèle, ni l'accélération GPU/NPU.
Les résultats indiquent les durées monotones, le nombre de tokens et le pic RSS
du processus séparé depuis son lancement, pas une mesure de la RAM du modèle
seul. Le modèle, le contexte et le sampler sont libérés après l'essai.

Le diagnostic Android utilise `LocalModelProbeService` dans le processus
`:model_diagnostic` non exporté. Les fichiers sont limités à l'espace privé de
l'application. L'interface de diagnostic exige une action explicite et interdit
l'essai quand le lecteur ou la narration travaillent. Aucun texte personnel
n'est utilisé ; l'entrée synthétique est fixée dans le code.

Les architectures TranslateGemma identifiées par leur nom de métadonnées
utilisent le gabarit textuel anglais→français officiel. Les autres modèles
reçoivent uniquement une courte complétion synthétique : aucune affirmation
concernant leurs conversations ou leur traduction.

La traduction utilise une session persistante distincte : `local_translation_engine`
charge le modèle une fois et crée un contexte au plus de **2 048 tokens**. Chaque
requête contient son contexte explicite. Un préfixe de tokens strictement identique
peut réutiliser le KV de la même session : les positions doivent encore être
présentes (`seq_pos_min == 0`, maximum suffisant), le retrait du suffixe doit
réussir, et le dernier token de la nouvelle requête est toujours réévalué. En
cas de mémoire SWA ayant évincé le début, d'erreur ou d'annulation, le cache est
vidé. Aucun état n'est repris d'un autre modèle, d'une autre session ou d'une tâche
reprise après fermeture. La sortie est bornée à **1 024 tokens** par génération (512 demandés par
le moteur de traduction). Une fin de sortie, de contexte ou de taille ne produit
jamais une traduction réussie : seul un EOS réellement observé permet de livrer
le texte. Les séquences UTF-8 invalides sont refusées.

`LocalGgufTranslationService`, non exporté, partage le processus
`:model_diagnostic` avec la sonde ; ce processus reste distinct du lecteur.
`ModelLease` réserve une seule charge de modèle dans ce processus, y compris
entre diagnostic et traduction. Le client attend uniquement sur un worker ;
annuler et fermer n'attendent pas un calcul natif. Une barrière `closeAndAwait`,
réservée aux workers, attend l'acquittement de libération avant d'enchaîner deux
mesures. Une simple déconnexion d'un Binder encore vivant ne vaut pas acquittement.
La fermeture du service annule le calcul et libère la session. Si une opération native reste bloquée,
seul ce processus privé peut être arrêté après le délai de grâce ; le lecteur
n'est jamais tué par cette procédure. Cette séparation ne constitue pas un
bac à sable de sécurité.

Compilation Android : NDK 27.2.12479018, CMake 3.22.1, ABIs arm64-v8a et x86_64.
Les dépendances sont téléchargées au build uniquement ; les bibliothèques
natives sont empaquetées dans l'APK. CPU portable (pas de flags natifs de l'hôte),
SSE4.2 sans AVX pour l'ABI x86_64. ARM64 active KleidiAI avec sélection des
microkernels compatibles à l'exécution, sans relever globalement le jeu d'instructions.
L'ABI ARM64 inclut aussi OpenCL Adreno optionnel ; x86_64 reste CPU. Vulkan et
Hexagon ne sont pas intégrés dans cette version. Les identifiants llama/ggml sont fixés à la source et les chemins du
compilateur sont normalisés pour ne pas dépendre du commit de l'application
ou du chemin de checkout.

Modes d'exécution :

- `CPU` ne charge pas le pilote OpenCL et ne déporte aucune couche.
- `AUTO` utilise OpenCL si le pilote et le budget indicatif de RAM le permettent ;
  sinon le CPU est retenu avec une raison explicite. Le budget compare la taille
  du fichier et une réserve de 1 Gio à `MemAvailable` ; il ne prouve ni une
  allocation réussie ni un manque de mémoire effectif.
- `OPENCL` demande strictement ce backend. Un échec ou une génération sans nœud
  de calcul GPU observé n'est jamais présenté comme un essai GPU réussi. Le
  nombre de couches peut être borné explicitement ; il n'y a pas d'autotuning
  de déport partiel dans cette version.

Les en-têtes OpenCL et KleidiAI sont épinglés par SHA-256 dans CMake.
L’adaptateur C statique de l’application remplace le chargeur ICD et transmet
directement les appels au pilote, sans exiger son extension ICD. Le chargeur constructeur `libOpenCL.so` n'est ni empaqueté
ni une dépendance `DT_NEEDED` obligatoire du JNI : il est ouvert uniquement pour
une demande GPU. Le manifeste le déclare optionnel. Le registre OpenCL est
ajouté explicitement après détection ; un repli CPU dans un nouveau processus
ne rouvre donc pas un pilote qui a fait planter l'essai GPU. Les sources des
kernels sont embarquées ; aucun code ou modèle n'est téléchargé au runtime.
Le cache des programmes compilés utilise un sous-dossier privé de `cacheDir`.

Le rapport distingue sélection de backend et calcul observé. Le compteur GPU
provient d'un hook sur le retour réussi de `ggml_backend_opencl_graph_compute`,
puis n'est publié qu'après `llama_synchronize`. Il compte des nœuds calculés,
pas exactement les kernels fusionnés. Les couches annoncées par le chargeur
sont rapportées séparément ; une valeur inconnue reste `-1`. Les durées de
préfill/génération, tokens réutilisés/réévalués, RSS courant, pic RSS et RAM
système disponible sont des mesures différentes.

Le dossier `tests` fournit un modèle minuscule entièrement synthétique et un
exécutable utilisant le même vrai moteur pour vérifier chargement, calcul,
annulation, concurrence et refus d'un GGUF corrompu, puis une nouvelle
génération après échec. Exécution depuis la racine du dépôt :

```sh
python3 app/src/main/cpp/tests/make_tiny_gguf.py /tmp/subtitle-synthetic.gguf
cmake -S app/src/main/cpp -B build/native-probe -DCMAKE_BUILD_TYPE=Release
cmake --build build/native-probe --target model-probe-smoke -j2
build/native-probe/model-probe-smoke /tmp/subtitle-synthetic.gguf
python3 app/src/main/cpp/tests/make_tiny_gguf.py /tmp/subtitle-eos.gguf --terminating
cmake --build build/native-probe --target local-translation-smoke -j2
python3 app/src/main/cpp/tests/make_tiny_gguf.py /tmp/subtitle-context.gguf --terminating --context-sensitive
build/native-probe/local-translation-smoke /tmp/subtitle-eos.gguf /tmp/subtitle-synthetic.gguf /tmp/subtitle-context.gguf
```

Ces contrôles hôte ne remplacent pas l'instrumentation Android ou les essais
sur OnePlus. L'instrumentation utilise également le vrai JNI et le processus
séparé de l'APK avec la petite fixture générée localement ; aucun poids entraîné
n'est téléchargé pour les tests.

La fixture `--terminating` est également synthétique : elle produit un token
« probe », puis EOS. Elle vérifie le vrai chemin de terminaison, la réutilisation
de la session, le refus de sortie tronquée et l'exclusion du diagnostic. Cette
fixture n'est générée que pour les assets de l'APK de tests Android ; elle ne
constitue jamais un modèle de traduction ni une preuve de qualité linguistique.

Le mode `--context-sensitive`, réservé au smoke hôte, ajoute une attention non
nulle et des embeddings déterministes variés. Les logits obtenus avec préfixe
réutilisé sont comparés à des sessions neuves, y compris après changement ou
raccourcissement du contexte et après erreur. L'accès aux logits de test est
compilé uniquement hors Android ; il n'existe pas dans l'APK distribué.
