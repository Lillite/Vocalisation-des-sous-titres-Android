# Contribuer à Sous-titres en voix

[Accueil](README.md) · [Documentation](docs/README.md) · [Architecture](docs/ARCHITECTURE.md) · [Validation](TESTING.md) · [Consignes](AGENTS.md)

`main` est la référence du projet. Un travail terminé et vérifié doit y être fusionné avant d’être annoncé comme livré. La 1.1.1 conserve l’identité `fr.soustitresvoix.player.translation`, le code 25 et le certificat durable de la série Traduction. Une release stable ne transforme pas les backends expérimentaux en matériel universellement qualifié.

## Préparer une modification

1. Partir du `main` à jour, lire `AGENTS.md` et vérifier l’état des branches avant de réutiliser une branche de travail.
2. Définir le comportement observable et sa couche dans la [carte du code](docs/ARCHITECTURE.md#carte-du-code). Préserver les identifiants et minutages de chaque occurrence, y compris panneaux, répétitions et chevauchements.
3. Ajouter une régression utile pour un défaut corrigé. Conserver Pause, déplacement, callback périmé, fin, reprise et annulation ; ne pas retirer un contrôle pour faire passer une suite.
4. Faire les vérifications locales pertinentes puis réunir code, tests, README, index, journal et notes de release dans la même PR.
5. Exécuter la campagne complète GitHub Actions sur le changement final **avant chaque fusion** : construction des APK, JVM/lint et instrumentation du release de test. Contrôler les XML frais par rapport à l’inventaire source ; une compilation seule ou un ancien commit ne suffit pas.
6. Décrire dans la PR le problème, le comportement obtenu, les vérifications réellement exécutées et les limites. Résoudre les conflits en préservant les corrections récentes.
7. Après réussite des contrôles requis, fusionner par **commit de merge à deux parents**. Une protection de branche, un test en échec ou une provenance différente reste bloquant ; ne pas contourner la règle ni annoncer la livraison.

Le projet doit rester utilisable en CPU sans pilote GPU/NPU. Aucune baisse de précision, réduction silencieuse de texte, perte d’occurrence, acceptation arbitraire d’un certificat ou changement opaque de fournisseur ne peut servir d’optimisation. Le texte des sous-titres est une donnée, pas une instruction.

## Compiler et interpréter les résultats

Les versions Java/Android/Gradle, commandes et niveaux de preuve sont dans [TESTING.md](TESTING.md). Le code se partage entre noyau Kotlin pur `core`, adaptateurs Android `app`, JNI/C++ et outils de provenance. Les dépendances Gradle et natives sont épinglées ; tout changement doit mettre à jour les notices pertinentes et leur [attribution](ATTRIBUTIONS.md).

Une réussite JVM n’est pas une instrumentation Android. Une inférence native hôte n’est pas un essai d’accélérateur physique. TalkBack, vraie voix, codec, serveur réseau, température, stabilité et fidélité linguistique demandent des essais identifiés. Conserver les échecs intermédiaires et les séparer des résultats finaux ; ne pas compter des XML périmés comme nouvelle exécution.

Ne pas lire ni téléverser de vidéos personnelles pour déboguer. Employer d’abord la démonstration et les corpus synthétiques. Ne pas ajouter un service, une collecte ou des accès secrets hors du périmètre expressément autorisé. Un rapport doit être expurgé de secrets, liens personnels, texte et chemins de films privés.

## Préserver l’historique et la documentation

Les pages courantes sont le README, l’index, le guide utilisateur, les sources médias, les modèles, l’architecture, la validation et cette contribution. Les documents datés/de version conservent leurs constats d’origine. Les anciennes vues de README, architecture et validation ont été préservées dans `docs/archives` lors de la réorganisation 1.0.0. Ne pas transformer une ancienne attente de test en réussite actuelle.

Conserver les commits et PR explicites ; ne pas réintroduire une ancienne implémentation en consolidant des branches. Ne pas déplacer un tag correct publié. Pour corriger un ancien nom, établir d’abord la correspondance avec sa source et ses APK, conserver les preuves puis créer le tag canonique approprié.

Le manifeste [`.github/release-history.json`](.github/release-history.json) décrit l’historique et `next_release`. Pour 1.1.1, le tag déclaré est `v1.1.1`, `prerelease=false`, `latest=true`. Ne pas inventer d’identifiant GitHub, de run ni de SHA-256 inconnu. La synchronisation conserve la recommandation publiée existante avant la publication, puis vérifie la source sur `main` et les assets avant de l’actualiser.

## Publier un APK vérifié

La compilation après fusion et la publication sont distinctes. `release-build.yml` reconstruit sur `main` **sans rejouer les suites déjà réussies** : contrôler arbre et parents par rapport à la proposition testée, puis contenu applicatif identique de l’APK hors seules métadonnées de signature autorisées. Une provenance ou un contenu différent interdit de réutiliser les résultats.

Signer ensuite avec la **clé durable existante**, conservée hors dépôt. `SUBTITRES_SIGNING_PROPERTIES` désigne les propriétés externes ; PKCS12, mots de passe et jetons restent hors versionnement. Ne jamais générer une nouvelle clé en remplacement pour la même application. Vérifier manifeste, version/code/applicationId, certificat, alignement et empreinte de l’APK exact avant son transport.

Pour 1.1.1, `publish-apk.yml` reçoit l’APK déjà signé sur la branche temporaire `release-upload/v1.1.1`. Son unique parent doit être la source validée sur `main` ; son diff ne contient que le transport prévu. La source de `main` reste sans `release-upload`. Aucun secret ni URL de téléchargement fourni par les métadonnées ne devient une instruction du workflow.

| Format | Fichiers |
| --- | --- |
| APK unique | `release-upload/release.json`, `release-upload/notes.md`, `release-upload/SousTitresVoix-1.1.1.apk` |
| Fragments binaires | Même JSON et notes ; `release-upload/apk-parts/0000.part`, `0001.part`, etc., sans APK complet dans le checkout |

Les champs sont exactement `tag`, `source_sha`, `apk_sha256`, `signer_sha256`, `apk_file`, `notes_file`, `title`, plus `apk_chunks` pour les fragments. Dans les deux formats, `apk_file` vaut `release-upload/SousTitresVoix-1.1.1.apk` et l’empreinte globale porte sur l’APK signé entier.

Les métadonnées sont bornées à 64 Kio. Le transport fragmenté comporte **1 à 512 parties**, numérotées sans trou à partir de `0000`, de **1 048 576 octets** chacune sauf la dernière, de 1 à 1 048 576 octets. Taille totale maximale : **512 Mio**. Les SHA-256 sont minuscules, sur 64 caractères ; `apk_chunks` conserve l’ordre. Le mélange des formats, les fichiers supplémentaires et les liens symboliques sont refusés. Le réassemblage a lieu hors checkout après contrôle de chaque partie, puis de l’empreinte globale.

Les deux formats passent les mêmes contrôles de provenance, certificat durable, manifeste, alignement et contenu comparé à l’APK CI validé. Les seuls fichiers publics destinés à l’installation sont l’APK et `SHA256SUMS` ; les fragments ne s’installent pas. Après publication, télécharger indépendamment les fichiers publics et revérifier taille, empreinte, identité et certificat avant de supprimer la branche de transport et d’annoncer la livraison.

Compléter les [notes 1.1.1](docs/releases/1.1.1.md#validation-et-publication) avec les résultats observés et les liens propres à cette version. Les suites validées n’ont pas à être répétées pour ajouter les identifiants attribués après publication ; la provenance et l’égalité de contenu doivent néanmoins rester démontrées.

## Signaler un problème

Fournir version/code, Android, appareil, moteur/voix, modèle/SHA si GGUF, mode demandé, réglages, format/protocole, étapes et résultat attendu/observé. Joindre si possible un petit exemple synthétique et un rapport expurgé. Un arrêt Android de motif 6/ANR ne prouve pas un OOM ni la responsabilité d’un pilote ; conserver la cause indéterminée si les données ne permettent pas de conclure.

Le dépôt ne définit pas de licence générale de redistribution du code propre au projet. La réalisation par IA et les licences tierces ne remplacent pas une telle licence.
