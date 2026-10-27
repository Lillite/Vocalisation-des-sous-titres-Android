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

## Signaler un problème

Indiquer la version de l’application, la version Android, le modèle du téléphone, le moteur et la voix utilisés, le format de la vidéo et des sous-titres, les réglages concernés et les étapes de reproduction. Décrire le résultat attendu et le résultat observé. Un diagnostic ou un petit exemple synthétique peut aider ; ne pas joindre de vidéo personnelle ni d’information privée.

Le dépôt ne définit pas actuellement de licence générale de redistribution. La présence publique du code et la mention de réalisation par IA ne remplacent pas une licence.
