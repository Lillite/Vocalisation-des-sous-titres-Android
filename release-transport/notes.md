# Sous-titres en voix 0.5.3 — Diagnostic local (préversion)

[Accueil](https://github.com/Lillite/Vocalisation-des-sous-titres-Android#readme) · [Guide du diagnostic](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/docs/DIAGNOSTIC_LOCAL_053.md) · [Validation](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/TESTING.md)

**Préversion de diagnostic : la 0.5.2 reste la stable recommandée.** Cette étape ajoute un diagnostic local pour préparer les futurs moteurs d’OCR et de traduction : mesures Android, essai court de décodage vidéo et exécution réelle d’un GGUF sur CPU. La [0.5.2 publiée](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.5.2) reste la version stable recommandée.

## Identification

| Élément | Référence |
| --- | --- |
| Version | **0.5.3-diagnostic**, versionCode **10** |
| Statut de publication | Préversion, sans remplacer la dernière stable |
| APK de distribution | `SousTitresVoix-0.5.3-diagnostic.apk` |
| Identité Android | `fr.soustitresvoix.player.translation`, conservée |
| Android minimum | Android 8.0 — API 26 |
| Architectures du diagnostic | ARM 64 bits pour téléphone ; x86_64 pour émulateur |
| Signature | Même certificat durable que la série Traduction, vérifié |

L’APK de cette préversion a été signé avec le même certificat durable que l’édition Traduction, après validation et reconstruction des sources fusionnées. La clé et ses mots de passe restent hors du dépôt et des artefacts publics. **Ne pas désinstaller l’édition Traduction pour préparer cette mise à jour.** Aucun nouveau certificat de distribution n’a été créé.

## Diagnostic ajouté

Ouvrir **Réglages de la voix et du confort → Diagnostic → Diagnostic local : appareil, vidéo et modèle**.

- **Appareil** : mesures de RAM, mémoire du processus, espace disponible, état thermique et inventaire des décodeurs déclarés par Android. Une capacité déclarée reste distincte d’un essai effectué.
- **Vidéo** : démonstration synthétique ou fichier local choisi explicitement, avec au plus **8 images sur 10 secondes**. Les erreurs et résultats partiels restent visibles ; l’échantillon ne certifie pas un film complet ni tous les anciens formats.
- **GGUF sur CPU** : petit modèle synthétique intégré pour essayer le moteur sans téléchargement ; modèle personnel sélectionné et copié dans l’espace privé pour un essai distinct. Le chargement et la génération sur une phrase synthétique utilisent **llama.cpp via JNI**, dans un processus de service séparé. Le rapport distingue durées, jetons, mémoire observée, fin, annulation et échec. Les imports interrompus peuvent être nettoyés sans modifier l’original.
- **Commandes indépendantes** : mesures, essai vidéo et essai de modèle se lancent séparément. Fermer annule le diagnostic ; aucune réponse tardive ne doit relancer la lecture ou la voix. Le rapport est copiable dans le presse-papiers, sans export de fichier à cette étape.

Aucun poids n’est téléchargé automatiquement, aucun fichier personnel n’est envoyé et aucun nouveau service Internet n’est ajouté. Le modèle ne passe pas par PocketPal : l’application utilise directement la copie privée du GGUF choisi.

## Périmètre de cette première étape

L’OCR des textes incrustés, PGS/VobSub et la traduction de sous-titres avec TranslateGemma **ne sont pas encore intégrés**. Un essai synthétique de GGUF ne mesure pas la fidélité d’une traduction de film. Le CPU est le seul backend exécuté ; **GPU et NPU restent non testés**. L’inventaire matériel ne permet aucune affirmation de leur performance.

Le diagnostic est indépendant de la vocalisation existante. L’indépendance future de l’OCR et de la traduction, leur fonctionnement pendant le visionnage et la prise en charge complète des anciens conteneurs restent les étapes suivantes.

## Validation et provenance

Les sources de cette préversion ont été validées avant fusion dans le [run 40641431843](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40641431843). Les rapports du run préalable confirment **600 tests réussis : 178 noyau, 316 JVM application et 106 Android**, sans échec, erreur ni test ignoré. Le [run après fusion 40643641925](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40643641925) a reconstruit la release sans rejouer les suites et confirmé son contenu applicatif identique à l’APK testé. La signature locale a ensuite été vérifiée, ainsi que le contenu hors signature et l’alignement.

| Preuve | Valeur |
| --- | --- |
| Source de la préversion | `3b68b118c3f7d21ef47a47e6eb5611ceb34f2055` |
| APK signé | `SousTitresVoix-0.5.3-diagnostic.apk` |
| APK signé, SHA-256 | `c622d3f49bced6f1afeb8abd35060841cc2f7636f75576e5e6c1bc5af18d80d1` |
| Certificat durable, SHA-256 | `304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d` |

Les essais automatisés utilisent des contenus synthétiques. Ils ne remplacent pas l’écoute réelle, TalkBack sur téléphone, les mesures de chauffe/autonomie ni une évaluation linguistique. GPU/NPU, OCR de film et traduction de sous-titres par TranslateGemma restent hors du périmètre exécuté de cette étape.

**Le travail propre à ce projet est réalisé intégralement par IA à partir des demandes et retours de l’utilisateur.** Android, les bibliothèques et le moteur llama.cpp restent attribués à leurs auteurs respectifs.
