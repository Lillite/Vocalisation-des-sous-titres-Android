# Sous-titres en voix 0.5.9 — Traduction locale et cache (préversion)

[Accueil](https://github.com/Lillite/Vocalisation-des-sous-titres-Android#readme) · [Guide traduction locale 0.5.9](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/docs/TRADUCTION_LOCALE_059.md) · [Validation](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/TESTING.md)

Cette préversion apporte les corrections du format de traduction, du contexte récent et du cache GGUF. Elle conserve le modèle choisi, ses poids et sa précision de calcul. La **0.5.2 reste la stable recommandée**.

## Identification et distribution

| Élément | Référence |
| --- | --- |
| Version | **0.5.9-local**, versionCode **16** |
| Statut | Préversion vérifiée, sans remplacer la dernière stable |
| APK signé | SousTitresVoix-0.5.9-local.apk |
| Identité Android | fr.soustitresvoix.player.translation, conservée |
| Android minimum | Android 8.0 — API 26 |
| Architectures natives | ARM 64 bits ; x86_64 pour émulateur |
| Signature vérifiée | Certificat durable de l’édition Traduction, SHA-256 304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d |

L’APK a été signé avec le certificat durable après validation de la proposition et reconstruction vérifiée de ses sources fusionnées. **Installer par-dessus l’édition Traduction actuelle, sans désinstaller.** Les clés et mots de passe restent privés et hors du dépôt.

## Changements ajoutés

- **Format TranslateGemma** : séparation exacte de l’instruction et de la source selon le template épinglé ; contexte personnel reconstruit de façon cohérente dans les tours précédents. Ce contexte personnel reste une adaptation manuelle, à évaluer.
- **Contexte complet** : suppression de la coupe indépendante à 300 points de code des anciennes paires source/traduction. Une paire trop coûteuse peut être retirée entière de l’historique, avant subdivision éventuelle de la source courante conservée intégralement.
- **Cache exact élargi** : prise en compte des tokens de sortie réellement décodés avec succès, sans reconstruction approximative ni déplacement arbitraire de mémoire. Le glissement de l’historique limite les gains possibles.
- **Identité v3** : les nouvelles tâches utilisent une nouvelle identité de configuration ; les anciens résultats restent disponibles sous leur identité d’origine.
- **Fermeture coordonnée** : attente bornée de la libération native sur le worker des tâches locales, avec interface non bloquante et erreur perceptible.
- **Entrées protégées** : garde renforcée des marqueurs réservés dans la source, l’historique et le contexte, sans suppression silencieuse de texte.
- **Parcours conservés** : 32 occurrences synthétiques de référence, profils CPU/GPU, reprise, export, narration et minutages. Aucun changement de poids, quantification, noyaux ou taille de contexte natif ; aucun téléchargement automatique de modèle.

Le [guide](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/docs/TRADUCTION_LOCALE_059.md) décrit le comportement du contexte et du cache, les limites et les essais sur téléphone.

## Limites

Ces corrections d’intégration ne garantissent ni fidélité parfaite ni accélération de chaque occurrence. Aucun remplacement codé en dur des exemples du comparatif n’est utilisé. Une traduction terminée peut encore modifier un nombre, un pronom ou une condition ; il faut comparer les sources et les sorties sur des textes variés.

Le cache réutilise seulement un préfixe exact encore disponible. Les deux paires d’historique peuvent glisser et changer le début du prompt. Le modèle est déjà réutilisé dans une tâche ; cette version ne le maintient pas chargé entre des tâches indépendantes. Automatique conserve sa réserve mémoire et peut choisir le CPU même après un essai GPU strict réussi dans d’autres conditions.

Les mesures du téléphone avec cette version restent nécessaires pour établir le gain réel, la stabilité, la chauffe, l’autonomie et l’usage simultané vidéo, traduction et voix. Les observations ponctuelles du lecteur ne prouvent ni fluidité continue ni vocalisation complète d’un film. La mémoire résidente ne couvre pas toutes les allocations GPU ; la température de batterie n’est pas celle du CPU ou du GPU.

L’OCR et la traduction restent indépendants. **Le NPU et l’OCR de film ne sont pas intégrés.** Les résultats de versions antérieures restent attachés à leurs propres sources.

## Validation et provenance

Les sources ont été validées avant fusion dans le [run 40840935299](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40840935299). Le [run après fusion 40844418035](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40844418035) a reconstruit la release sans rejouer les suites et confirmé son contenu applicatif identique à l’APK testé. La signature durable, l’identité Android et les alignements ont ensuite été vérifiés.

| Preuve | Valeur |
| --- | --- |
| Source fusionnée | 49c22dab7b5eb07fffc27c7914bfd50ca31b8812 |
| APK signé | SousTitresVoix-0.5.9-local.apk |
| SHA-256 APK | 7cbfa79a3690e4d2ab4dec16b74b19e2bf8fe8d253a778f420d549e4ae1b78ac |
| Certificat durable SHA-256 | 304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d |

Les essais automatisés utilisent des textes et modèles miniatures synthétiques. Leur réussite ne mesure ni qualité linguistique de production, performances prolongées, chauffe, autonomie ou TalkBack réel. L’instrumentation sur émulateur et les essais de pilote synthétique ne prouvent pas un calcul GPU sur le OnePlus. Les effectifs appartiennent au run exact et ne sont pas remplacés par ceux d’une version antérieure. Le NPU et l’OCR de film restent hors du périmètre exécuté.

**Le travail propre à ce projet est réalisé intégralement par IA à partir des demandes et retours de l’utilisateur.** Android, Kotlin, Media3, llama.cpp, KleidiAI, OpenCL et les modèles restent attribués à leurs auteurs respectifs. Aucun poids de production ni secret n’est redistribué avec cette préversion.
