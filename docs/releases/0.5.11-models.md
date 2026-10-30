# Sous-titres en voix 0.5.11 — Modèles GGUF et profils de traduction (préversion)

[Accueil](https://github.com/Lillite/Vocalisation-des-sous-titres-Android#readme) · [Guide des modèles et profils](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/docs/COMPATIBILITE_GGUF_0511.md) · [Validation](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/TESTING.md)

Cette préversion apporte la reconnaissance d’Index-Translate et le rendu des formats de conversation intégrés aux GGUF instruct compatibles. Elle conserve le modèle choisi, ses poids et sa précision de calcul. La **0.5.2 reste la stable recommandée**.

## Identification et distribution

| Élément | Référence |
| --- | --- |
| Version | **0.5.11-models**, versionCode **18** |
| Statut | Préversion vérifiée, sans remplacer la dernière stable |
| APK signé | SousTitresVoix-0.5.11-models.apk |
| Identité Android | fr.soustitresvoix.player.translation, conservée |
| Android minimum | Android 8.0 — API 26 |
| Architectures natives | ARM 64 bits ; x86_64 pour émulateur |
| Signature vérifiée | Certificat durable de l’édition Traduction, SHA-256 304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d |

L’APK a été signé avec le certificat durable après validation de la proposition et reconstruction vérifiée de ses sources fusionnées. **Installer par-dessus l’édition Traduction actuelle, sans désinstaller.** Les clés et mots de passe restent privés et hors du dépôt.

## Changements ajoutés

- **Index-Translate** : nouveau profil spécialisé, distinct de TranslateGemma et Hy-MT, avec ses consignes et son encadrement de conversation.
- **Profils spécialisés conservés** : le mode automatique privilégie les anciennes familles reconnues et conserve leurs formats et résultats ; il reconnaît également Index-Translate.
- **Format intégré reconnu** : pour d’autres GGUF instruct nativement compatibles, Automatique ou le profil explicite **Gabarit intégré au GGUF — formats reconnus** utilise un format natif identifié à partir des métadonnées.
- **Rendu borné** : un message utilisateur, puis début de réponse assistant, via le renderer natif. La logique Jinja arbitraire n’est pas exécutée ; un format absent, inconnu ou un échec de rendu est signalé sans repli opaque en texte brut.
- **Parcours conservés** : contexte récent, occurrence isolée, blocs de six, cache exact local, reprise durable, rapports TXT, export SRT/VTT et conservation des identifiants et minutages de chaque occurrence.
- **Calcul conservé** : aucun changement des poids, de leur quantification, des noyaux, du greedy, du contexte de 2 048 tokens ou de la borne de sortie de 512 tokens.

Le [guide](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/docs/COMPATIBILITE_GGUF_0511.md) distingue fichier, architecture, profil, format, ressources et fidélité. Les références du modèle Index sont les pages du producteur [Index-Translate-9B](https://huggingface.co/IndexTeam/Index-Translate-9B) et [GGUF officiels](https://huggingface.co/IndexTeam/Index-Translate-9B-GGUF).

## Limites

Ces ajouts ne garantissent ni fidélité parfaite ni compatibilité universelle. La reconnaissance du profil ne prouve pas le chargement d’un 9B Q6 sur le téléphone, la RAM suffisante, l’exécution GPU ou une meilleure traduction. La mémoire des poids ne représente pas toute la mémoire d’exécution. Automatique conserve sa réserve et son repli CPU explicite.

Le parcours générique reconnaît les formats intégrés pris en charge par le moteur compilé ; il ne reproduit pas toutes les conditions, filtres, extensions ou instructions particulières de gabarits Jinja personnalisés. Une nouvelle architecture ou un format inconnu peut nécessiter une adaptation ultérieure. La désactivation du raisonnement du profil spécialisé Index ne doit pas être attribuée à tous les profils instruct génériques.

Les stratégies de contexte et la génération restent bornées. Un format reconnu peut encore produire une entrée trop longue, une réponse limitée ou un contresens. Une occurrence terminée et un export complet ne certifient pas sa fidélité. Les langues et les tâches annoncées par un producteur ne sont pas automatiquement toutes intégrées ici.

L’intégration Index concerne le texte ; les projecteurs multimodaux `mmproj` ne sont pas chargés. **Le NPU et l’OCR de film ne sont pas intégrés.** Aucun modèle n’est téléchargé automatiquement et aucune nouvelle destination réseau de traduction n’est ajoutée. L’OCR et la traduction restent indépendants.

## Validation et provenance

Les sources ont été validées avant fusion dans le [run 40951282254](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40951282254). Le [run après fusion 40956078138](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40956078138) a reconstruit la release sans rejouer les suites et confirmé son contenu applicatif identique à l’APK testé. La signature durable, l’identité Android et les alignements ont ensuite été vérifiés.

| Preuve | Valeur |
| --- | --- |
| Source fusionnée | 5b7071d717308d8f97a7daaae909ec20bf633c70 |
| APK signé | SousTitresVoix-0.5.11-models.apk |
| SHA-256 APK | 4fc2e6900b6a4d19dc1ac57fedba9f840543cd5a827aff487617c0d9e67ae3f3 |
| Certificat durable SHA-256 | 304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d |

Les essais automatisés utilisent des textes et modèles miniatures synthétiques. Leur réussite ne mesure ni qualité linguistique de production, performances prolongées, chauffe, autonomie ou TalkBack réel. L’instrumentation sur émulateur et les essais de pilote synthétique ne prouvent pas un calcul GPU sur le OnePlus. Les effectifs appartiennent au run exact et ne sont pas remplacés par ceux d’une version antérieure. Le NPU et l’OCR de film restent hors du périmètre exécuté.

**Le travail propre à ce projet est réalisé intégralement par IA à partir des demandes et retours de l’utilisateur.** Android, Kotlin, Media3, llama.cpp, KleidiAI, OpenCL et les modèles restent attribués à leurs auteurs respectifs. Aucun poids de production ni secret n’est redistribué avec cette préversion.
