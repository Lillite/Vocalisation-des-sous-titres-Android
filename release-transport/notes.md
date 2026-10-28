# Sous-titres en voix 0.5.5 — Performances GGUF locales (préversion)

[Accueil](https://github.com/Lillite/Vocalisation-des-sous-titres-Android#readme) · [Guide des performances GGUF](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/docs/TRADUCTION_GGUF_055.md) · [Validation](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/TESTING.md)

Cette préversion ajoute des optimisations de la traduction GGUF locale et le correctif de reconnaissance du modèle officiel Hy-MT2-7B. Les minutages, occurrences, reprises, exports et protections de lecture introduits précédemment restent applicables. La **0.5.2 reste la stable recommandée** ; cette préversion ne doit pas la remplacer comme dernière stable.

## Identification et distribution

| Élément | Référence |
| --- | --- |
| Version | **0.5.5-perf**, versionCode **12** |
| Statut | Préversion vérifiée, sans remplacer la dernière stable |
| APK signé | `SousTitresVoix-0.5.5-perf.apk` |
| Identité Android | `fr.soustitresvoix.player.translation`, conservée |
| Android minimum | Android 8.0 — API 26 |
| Architectures natives | ARM 64 bits ; x86_64 pour émulateur |
| Signature vérifiée | Certificat durable de l’édition Traduction, SHA-256 `304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d` |

L’APK a été signé avec le certificat durable de l’édition Traduction après validation de la proposition et reconstruction vérifiée de ses sources fusionnées. **Ne pas désinstaller l’édition Traduction pour appliquer cette mise à jour.** La clé et ses mots de passe restent hors du dépôt et des fichiers publics.

## Changements ajoutés

- **CPU** : activation des optimisations ARM64 adaptées, sans retirer le chemin CPU portable ni attribuer au NPU le travail du processeur.
- **GPU facultatif** : choix local explicite, disponibilité et travail réellement déporté distingués dans les mesures. Un backend présent dans l’APK ne prouve pas son exécution sur le téléphone.
- **Préfixe exact** : réutilisation de tokens déjà calculés seulement lorsque leur identité et leur état sont compatibles. L’annulation, les erreurs et les changements de contexte invalident les états qui ne peuvent plus être réutilisés.
- **Hy-MT2** : reconnaissance des empreintes publiques exactes des GGUF officiels Q8, Q6 et Q4 avec leur architecture, même lorsque leur nom interne ne mentionne pas Hy-MT2 ; profil explicite possible, sans accepter une architecture incompatible. Une architecture Hunyuan seule ne suffit pas à reconnaître automatiquement tout modèle.
- **Erreurs et mesures** : distinction des problèmes de fichier, profil, backend ou génération ; séparation du chargement, du calcul de la consigne et de la génération. Les textes et le modèle restent sur le téléphone.

Le [guide](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/docs/TRADUCTION_GGUF_055.md) donne le parcours et les limites. Le modèle était déjà chargé une fois par tâche en 0.5.4 ; cette propriété n’est pas présentée comme une nouvelle optimisation. L’identité du cache passe à GGUF v2 et distingue le profil et le mode demandé. Aucun poids n’est inclus ou téléchargé automatiquement. L’OCR reste indépendant et non intégré. Les [attributions](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/ATTRIBUTIONS.md) conservent les licences et copyrights des composants natifs.

## Limites et comparaison

Aucun facteur d’accélération n’est promis. Le GPU peut manquer de mémoire, refuser une opération ou être plus lent selon le modèle, sa quantification et le pilote. Les résultats d’émulateur et de modèles miniatures ne mesurent pas le gain d’un modèle de production sur le OnePlus. **Le NPU, l’OCR de film et la prise en charge complète des anciens conteneurs ne sont pas ajoutés par cette étape.**

Pour comparer, conserver exactement le même GGUF, le même SRT, les langues et le contexte ; distinguer chargement à froid, génération, nombre de tokens réutilisés, mémoire et état thermique. Un essai technique réussi ne démontre pas à lui seul la fidélité linguistique, la stabilité d’un film entier, l’autonomie ou TalkBack réel. Le traitement continu après fermeture de l’application ou écran éteint n’est pas garanti.

## Validation et provenance

Les sources de cette préversion ont été validées avant fusion dans le [run 40721684965](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40721684965). Le [run après fusion 40727662374](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40727662374) a reconstruit la release sans rejouer les suites et confirmé son contenu applicatif identique à l’APK testé. La signature durable, l’identité Android, le contenu hors signature et les alignements ont ensuite été vérifiés.

| Preuve | Valeur |
| --- | --- |
| Source de la préversion | `71a014af332906ecda30b74faadcc5d6ee3ddc9d` |
| APK signé | `SousTitresVoix-0.5.5-perf.apk` |
| APK signé, SHA-256 | `cd07705714eff711283711041abe196ed5f849c8cf8a9ae41cd8fb64dfd16950` |
| Certificat durable, SHA-256 | `304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d` |

Les tests automatisés utilisent des textes et modèles miniatures synthétiques. Leur réussite ne mesure pas la qualité linguistique d’un modèle de production, les performances prolongées du téléphone, la chauffe, l’autonomie, la qualité vocale ou TalkBack réel. L’instrumentation sur émulateur ne prouve pas un déport GPU sur le téléphone. Le NPU et l’OCR de film restent hors du périmètre exécuté. Les effectifs et résultats appartiennent au run exact ; les succès des versions précédentes ne les remplacent pas.

**Le travail propre à ce projet est réalisé intégralement par IA à partir des demandes et retours de l’utilisateur.** Android, Kotlin, Media3, llama.cpp, KleidiAI et les modèles restent attribués à leurs auteurs respectifs. Aucun poids de production ni secret n’est redistribué avec cette préversion.
