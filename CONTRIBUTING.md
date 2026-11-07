# Contribuer à Sous-titres en voix

[Accueil](README.md) · [Documentation](docs/README.md) · [Validation](TESTING.md) · [Consignes](AGENTS.md)

La branche **`main` est la référence du projet**. Chaque modification terminée et vérifiée doit y être fusionnée avant d’être annoncée comme livrée. Cette règle s’applique aussi au travail réalisé par les agents d’intelligence artificielle.

## Préparer et intégrer une modification

1. Partir de l’état à jour de `main` et lire les [consignes de développement](AGENTS.md).
2. Préparer le changement sur une branche de travail. Vérifier ce que contiennent les branches existantes avant de les réutiliser.
3. Préserver la vocalisation des événements textuels distincts : une correction de doublons ne doit pas supprimer des répliques, des panneaux ou des répétitions réelles.
4. Exécuter les contrôles applicables décrits dans [TESTING.md](TESTING.md). Avant chaque fusion, construire les APK de test dans GitHub Actions et exécuter la validation JVM, lint et les tests instrumentés sur l’APK release de test. Chaque suite complète ne passe qu’une fois ; ses résultats sont conservés. Conserver les échecs et les limites constatées.
5. Mettre à jour la documentation concernée, le [journal des changements](CHANGELOG.md) et, lorsqu’une version change, les liens de téléchargement et les repères de version.
6. Ouvrir une demande de fusion vers `main` avec le problème traité, le comportement obtenu, les vérifications réellement exécutées et les limites restantes.
7. Fusionner le travail achevé lorsque les contrôles requis réussissent. Utiliser une fusion avec commit de merge. Vérifier ensuite la construction de release sur `main`, sans rejouer les tests : elle doit prouver le même arbre et les mêmes parents que la fusion provisoire testée, puis le même contenu applicatif hors signature. Ne pas laisser une version terminée uniquement sur une branche secondaire.

Un test en échec, une protection de branche ou un conflit non résolu empêche de présenter la livraison comme terminée. Résoudre le problème ou expliquer précisément le blocage ; ne pas contourner les contrôles et ne pas annoncer une fusion qui n’a pas eu lieu.

## Préserver l’historique

- Utiliser des commits et des demandes de fusion aux titres explicites.
- Lors d’une consolidation de branches anciennes, conserver l’historique et documenter les variantes remplacées. Ne pas réintroduire une ancienne implémentation à la place de correctifs plus récents.
- Conserver les tests de non-régression. Une simplification de la présentation du dépôt ne justifie pas de retirer une fonction ou une preuve de validation.
- Ne pas déplacer un tag de version correct déjà publié. Pour corriger des tags historiques mal nommés, créer des tags canoniques sur les sources vérifiées, conserver les APK et documenter la correspondance avant de retirer les anciens noms.

## Préparer une version installable

La compilation et la publication d’un APK sont deux opérations distinctes. Après fusion, le workflow `release-build.yml` construit un APK release non signé sans relancer les suites déjà validées ; la publication exige ensuite la clé durable existante et un contrôle du fichier signé. Les artefacts de validation CI utilisent une signature éphémère ; ils ne remplacent pas automatiquement l’APK distribué avec sa clé durable.

Pour une nouvelle publication, suivre [TESTING.md](TESTING.md), conserver la même identité et la même clé pour les mises à jour, vérifier le fichier distribué, et fournir dans les notes de version le commit source, les résultats de validation, le SHA-256 et les limites connues. Utiliser un tag correspondant à la version réelle, par exemple `v0.5.2` pour une application versionnée 0.5.2, ou un suffixe explicite pour une préversion.

Les secrets, mots de passe, vidéos personnelles et clés privées ne doivent pas être versionnés. Les déclarations sur les tests doivent distinguer compilation, tests JVM, instrumentation Android et essais manuels sur un téléphone.

### Transport temporaire de l’APK déjà vérifié

