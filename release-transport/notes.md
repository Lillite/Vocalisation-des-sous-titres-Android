# Sous-titres en voix 0.5.6 — Diagnostic OpenCL précis (préversion)

[Accueil](https://github.com/Lillite/Vocalisation-des-sous-titres-Android#readme) · [Guide du diagnostic GPU](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/docs/DIAGNOSTIC_OPENCL_056.md) · [Validation](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/TESTING.md)

Cette préversion transmet un diagnostic précis des échecs OpenCL de la traduction GGUF locale. Les erreurs détaillées ne doivent plus être remplacées par le seul message général « moteur indisponible ». La **0.5.2 reste la stable recommandée** ; cette préversion ne doit pas la remplacer comme dernière stable.

## Identification et distribution

| Élément | Référence |
| --- | --- |
| Version | **0.5.6-opencl**, versionCode **13** |
| Statut | Préversion vérifiée, sans remplacer la dernière stable |
| APK signé | `SousTitresVoix-0.5.6-opencl.apk` |
| Identité Android | `fr.soustitresvoix.player.translation`, conservée |
| Android minimum | Android 8.0 — API 26 |
| Architectures natives | ARM 64 bits ; x86_64 pour émulateur |
| Signature vérifiée | Certificat durable de l’édition Traduction, SHA-256 `304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d` |

L’APK a été signé avec le certificat durable de l’édition Traduction après validation de la proposition et reconstruction vérifiée de ses sources fusionnées. **Ne pas désinstaller l’édition Traduction pour appliquer cette mise à jour.** La clé et ses mots de passe restent hors du dépôt et des fichiers publics.

## Changements ajoutés

- **Erreur précise** : étape et raison bornées conservées entre le code natif, le service Android, le moteur de traduction et le rapport copiable. Les informations inconnues ne sont pas inventées.
- **OpenCL** : interrogation directe du pilote distinguée du chargeur ICD et de l’appareil reconnu ; statut numérique lorsqu’il a réellement été mesuré, durée et éventuelle réutilisation du résultat de préparation.
- **Confidentialité** : aucun journal natif brut, chemin privé, nom de fichier ou texte personnel ajouté au diagnostic technique.
- **Durées compréhensibles** : le premier appel comprend son chargement éventuel ; une mesure de chargement réussi absente ne signifie pas absence de tentative.
- **Parcours conservé** : CPU, strict GPU sans faux repli, libération confirmée, annulation, reprise et export complet gardent leurs protections. Les poids, la quantification et le contexte ne sont pas réduits pour accélérer l’essai.

Le [guide du diagnostic](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/docs/DIAGNOSTIC_OPENCL_056.md) décrit l’essai à transmettre après installation. Quatre threads ont donné un temps CPU total inférieur d’environ 19 % à huit threads dans les deux rapports reçus ; les conditions n’étaient pas une comparaison contrôlée répétée. Ce réglage reste provisoire et propre à ces observations. Aucun modèle de production ni code n’est téléchargé au démarrage. Les [attributions](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/ATTRIBUTIONS.md) des composants tiers restent applicables.

## Limites

Cette étape améliore le diagnostic ; **elle ne démontre pas la résolution de l’échec GPU ni un gain supplémentaire de vitesse**. La cause sur le téléphone reste à établir à partir du rapport détaillé. Le délai de 50 ms du second échec ne constitue pas une mesure de calcul GPU. Les générations synthétiques ne prouvent pas la fidélité sur un film, la stabilité prolongée, l’autonomie ou TalkBack réel.

L’OCR et la traduction restent indépendants. **Le NPU et l’OCR de film ne sont pas intégrés.** Le traitement continu après fermeture de l’application ou écran éteint n’est pas garanti. L’identité Android réellement mesurée doit accompagner les futurs rapports, notamment après mise à jour du système.

## Validation et provenance

Les sources de cette préversion ont été validées avant fusion dans le [run 40751406769](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40751406769). Le [run après fusion 40756758677](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40756758677) a reconstruit la release sans rejouer les suites et confirmé son contenu applicatif identique à l’APK testé. La signature durable, l’identité Android, le contenu hors signature et les alignements ont ensuite été vérifiés.

| Preuve | Valeur |
| --- | --- |
| Source de la préversion | `1e982b907c6d322cb4066da743c9a8fefcabe7a2` |
| APK signé | `SousTitresVoix-0.5.6-opencl.apk` |
| APK signé, SHA-256 | `58af4d6f4ae9efe65e21877e442275f74bdd6f1168e430dd623885a6eeb9aded` |
| Certificat durable, SHA-256 | `304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d` |

Les tests automatisés utilisent des textes et modèles miniatures synthétiques. Leur réussite ne mesure pas la qualité linguistique d’un modèle de production, les performances prolongées du téléphone, la chauffe, l’autonomie, la qualité vocale ou TalkBack réel. L’instrumentation sur émulateur ne prouve pas un déport GPU sur le téléphone. Le NPU et l’OCR de film restent hors du périmètre exécuté. Les effectifs et résultats appartiennent au run exact ; les succès des versions précédentes ne les remplacent pas.

**Le travail propre à ce projet est réalisé intégralement par IA à partir des demandes et retours de l’utilisateur.** Android, Kotlin, Media3, llama.cpp, KleidiAI, OpenCL et les modèles restent attribués à leurs auteurs respectifs. Aucun poids de production ni secret n’est redistribué avec cette préversion.
