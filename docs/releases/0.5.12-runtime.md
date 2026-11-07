# Sous-titres en voix 0.5.12 — Calcul local, Qwen3.5 et diagnostic de chargement (préversion)

[Accueil](https://github.com/Lillite/Vocalisation-des-sous-titres-Android#readme) · [Guide du calcul local](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/docs/TRADUCTION_RUNTIME_0512.md) · [Validation](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/TESTING.md)

Cette préversion apporte un profil Qwen3.5 avec réponse directe, un moteur Vulkan facultatif et des réglages mémoire pour étudier le chargement des grands modèles. Un diagnostic doit rester consultable même si le modèle ne termine pas son initialisation. La **0.5.2 reste la stable recommandée**.

## Identification et distribution

| Élément | Référence |
| --- | --- |
| Version | **0.5.12-runtime**, versionCode **19** |
| Statut | Préversion vérifiée, sans remplacer la dernière stable |
| APK signé | SousTitresVoix-0.5.12-runtime.apk |
| Identité Android | fr.soustitresvoix.player.translation, conservée |
| Android minimum | Android 8.0 — API 26 |
| Architectures natives | ARM 64 bits ; x86_64 pour émulateur |
| Signature vérifiée | Certificat durable de l’édition Traduction, SHA-256 304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d |

L’APK a été signé avec le certificat durable après validation de la proposition et reconstruction vérifiée de ses sources fusionnées. **Installer par-dessus l’édition Traduction actuelle, sans désinstaller.** Les clés et mots de passe restent privés et hors du dépôt.

## Changements ajoutés

- **Qwen3.5 instruct** : profil explicite sans raisonnement, avec reconnaissance automatique des modèles instruct identifiés ; Index-Translate garde sa priorité et son encadrement spécialisé.
- **Identités de cache séparées** : identité v5 pour Qwen3.5 et pour les sélections automatiques aux métadonnées absentes ou ambiguës, afin de ne pas reprendre une ancienne réponse générique. Les familles historiques clairement identifiées conservent leur identité v4 à réglages inchangés.
- **Vulkan facultatif sur ARM64** : option GPU stricte supplémentaire et choix du GPU dans le comparatif. OpenCL et son accès direct restent disponibles ; le CPU ne doit pas initialiser un pilote GPU.
- **Réglages mémoire explicites** : taille des lots et micro-lots de calcul, nombre de couches GPU et allocation compacte du cache d’attention locale pour les modèles compatibles. Les valeurs effectives doivent être rapportées.
- **Diagnostic avant chargement réussi** : suivi des étapes et rapport conservé pour une tentative qui échoue avant la première occurrence ; distinction entre délai dépassé et arrêt du processus, avec motif Android lorsqu’il est disponible et attribuable.
- **Conservation du contenu** : mêmes poids et quantification, capacité native de contexte de 2 048 tokens, borne d’une génération de 512 tokens, cache exact, reprise durable et export SRT/VTT. Chaque occurrence garde son identité et ses minutages.

Le [guide](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/docs/TRADUCTION_RUNTIME_0512.md) décrit les réglages, le GGUF Qwen3.5 retenu comme candidat et la méthode de comparaison. Aucun poids n’est inclus ni téléchargé automatiquement.

## Limites

La reconnaissance d’un profil, la présence d’un backend et une réponse terminée ne certifient ni fidélité parfaite ni compatibilité universelle. Aucun chargement de TranslateGemma 12B Q6_K sur le OnePlus, avantage de Vulkan, classement linguistique ou débit soutenu n’est déduit de cette préparation.

La mémoire du fichier GGUF ne représente pas toute la mémoire d’exécution. Réduire les micro-lots cible les buffers temporaires, pas les poids. Répartir les couches entre CPU et GPU ne crée pas de RAM supplémentaire. Le cache compact doit conserver les mécanismes de recalcul lorsque le préfixe n’est plus disponible.

Le compilateur de shaders glslc 2022.3 du NDK 27.2 ne reconnaît pas sept extensions optionnelles récentes ; les variantes correspondantes restent désactivées. Les kernels standards ont passé une compilation ARM64 isolée. Cela ne prouve ni un avantage sur OpenCL ni une exécution Vulkan sur ce téléphone. Le cache compact d’attention locale reste expérimental, désactivé par défaut et soumis à un essai propre au modèle choisi.

Les modes GPU explicites ne doivent pas présenter un repli CPU comme une réussite GPU. Automatique conserve son parcours OpenCL et son repli CPU signalé. Des arrondis différents peuvent faire diverger les sorties d’un même modèle selon le moteur ; les traductions doivent également être relues.

Les motifs Android et les mesures mémoire peuvent être absents, incomplets ou ponctuels. Un arrêt sans cause établie reste indéterminé ; un signal système seul n’est pas une preuve de mémoire insuffisante.

Le parcours reste textuel : les projecteurs multimodaux `mmproj` ne sont pas chargés. **Le NPU et l’OCR de film ne sont pas intégrés.** Aucun nouveau service réseau de traduction n’est ajouté. OCR et traduction restent indépendants.

## Validation et provenance

Les sources ont été validées avant fusion dans le [run 40985575393](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40985575393). Le [run après fusion 40989453311](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40989453311) a reconstruit la release sans rejouer les suites et confirmé son contenu applicatif identique à l’APK testé. La signature durable, l’identité Android et les alignements ont ensuite été vérifiés.

| Preuve | Valeur |
| --- | --- |
| Source fusionnée | 9f19792656b6a274297a3e23c2d142ddfdb33acc |
| APK signé | SousTitresVoix-0.5.12-runtime.apk |
| SHA-256 APK | 7c545a143950acf7d893a2ec65df2c1587cf7e588801d462f7391e5cd9c97e56 |
| Certificat durable SHA-256 | 304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d |

Les essais automatisés utilisent des textes et modèles miniatures synthétiques. Leur réussite ne mesure ni qualité linguistique de production, performances prolongées, chauffe, autonomie ou TalkBack réel. L’instrumentation sur émulateur et les essais de pilote synthétique ne prouvent pas un calcul GPU sur le OnePlus. Les effectifs appartiennent au run exact et ne sont pas remplacés par ceux d’une version antérieure. Le NPU et l’OCR de film restent hors du périmètre exécuté.

**Le travail propre à ce projet est réalisé intégralement par IA à partir des demandes et retours de l’utilisateur.** Android, Kotlin, Media3, llama.cpp, KleidiAI, OpenCL, Vulkan et les modèles restent attribués à leurs auteurs respectifs. Aucun poids de production ni secret n’est redistribué avec cette préversion.
