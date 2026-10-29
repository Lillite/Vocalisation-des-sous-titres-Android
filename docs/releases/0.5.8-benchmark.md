# Sous-titres en voix 0.5.8 — Comparatif GGUF étendu (préversion)

[Accueil](https://github.com/Lillite/Vocalisation-des-sous-titres-Android#readme) · [Guide du comparatif étendu](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/docs/COMPARATIF_LOCAL_058.md) · [Validation](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/TESTING.md)

Cette préversion ajoute le comparatif GGUF étendu et le profilage du chargement. Elle répond au rapport de la 0.5.7 : calcul Adreno 840 effectivement observé et génération plus rapide, mais chargement GPU plus long et essai limité à un court texte répété. La **0.5.2 reste la stable recommandée**.

## Identification et distribution

| Élément | Référence |
| --- | --- |
| Version | **0.5.8-benchmark**, versionCode **15** |
| Statut | Préversion vérifiée, sans remplacer la dernière stable |
| APK signé | SousTitresVoix-0.5.8-benchmark.apk |
| Identité Android | fr.soustitresvoix.player.translation, conservée |
| Android minimum | Android 8.0 — API 26 |
| Architectures natives | ARM 64 bits ; x86_64 pour émulateur |
| Signature vérifiée | Certificat durable de l’édition Traduction, SHA-256 304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d |

L’APK a été signé avec le certificat durable après validation de la proposition et reconstruction vérifiée de ses sources fusionnées. **Installer par-dessus l’édition Traduction actuelle, sans désinstaller.** Les clés et mots de passe restent privés et hors du dépôt.

## Changements ajoutés

- **Série étendue** : 32 occurrences intégrées, 29 textes distincts, dialogues et panneaux variés, répétitions légitimes identifiées et minutages synthétiques conservés.
- **Comparaison explicite** : ordre CPU/GPU sélectionnable, session conservée pour la série, libération avant changement de mode et réouverture facultative sur une occurrence sans effacer les caches.
- **Chargement détaillé** : durées réelles de l’initialisation, de la préparation OpenCL, du chargement du modèle, du contexte et de l’ouverture totale ; aucune ventilation interne des lectures, allocations ou transferts n’est inventée.
- **Statistiques et intégralité** : comptage des occurrences terminées, agrégation des mesures reçues pour les appels natifs terminés et distributions de latence clairement séparées du chargement. Une erreur conserve le préfixe terminé, sans déclarer le reste complet.
- **Lecture observée** : états et compteurs ponctuels en lecture seule, sans titre ni contenu de sous-titres personnels dans le rapport ; aucune commande automatique de transport ou de voix.
- **Calcul conservé** : mêmes poids, quantification, contexte de production et noyaux épinglés. Aucun modèle de production inclus ou téléchargement automatique ajouté.

Le [guide](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/docs/COMPARATIF_LOCAL_058.md) décrit les boutons et l’interprétation des temps. Le comparatif court reste disponible. Quatre threads constituent un point de départ pour comparer avec le rapport reçu, sans optimum universel établi.

## Limites

La réussite technique d’une occurrence ne mesure pas automatiquement sa fidélité linguistique. Le contexte récent synthétique peut diverger si les sorties des modes diffèrent. L’ordre, le cache système, la chauffe et la lecture simultanée influencent les mesures. Une réouverture ne constitue pas un démarrage à froid garanti.

Les observations ponctuelles du lecteur ne prouvent ni fluidité continue, ni vocalisation intégrale d’un film, ni TalkBack réel, ni autonomie. La mémoire résidente ne couvre pas toutes les allocations GPU ; la température de batterie ne mesure pas celle des unités de calcul. Les performances prolongées doivent être mesurées sur le téléphone avec cette version.

L’OCR et la traduction restent indépendants. **Le NPU et l’OCR de film ne sont pas intégrés.** La préversion 0.5.7 reste une preuve historique attachée à ses propres sources et à son court essai de téléphone.

## Validation et provenance

Les sources ont été validées avant fusion dans le [run 40822001535](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40822001535). Le [run après fusion 40825409538](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40825409538) a reconstruit la release sans rejouer les suites et confirmé son contenu applicatif identique à l’APK testé. La signature durable, l’identité Android et les alignements ont ensuite été vérifiés.

| Preuve | Valeur |
| --- | --- |
| Source fusionnée | 0f0bc4ac189c8565ee47ee12fc4986292bb57716 |
| APK signé | SousTitresVoix-0.5.8-benchmark.apk |
| SHA-256 APK | 68ce0689f5f60598e938dd15e04771428689ec7742549e6185f4daec437772bd |
| Certificat durable SHA-256 | 304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d |

Les essais automatisés utilisent des textes et modèles miniatures synthétiques. Leur réussite ne mesure ni qualité linguistique de production, performances prolongées, chauffe, autonomie ou TalkBack réel. L’instrumentation sur émulateur et les essais de pilote synthétique ne prouvent pas un calcul GPU sur le OnePlus. Les effectifs appartiennent au run exact et ne sont pas remplacés par ceux d’une version antérieure. Le NPU et l’OCR de film restent hors du périmètre exécuté.

**Le travail propre à ce projet est réalisé intégralement par IA à partir des demandes et retours de l’utilisateur.** Android, Kotlin, Media3, llama.cpp, KleidiAI, OpenCL et les modèles restent attribués à leurs auteurs respectifs. Aucun poids de production ni secret n’est redistribué avec cette préversion.
