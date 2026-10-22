# Historique des branches et des publications

[Accueil](../README.md) · [Documentation](README.md) · [Changements](../CHANGELOG.md)

## Branche de référence

**`main` rassemble le travail terminé et vérifié.** La consolidation du 22 octobre 2026 part du commit [`311d99052d287bb2227e85255740de201234e770`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/311d99052d287bb2227e85255740de201234e770), qui contient la version 0.3.2. Les sources applicatives et les tests de cette version sont conservés ; la consolidation actualise la présentation, la documentation et l’organisation des vérifications.

Avant cette opération, `main` était restée au commit `8028f5d`, correspondant à la première édition. Les versions suivantes existaient sur des branches secondaires.

## Sort des branches antérieures

| Branche | Dernier commit examiné | Intégration |
| --- | --- | --- |
| `main` avant consolidation | `8028f5d` | Ancêtre de la série 0.3.2 ; historique conservé. |
| `cinema-0.2` | `32ac63d` | Déjà entièrement incluse dans la série 0.3.2. |
| `cinema-0.3` | `0f9b275` | Déjà entièrement incluse dans la série 0.3.2. |
| `cinema-0.3.1` | `311d990` | Base applicative retenue ; malgré son nom de branche, elle contient la correction 0.3.2. |
| `cinema-voix-v02` | `59ccd4d` | Variante ancienne remplacée par la série livrée ; son historique est réconcilié sans réintroduire ses anciennes classes. |
| `validation-cinema-0.3` | `dc66631` | Branche technique d’import dont les correctifs sont déjà appliqués ; historique réconcilié sans réactiver le workflow d’import. |

### Variante Cinéma 0.2

La branche `cinema-voix-v02` comportait une autre implémentation du plein écran, de l’anticipation, du cache et du service vocal de test. La version 0.3.2 utilise les composants plus récents `CinemaView`, `CueAhead`, `FullSubtitleIndexer`, `SpeechTools`, `AdaptiveTools`, `PlaybackBenchmark` et `FixtureSpeechService`.

La réconciliation conserve les commits de la variante et l’état applicatif 0.3.2. Elle ne prétend pas recopier tous les anciens fichiers : plusieurs classes et signatures d’API sont incompatibles avec l’architecture actuelle. Les scénarios de cette série ont été repris et étendus dans les suites de régression courantes.

### Branche technique de validation

Les trois commits propres à `validation-cinema-0.3` modifiaient uniquement un workflow ponctuel appliquant des correctifs encodés. La comparaison des correctifs décodés, sans exécution, a établi les correspondances suivantes :

| Commit du workflow d’import | Correctif déjà appliqué dans la série retenue |
| --- | --- |
| `686ec00` | `62a953f` — adaptation hors cache, indexation et diagnostics |
| `1f16d52` | `2766ce1` — préparation interrompue et cycle de vie des volumes |
| `dc66631` | `368787d` — outils de test et preuves visuelles |

Le workflow d’import n’est plus utile. Il n’est pas réintroduit dans la branche principale.

## Publications et sources exactes

Les quatre APK existants sont conservés, sans recompilation ni modification de leur contenu. Les titres et descriptions sont normalisés ; les tags canoniques désignent les commits des sources correspondant aux APK.

| Version interne de l’APK | Tag canonique | Ancien tag | Commit source | Statut |
| --- | --- | --- | --- | --- |
| `0.1.0-test` | [`v0.1.0-test`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.1.0-test) | `Tes` | `8028f5d` | Préversion historique |
| `0.2.0-cinema-test` | [`v0.2.0-cinema-test`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.2.0-cinema-test) | `Test2` | `32ac63d` | Préversion historique |
| `0.3.1-cinema-test` | [`v0.3.1-cinema-test`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.3.1-cinema-test) | `Teste3` | `b0f32d3` | Préversion historique |
| `0.3.2` | [`v0.3.2`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.3.2) | `Test4` | `311d990` | Version recommandée |

Auparavant, les quatre anciens tags pointaient tous vers `8028f5d`. Les archives automatiques de sources des publications 0.2, 0.3.1 et 0.3.2 étaient donc incorrectes. La réorganisation crée les nouveaux tags sur les commits vérifiés, rattache les publications existantes à ces tags, puis retire les anciens tags après vérification. Les identifiants des publications et de leurs APK sont conservés.

Les liens contenant les anciens noms de tags doivent être remplacés par les liens canoniques ci-dessus. Les commits sources et les informations de correspondance restent consultables dans ce document et dans le manifeste de publications.

### Notes de version

- [0.3.2 — Version stable](releases/0.3.2.md)
- [0.3.1 — Cinéma et cadrage](releases/0.3.1-cinema-test.md)
- [0.2.0 — Cinéma](releases/0.2.0-cinema-test.md)
- [0.1.0 — Première préversion](releases/0.1.0-test.md)

## Maintenir une présentation cohérente

Les métadonnées des quatre publications sont définies dans [`.github/release-history.json`](../.github/release-history.json), avec leurs notes dans `docs/releases/`. Le [workflow de synchronisation](../.github/workflows/releases.yml) vérifie les fichiers et les références avant d’actualiser les publications existantes. Il ne compile pas d’APK et ne remplace pas la procédure de livraison d’une nouvelle version.

La synchronisation est limitée à ce dépôt et aux publications décrites. Elle contrôle les empreintes téléchargées, les versions déclarées dans les sources et les tags attendus. Un tag canonique pointant vers un autre commit entraîne un arrêt : le script ne force pas sa réécriture.

Pour toute nouvelle version, suivre [CONTRIBUTING.md](../CONTRIBUTING.md) et [TESTING.md](../TESTING.md), puis choisir un tag correspondant à la version réelle sur le commit exact de ses sources.