Le workflow `publish-apk.yml` reçoit les données sur la branche temporaire
`release-upload/v0.5.13-memory`. Son commit doit avoir pour seul parent le commit
source validé sur `main` et ne modifier que les fichiers de transport prévus.
Le commit source reste dépourvu de `release-upload`. L’APK a déjà été signé
avec le certificat durable hors de ce transport ; aucune clé, aucun mot de
passe ni jeton n’est ajouté aux fichiers. Les métadonnées ne permettent pas
de fournir une commande ou une URL de téléchargement au workflow.

Deux formats sont acceptés par `tools/publish-verified-release.py` :

| Format | Fichiers temporaires | Métadonnées |
| --- | --- | --- |
| APK unique | `release-upload/release.json`, `release-upload/notes.md` et `release-upload/SousTitresVoix-0.5.13-memory.apk` | Les sept champs `tag`, `source_sha`, `apk_sha256`, `signer_sha256`, `apk_file`, `notes_file` et `title`. Aucun répertoire `apk-parts` n’est accepté. |
| Fragments binaires | `release-upload/release.json`, `release-upload/notes.md` et `release-upload/apk-parts/0000.part`, `0001.part`, etc. | Les mêmes sept champs, plus `apk_chunks`, liste ordonnée des SHA-256 de chaque partie. Le fichier APK complet est absent du checkout. |

Le second format permet de transmettre les mêmes octets en plusieurs petits
blobs lorsque le moyen d’envoi ne permet pas de transférer l’APK en une seule
requête. Les parties contiennent des octets binaires, sans encodage base64 dans
les fichiers. Il y a **1 à 512 parties**, numérotées à partir de `0000` sans trou,
de **1 048 576 octets** chacune ; la dernière contient **1 à 1 048 576 octets**.
La taille totale de l’APK reste limitée à **512 Mio**. Les empreintes sont
hexadécimales minuscules de 64 caractères ; `apk_chunks` conserve leur ordre de
concaténation. Les métadonnées sont bornées à **64 Kio**. Dans les deux formats,
`apk_file` reste `release-upload/SousTitresVoix-0.5.13-memory.apk` et
`apk_sha256` désigne l’empreinte de l’APK signé complet.

Le format fragmenté refuse un mélange avec l’APK complet, les parties
supplémentaires, les liens symboliques et les fichiers hors de la liste
attendue. Le réassemblage s’effectue dans un répertoire temporaire **hors du
checkout**, après vérification de la taille et de l’empreinte de chaque partie ;
l’empreinte globale est contrôlée avant les vérifications de l’APK. Les deux
formats imposent les mêmes contrôles de provenance, manifeste, certificat,
alignement et contenu applicatif par comparaison avec l’artefact CI validé.
Ils produisent les mêmes fichiers publics : l’APK installable et
`SHA256SUMS`. Les fragments ne sont pas une distribution à installer. La branche
de transport est supprimée après vérification de la publication publique.

### Préparer la documentation avant les tests

Réunir code, tests utiles et documentation dans la même PR. Le manifeste
`.github/release-history.json` conserve les identifiants et empreintes des
publications historiques. Son champ `next_release` déclare la prochaine version,
ses notes et son identité Android avant la validation : aucun identifiant de
release ni SHA-256 encore inconnu ne doit être inventé. Avant publication, la
synchronisation conserve la version recommandée existante. Après publication,
elle résout le tag déclaré, vérifie que sa source appartient à `main`, puis
contrôle les sources, les fichiers publics, leur taille et leur empreinte avant
toute modification. Une publication non déclarée reste bloquante.

Ce parcours évite une seconde campagne Android uniquement pour ajouter les
identifiants que GitHub attribue après publication. Les résultats et empreintes
propres à l’exécution figurent dans les preuves de la release et les artefacts
Actions, avec leurs limites de conservation.

## Signaler un problème

Indiquer la version de l’application, la version Android, le modèle du téléphone, le moteur et la voix utilisés, le format de la vidéo et des sous-titres, les réglages concernés et les étapes de reproduction. Décrire le résultat attendu et le résultat observé. Un diagnostic ou un petit exemple synthétique peut aider ; ne pas joindre de vidéo personnelle ni d’information privée.

Le dépôt ne définit pas actuellement de licence générale de redistribution. La présence publique du code et la mention de réalisation par IA ne remplacent pas une licence.
