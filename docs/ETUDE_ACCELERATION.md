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

## Critères d’un prototype futur falsifiable

Choisir d’abord un modèle français et sa licence ; vérifier les opérateurs et formes de tenseurs, exporter les graphes selon les outils du fournisseur, compiler les contextes pour le SoC et sa version HTP réels. Journaliser le backend réellement sélectionné et les éventuels replis. Comparer sortie audio, latence à froid et à chaud, débit de synthèse, mémoire, pauses de lecture et stabilité thermique sur les mêmes extraits et le même téléphone. Ne pas extrapoler des scores d’un autre SoC. Le backend le plus rapide sur une phrase isolée peut être moins satisfaisant pendant un film.
