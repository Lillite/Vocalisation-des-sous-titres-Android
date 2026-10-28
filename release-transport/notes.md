# Sous-titres en voix 0.5.7 — Accès OpenCL direct (préversion)

[Accueil](https://github.com/Lillite/Vocalisation-des-sous-titres-Android#readme) · [Guide de l’accès direct](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/docs/OPENCL_DIRECT_057.md) · [Validation](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/TESTING.md)

Cette préversion ajoute un accès direct facultatif au pilote OpenCL du téléphone. Elle vise le blocage **ICD_ENTRY_POINT_MISSING** identifié par la 0.5.6 : une plateforme était visible directement, mais le chargeur intermédiaire demandait une extension absente. Le fonctionnement réel du GPU et son éventuel gain restent à mesurer sur le OnePlus. La **0.5.2 reste la stable recommandée**.

## Identification et distribution

| Élément | Référence |
| --- | --- |
| Version | **0.5.7-directcl**, versionCode **14** |
| Statut | Préversion vérifiée, sans remplacer la dernière stable |
| APK signé | SousTitresVoix-0.5.7-directcl.apk |
| Identité Android | fr.soustitresvoix.player.translation, conservée |
| Android minimum | Android 8.0 — API 26 |
| Architectures natives | ARM 64 bits ; x86_64 pour émulateur |
| Signature vérifiée | Certificat durable de l’édition Traduction, SHA-256 304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d |

L’APK a été signé avec le certificat durable après validation de la proposition et reconstruction vérifiée de ses sources fusionnées. **Installer par-dessus l’édition Traduction actuelle, sans désinstaller.** Les clés et mots de passe restent privés et hors du dépôt.

## Changements ajoutés

- **Pilote direct** : résolution facultative des fonctions standard OpenCL dans la bibliothèque fournie par le téléphone, sans exiger le chargeur Khronos ICD.
- **CPU indépendant** : aucun chargement ni sondage du pilote en mode CPU seul ; règles de repli et de libération conservées.
- **Rapport précis** : accès direct distinct du moteur sélectionné et du calcul GPU observé, avec un diagnostic borné si une fonction requise manque.
- **Calcul conservé** : mêmes sources épinglées et noyaux de calcul, poids, quantification, contexte et minutages. Aucun modèle ou pilote du fabricant inclus.
- **Régressions** : essais synthétiques du véritable parcours de liaison avec un pilote sans extension ICD, fonctions absentes, annulation, erreurs et protection des résultats ; la portée de chaque essai doit rester explicite.

Le [guide](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/docs/OPENCL_DIRECT_057.md) décrit le rapport du téléphone et le prochain comparatif à transmettre. Quatre threads restent un réglage provisoire fondé sur les essais reçus, sans optimum universel établi. Les [attributions](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/ATTRIBUTIONS.md) distinguent le code propre à l’application et les composants tiers.

## Limites

Un accès direct réussi ne démontre ni compatibilité complète du modèle, ni exécution GPU, ni accélération. Le chargement des poids et le calcul effectif doivent encore réussir sur le téléphone. Les générations synthétiques ne prouvent pas la fidélité sur un film, la stabilité prolongée, l’autonomie ou TalkBack réel. Le traitement continu après fermeture de l’application ou écran éteint n’est pas garanti.

L’OCR et la traduction restent indépendants. **Le NPU et l’OCR de film ne sont pas intégrés.** Aucun modèle de production ni nouveau service distant n’est ajouté.

## Validation et provenance

Les sources ont été validées avant fusion dans le [run 40776287846](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40776287846). Le [run après fusion 40780700846](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40780700846) a reconstruit la release sans rejouer les suites et confirmé son contenu applicatif identique à l’APK testé. La signature durable, l’identité Android et les alignements ont ensuite été vérifiés.

| Preuve | Valeur |
| --- | --- |
| Source fusionnée | cdeec9e590c35ca69035c67c1afd3b0a6cde2157 |
| APK signé | SousTitresVoix-0.5.7-directcl.apk |
| SHA-256 APK | 0aba11bb5eace394c91cbdbf8ab77c54750add6d89d0b3834eb60ed0667fe82c |
| Certificat durable SHA-256 | 304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d |

Les essais automatisés utilisent des textes et modèles miniatures synthétiques. Leur réussite ne mesure ni qualité linguistique de production, performances prolongées, chauffe, autonomie ou TalkBack réel. L’instrumentation sur émulateur et les essais de pilote synthétique ne prouvent pas un calcul GPU sur le OnePlus. Les effectifs appartiennent au run exact et ne sont pas remplacés par ceux d’une version antérieure. Le NPU et l’OCR de film restent hors du périmètre exécuté.

**Le travail propre à ce projet est réalisé intégralement par IA à partir des demandes et retours de l’utilisateur.** Android, Kotlin, Media3, llama.cpp, KleidiAI, OpenCL et les modèles restent attribués à leurs auteurs respectifs. Aucun poids de production ni secret n’est redistribué avec cette préversion.
