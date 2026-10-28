# Historique des branches et des publications

[Accueil](../README.md) · [Documentation](README.md) · [Changements](../CHANGELOG.md)

## Branche de référence

**`main` rassemble le travail terminé et vérifié.** La **0.5.2 — Google et traduction pendant la lecture** est décrite dans ses [notes](releases/0.5.2.md). Le [tag v0.5.2](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/tree/v0.5.2), les fichiers de sa release et les [exécutions GitHub Actions](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/workflows/android.yml) fournissent les références vérifiables ; aucun numéro de commit ou de run antérieur ne sert de preuve à ces ajouts.

### Publications précédentes

La version **0.5.1 — réglages stables et traduction résiliente**, publiée le **27 octobre 2026**, provient du commit [`d5d5b63ea37ca4be423f375e8e7a75e870d154e6`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/d5d5b63ea37ca4be423f375e8e7a75e870d154e6), intégré par la [PR #16](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/pull/16).

Le [run préalable 40556708779](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40556708779) a réussi **524 tests** avant la fusion. Le [run après fusion 40560530124](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40560530124) a reconstruit la release et vérifié le même contenu applicatif sans rejouer les suites. Le [run de publication 40580117622](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40580117622) a publié la release `425961994` et l’asset APK `682813699` après ses contrôles. Le tag `v0.5.1` reste attaché aux sources exactes de cet APK ; une maintenance documentaire ultérieure ne le déplace pas.

La version **0.5.0 — lecture intégrale et traduction progressive**, publiée le **26 octobre 2026**, est issue du commit [`3740356420fb09eadabe23da207fa554515da101`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/tree/3740356420fb09eadabe23da207fa554515da101). Le tag [`v0.5.0`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.5.0) désigne exactement ce commit, validé dans `main` avant la publication. L’APK public a été téléchargé et vérifié à nouveau ; une mise à jour documentaire ultérieure ne déplace pas ce tag.

La version **0.4.0 — édition Traduction**, publiée le **24 octobre 2026**, est conservée comme version antérieure. Elle réunit les étapes 1 à 6 et la traduction de l’étape 7. Son tag `v0.4.0` désigne exactement [`32864ba732d834e71d87b887ae63f0d7bc09eb8c`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/tree/32864ba732d834e71d87b887ae63f0d7bc09eb8c), source de cet APK. La 0.5.0 conserve son identité d’application et son certificat durable ; les fichiers des versions antérieures ne sont pas remplacés.

La [PR #13](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/pull/13) a intégré les nouvelles fonctions ; la [PR #14](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/pull/14) a corrigé la reconstruction réentrante du plein écran et le cycle de vie du dialogue de reprise. Le [run final 40402709269](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40402709269) a réussi **1 016 exécutions JUnit de 508 méthodes distinctes** sur le commit `3740356`. Les échecs intermédiaires et leurs corrections restent dans [TESTING.md](../TESTING.md).

Le [run de publication 40407440389](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40407440389) a ensuite créé la release stable `424807308` et son asset APK `679580568`. La publication et le tag sont liés aux sources `3740356`, tandis que le commit d’envoi `589e0605ff5e217a8d821bf7602ac5a300406e57` contient uniquement les trois fichiers de livraison autorisés.

Les demandes de fusion [#3 à #8](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/pulls?q=is%3Apr+is%3Amerged) des étapes précédentes ont été intégrées avec la consolidation de la traduction, [#9](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/pull/9). Le correctif du transport de publication et les outils de synchronisation ont ensuite été intégrés par [#10](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/pull/10). Les historiques ont été conservés par fusion, sans recopier aveuglément des implémentations anciennes incompatibles.

Pour la 0.4.0, la [validation complète du commit distribué](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40053284969) a réussi avant la [publication vérifiée](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40054994161). Les résultats, corrections intermédiaires et limites figurent dans [TESTING.md](../TESTING.md).

## Consolidation de la 0.3.2 — historique du 22 octobre

La consolidation précédente partait du commit [`311d99052d287bb2227e85255740de201234e770`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/311d99052d287bb2227e85255740de201234e770), qui contient la version 0.3.2. Les sources applicatives restaient inchangées pendant cette opération documentaire. Elle actualisait la présentation et l’organisation des vérifications et corrigeait la fermeture des dialogues dans un test Android, sans supprimer de test ni modifier ses assertions.

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

## Archivage après publication de la 0.4.0

Après vérification de l’APK publié, le workflow a vérifié l’ascendance de chaque branche admissible, créé un tag d’archive sur son dernier commit, relu ce tag, puis supprimé la branche correspondante. **Les 14 branches ci-dessous sont archivées : leurs commits restent consultables.** Les six branches `backup/` ont été conservées comme points de retour ; elles n’ont pas été remplacées par une ancienne version de `main`.

| Ancienne branche | Tag d’archive | Dernier commit préservé |
|---|---|---|
| `cinema-0.2` | `archive/20261024/cinema-0.2` | [`32ac63d`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/32ac63d7ac226a29f09bc728ad982721f120b333) |
| `cinema-0.3` | `archive/20261024/cinema-0.3` | [`0f9b275`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/0f9b2759cff849ffb76c829510788c12965dde32) |
| `cinema-0.3.1` | `archive/20261024/cinema-0.3.1` | [`311d990`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/311d99052d287bb2227e85255740de201234e770) |
| `cinema-voix-v02` | `archive/20261024/cinema-voix-v02` | [`59ccd4d`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/59ccd4d48a363308cfe6f5f770bf146ca8638869) |
| `consolidation-main-0.3.2` | `archive/20261024/consolidation-main-0.3.2` | [`43966b6`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/43966b63c35344f245aa79fcf8c9250913cd20fe) |
| `fix/release-notes-line-endings` | `archive/20261024/fix/release-notes-line-endings` | [`0420302`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/042030246cebd82987593156ab1c7996bb61bd59) |
| `validation-cinema-0.3` | `archive/20261024/validation-cinema-0.3` | [`dc66631`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/dc666313792db6d0b49f80d53224e8e52079b151) |
| `work/etape-1-modularisation-20261022` | `archive/20261024/work/etape-1-modularisation-20261022` | [`a093e79`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/a093e791b1413aefef6add027225b88c20657c6c) |
| `work/etape-2-capacites-20261022` | `archive/20261024/work/etape-2-capacites-20261022` | [`0319dcb`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/0319dcb9b0cfd71be4231849306f0b248e1dbe02) |
| `work/etape-3-commandes-image-20261023` | `archive/20261024/work/etape-3-commandes-image-20261023` | [`c96b687`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/c96b687f91b2c9f7fadfc44383c8d346047cd30e) |
| `work/etape-4-interface-image-20261023` | `archive/20261024/work/etape-4-interface-image-20261023` | [`8feafda`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/8feafda50a272cbca9972cf1c948dce5afd269e7) |
| `work/etape-5-commandes-audio-20261023` | `archive/20261024/work/etape-5-commandes-audio-20261023` | [`647de93`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/647de93e99ef778cfa3199f3c225e1fc0b759baf) |
| `work/etape-6-audio-expert-20261023` | `archive/20261024/work/etape-6-audio-expert-20261023` | [`1942ba4`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/1942ba48ae3f9324b3c7d5b03ab8c9bf11463e09) |
| `work/traduction-francaise-20261024` | `archive/20261024/work/traduction-francaise-20261024` | [`d3a74c1`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/d3a74c15acdab24107c028fa02a816cc2975fdc1) |

À l’issue de cette publication, les sept branches restantes étaient `main` et les sauvegardes ci-dessous. La branche temporaire `release-upload/v0.4.0` a été supprimée après son utilisation ; l’APK reste attaché à la release. Une branche documentaire créée ensuite est temporaire et son rangement doit attendre sa fusion et la validation de son commit dans `main`.

| Sauvegarde préservée | Commit |
|---|---|
| `backup/avant-modularisation-20261022-32e9886` | `32e988654fc44faa80e1fb69c98cf9ca29e9f5b0` |
| `backup/etape-1-validee-20261022-a093e79` | `a093e791b1413aefef6add027225b88c20657c6c` |
| `backup/etape-2-validee-20261022-0319dcb` | `0319dcb9b0cfd71be4231849306f0b248e1dbe02` |
| `backup/etape-3-validee-20261023-c96b687` | `c96b687f91b2c9f7fadfc44383c8d346047cd30e` |
| `backup/etape-4-validee-20261023-8feafda` | `8feafda50a272cbca9972cf1c948dce5afd269e7` |
| `backup/etape-5-validee-20261023-647de93` | `647de93e99ef778cfa3199f3c225e1fc0b759baf` |

## Publications et sources exactes

Les APK antérieurs sont conservés, sans recompilation ni modification de leur contenu. La 0.4.0 avait ajouté une édition signée avec son identité d’installation séparée ; les 0.5.0 et 0.5.1 en conservent l’identité et le certificat. Les tags canoniques désignent les commits des sources correspondant aux APK.

| Version interne de l’APK | Tag canonique | Ancien tag | Commit source | Statut |
| --- | --- | --- | --- | --- |
| `0.1.0-test` | [`v0.1.0-test`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.1.0-test) | `Tes` | `8028f5d` | Préversion historique |
| `0.2.0-cinema-test` | [`v0.2.0-cinema-test`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.2.0-cinema-test) | `Test2` | `32ac63d` | Préversion historique |
| `0.3.1-cinema-test` | [`v0.3.1-cinema-test`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.3.1-cinema-test) | `Teste3` | `b0f32d3` | Préversion historique |
| `0.3.2` | [`v0.3.2`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.3.2) | `Test4` | `311d990` | Ancienne stable, conservée |
| `0.4.0` | [`v0.4.0`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.4.0) | Aucun | `32864ba` | Ancienne édition Traduction, conservée |
| `0.5.0` | [`v0.5.0`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.5.0) | Aucun | `3740356` | Lecture intégrale et traduction progressive, conservée |
| `0.5.1` | [`v0.5.1`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.5.1) | Aucun | `d5d5b63` | Correctifs des réglages et de DeepL, conservée |
| `0.5.2` | [`v0.5.2`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.5.2) | Aucun | [Sources du tag](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/tree/v0.5.2) | Google en ligne/local et traduction pendant la lecture |

Auparavant, les quatre anciens tags pointaient tous vers `8028f5d`. Les archives automatiques de sources des publications 0.2, 0.3.1 et 0.3.2 étaient donc incorrectes. La réorganisation crée les nouveaux tags sur les commits vérifiés, rattache les publications existantes à ces tags, puis retire les anciens tags après vérification. Les identifiants des publications et de leurs APK sont conservés.

Les liens contenant les anciens noms de tags doivent être remplacés par les liens canoniques ci-dessus. Les commits sources et les informations de correspondance restent consultables dans ce document et dans le manifeste de publications.

### Notes de version

- [0.5.2 — Google et traduction pendant la lecture](releases/0.5.2.md)
- [0.5.1 — réglages stables et traduction résiliente](releases/0.5.1.md)
- [0.5.0 — lecture intégrale et traduction progressive](releases/0.5.0.md)
- [0.4.0 — édition Traduction](releases/0.4.0.md)
- [0.3.2 — Version stable](releases/0.3.2.md)
- [0.3.1 — Cinéma et cadrage](releases/0.3.1-cinema-test.md)
- [0.2.0 — Cinéma](releases/0.2.0-cinema-test.md)
- [0.1.0 — Première préversion](releases/0.1.0-test.md)

## Maintenir une présentation cohérente

Les métadonnées des sept publications sont définies dans [`.github/release-history.json`](../.github/release-history.json), avec leurs notes dans `docs/releases/`. Le [workflow de synchronisation](../.github/workflows/releases.yml) vérifie les fichiers et les références avant d’actualiser les publications existantes. Il ne compile pas d’APK et ne remplace pas la procédure de livraison d’une nouvelle version.

La synchronisation est limitée à ce dépôt et aux publications décrites. Elle contrôle les empreintes téléchargées, les versions déclarées dans les sources et les tags attendus. Un tag canonique pointant vers un autre commit entraîne un arrêt : le script ne force pas sa réécriture.

Pour toute nouvelle version, suivre [CONTRIBUTING.md](../CONTRIBUTING.md) et [TESTING.md](../TESTING.md), puis choisir un tag correspondant à la version réelle sur le commit exact de ses sources.
