# Accélération réelle : ce qui est intégré et ce qui ne l’est pas

## Intégré

Décodage vidéo via Media3/MediaCodec et ses décodeurs disponibles, sélection normale ou préférence logicielle explicite. Effets graphiques Media3 de recadrage, luminosité et contraste SDR ; leur pipeline peut utiliser OpenGL sur l’appareil. Préparation et hachage WAV hors du thread d’interface ; indexation séquentielle sans décoder une seconde vidéo. Métriques séparant processus lecteur et moteur TTS externe. Le diagnostic ne prétend pas mesurer l’utilisation Adreno ou Hexagon.

## Non intégré ni validé sur OnePlus 15

Aucun backend vocal GPU/NPU, modèle ONNX intégré, contexte QNN spécifique OnePlus, benchmark comparatif CPU/GPU/NPU ou nouvelle bibliothèque complète de codecs. Aucun dispositif ne force un moteur externe à utiliser QNN.

## Sources primaires consultées le 20 octobre 2026

- API Android TextToSpeech : https://developer.android.com/reference/android/speech/tts/TextToSpeech
- QNN Execution Provider : https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html
- Compilation ONNX Runtime pour Android : https://onnxruntime.ai/docs/build/android.html
- Exemple d’un véritable moteur TTS Android spécialisé QNN : https://github.com/cedgeremek/kokoro-offline-tts-android
- Ses limites matérielles : https://github.com/cedgeremek/kokoro-offline-tts-android/blob/main/docs/COMPATIBILITY.md
- Effets vidéo Media3 : https://developer.android.com/reference/androidx/media3/exoplayer/ExoPlayer

L’exemple Kokoro étudié publie une validation sur Galaxy S24 Ultra / Snapdragon 8 Gen 3 / HTP v75, avec voix anglaises. L’auteur distingue contextes matériels spécifiques et repli CPU. Cela n’est pas une preuve de fonctionnement NPU sur le OnePlus 15 et ne constitue pas une voix française prête à intégrer.

## Vérification complémentaire du 20 octobre 2026

Le tableau de compatibilité de cet exemple Kokoro prévoit explicitement le CPU q8 sur SM8850 avec l’APK actuel. Un port NPU nécessite d’autres contextes et bibliothèques, puis une qualification réelle. OnePlus annonce le Snapdragon 8 Elite Gen 5 pour le OnePlus 15 ; la documentation Google LiteRT associe cette plateforme à SM8850 / HTP V81. Les contextes V75 de l’exemple ne sont donc pas une distribution prête à annoncer compatible avec ce téléphone.

- OnePlus : https://www.oneplus.com/us/15
- Google LiteRT, architectures HTP : https://github.com/google-ai-edge/LiteRT/blob/main/litert/vendors/qualcomm/doc/HTP_INSTRUCTIONS.md

Deux autres pistes sont distinguées dans le catalogue :

- NekoSpeak documente l’inférence CPU et ses essais NNAPI/QNN non retenus pour les modèles étudiés. Ce résultat local à un projet ne signifie pas que toute synthèse vocale sur NPU serait impossible. Sources : https://github.com/siva-sub/NekoSpeak ; https://github.com/siva-sub/NekoSpeak/blob/main/docs/adr/ADR-001-nnapi-execution-provider.md ; https://github.com/siva-sub/NekoSpeak/blob/main/docs/adr/ADR-002-qnn-execution-provider.md
- CloneTTS annonce un chemin NPU pour certains Snapdragon dont 8 Elite Gen 5. Il s’agit d’une annonce de l’auteur, sans mesure indépendante ici, dans un dépôt principalement documentaire avec distribution APK. Le français n’est pas confirmé ; ce n’est pas une voix française NPU intégrée ou validée dans notre lecteur. Source : https://github.com/sipeter/CloneTTS ; modèle référencé : https://github.com/k2-fsa/ZipVoice

La documentation QNN décrit différents backends et options de précision. Le choix de la quantification ou du FP16 dépend du modèle, de la version du runtime et du backend. Le contrôle `session.disable_cpu_ep_fallback=1`, associé aux journaux d’exécution, peut aider à prouver qu’un essai n’a pas simplement basculé sur CPU. Il ne remplace ni la mesure du coût total, ni l’écoute, ni les essais de durée sur le téléphone.

## Critères d’un prototype futur falsifiable

Choisir d’abord un modèle français et sa licence ; vérifier les opérateurs et formes de tenseurs, exporter les graphes selon les outils du fournisseur, compiler les contextes pour le SoC et sa version HTP réels. Journaliser le backend réellement sélectionné et les éventuels replis. Comparer sortie audio, latence à froid et à chaud, débit de synthèse, mémoire, pauses de lecture et stabilité thermique sur les mêmes extraits et le même téléphone. Ne pas extrapoler des scores d’un autre SoC. Le backend le plus rapide sur une phrase isolée peut être moins satisfaisant pendant un film.
