# Sous-titres en voix

Lecteur vidéo Android qui lit les sous-titres à voix haute, avec des commandes accessibles, un plein écran cinéma et des réglages pour écouter chaque réplique à son rythme.

**Ce projet est réalisé intégralement par intelligence artificielle : la conception de l’application, l’écriture du code, les corrections, les tests automatisés et la documentation sont produits par IA à partir des demandes, des indications et des retours de son utilisateur.**

Cette mention concerne le travail propre au projet. Android, Kotlin, Media3, les moteurs vocaux et les autres composants tiers restent les créations de leurs auteurs respectifs.

**[Télécharger l’APK 0.3.2](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/download/v0.3.2/SousTitresVoix-0.3.2-stable.apk)** · [Versions publiées](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases) · [Documentation](docs/README.md) · [Signaler un problème](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/issues/new/choose)

| Repère | État du projet |
| --- | --- |
| Version de l’application | **0.3.2** |
| Android minimum | **Android 8.0 — API 26** |
| Branche de référence | **`main`**, qui rassemble le travail terminé et vérifié |
| Fonctionnement | Vidéos locales et voix Android installées ; aucune permission Internet dans le lecteur |
| Vérifications | [Compilation et régressions Android](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/workflows/android.yml) |

## Sommaire

- [Installer et commencer](#installer-et-commencer)
- [Fonctions principales](#fonctions-principales)
- [Choisir le mode vocal](#choisir-le-mode-vocal)
- [Choisir le cadrage](#choisir-le-cadrage)
- [Compatibilité et limites](#compatibilité-et-limites)
- [Confidentialité](#confidentialité)
- [Documentation et développement](#documentation-et-développement)

## Installer et commencer

1. Depuis le téléphone, [télécharger `SousTitresVoix-0.3.2-stable.apk`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/download/v0.3.2/SousTitresVoix-0.3.2-stable.apk). Ouvrir le fichier, puis autoriser son installation dans l’application utilisée pour l’ouvrir si Android le demande.
2. Ouvrir **Sous-titres en voix**, puis **Réglages de la voix et du confort**.
3. Dans **Choisir le moteur vocal de CE lecteur**, sélectionner un moteur installé, puis **Choisir une voix hors ligne** et **Écouter un essai de la voix**. L’installation initiale d’un moteur ou d’une voix peut nécessiter Internet dans l’application du fournisseur.
4. Lancer **Essai intégré : sous-titres et mire de cadrage**. Ce court exemple synthétique permet d’essayer la lecture vocale et l’affichage avant d’ouvrir un fichier personnel.
5. Ouvrir une vidéo locale, sélectionner sa piste de sous-titres textuels ou ajouter un fichier SRT, VTT, ASS/SSA. Choisir **Confort** ou **Adaptatif** pour préserver les phrases complètes.

L’édition 0.3.2 utilise l’identité `fr.soustitresvoix.player.stable`. Elle peut coexister avec les anciennes éditions de test. Les réglages et les autorisations de fichiers sont propres à chaque installation : choisir à nouveau la voix et la vidéo dans cette édition. Les vidéos d’origine ne sont pas modifiées.

### Identifier le bon téléchargement

L’[édition 0.3.2](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.3.2) est la version recommandée dans le dépôt.

- Taille publiée : **4 902 640 octets**.
- SHA-256 : `0b3d2c7c7639542115327ee2a561cd7560ce535d57dad144f14dc3cda0bbb121`.
- [Sources exactes de l’APK 0.3.2](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/tree/311d99052d287bb2227e85255740de201234e770).

Les anciennes publications de test et les correspondances entre tags, sources et APK sont répertoriées dans l’[historique des branches et des publications](docs/HISTORIQUE_BRANCHES.md).

## Fonctions principales

| Fonction | Utilisation |
| --- | --- |
| Vocalisation des sous-titres | Lecture des événements textuels distincts de la piste sélectionnée : dialogues, répliques et panneaux traduits. Les répétitions réellement présentes dans la source restent distinctes. |
| Voix Android | Choix explicite du moteur et d’une voix installée, échantillon, vitesse et volume de la voix. Le catalogue présente des ressources et des moteurs externes. |
| Confort d’écoute | Modes Confort, Adaptatif et Continu ; pause explicite, reprise et répétition de la dernière phrase. |
| Mixage | Réglage séparé de la voix et du film, baisse progressive du film pendant la parole et options audio. |
| Plein écran cinéma | Commandes de lecture, orientation, taille des sous-titres, image entière, étirement ou recadrage explicite. |
| Préparation vocale | Préparer les cinq prochaines minutes ou indexer et préparer toute la vidéo ; opération explicite, annulable et soumise aux limites du cache. |
| Image et diagnostic | Diagnostic du cadrage, aperçu avant suppression des marges encodées, filtres SDR facultatifs et mesures de lecture. |

La préservation des sous-titres est une exigence du projet : la correction d’un panneau relu en boucle doit conserver les véritables occurrences, même lorsque plusieurs répliques contiennent le même texte. Voir les [vérifications de régression](TESTING.md).

## Choisir le mode vocal

| Mode | Comportement |
| --- | --- |
| **Confort** | La vidéo attend lorsque la voix a besoin de temps pour terminer la phrase. |
| **Adaptatif** | La voix conserve la vitesse de base lorsque le budget disponible le permet, en tenant compte des segments et répliques proches ; elle accélère dans la limite réglée et conserve une attente si nécessaire. |
| **Continu** | La vidéo continue sans attendre. Une phrase trop longue peut être interrompue ; ce mode ne convient pas à l’objectif de vocalisation intégrale. |

Pour un premier essai du mode Adaptatif, les réglages proposés dans le projet sont une vitesse de base de **100 %**, un plafond de **180 %** et une tolérance de **600 ms**. Le confort réel dépend du moteur vocal et du contenu. Une phrase préparée utilise la durée mesurée du WAV ; sans cache, le lecteur travaille avec une estimation.

La préparation vocale met la lecture en pause. Le cache est borné : « préparer toute la vidéo » ne garantit pas de conserver simultanément tous les fichiers audio d’une œuvre volumineuse.

## Choisir le cadrage

Ouvrir **Plein écran cinéma**, puis **Image / zoom**.

| Mode affiché | Résultat |
| --- | --- |
| **Image entière, proportions originales** | L’image entière conserve ses proportions. Des bandes peuvent rester visibles. |
| **Tout l’écran — image entière étirée** | L’image entière occupe l’écran en changeant ses proportions. Le raccourci **Étirement intégral** active ce mode. |
| **Tout l’écran, image recadrée** | L’image remplit l’écran en coupant une partie de ses bords. |

Les modes d’image entière réinitialisent les transformations incompatibles : zoom à 100 %, déplacements annulés et anciennes suppressions de marges retirées. La mire intégrée permet de vérifier que les quatre coins colorés restent visibles ; son cercle devient une ellipse lorsque l’image est étirée.

Pour les bandes réellement encodées dans le fichier, utiliser **Diagnostic du cadrage et des bandes noires**, puis, si nécessaire, **Analyser les marges encodées — aperçu obligatoire**. Toute suppression demande une confirmation. Une scène sombre ou des sous-titres près des bords peuvent fausser l’analyse : vérifier l’aperçu avant de l’appliquer.

## Compatibilité et limites

- **Vidéos locales** : notamment MKV, MP4 et WebM ; les codecs effectivement lisibles dépendent des décodeurs du téléphone et du lecteur Media3.
- **Sous-titres textuels** : pistes intégrées et fichiers externes SRT, VTT, ASS/SSA. Les fichiers externes sont limités à 8 Mio ; un choix d’encodage existe pour les fichiers anciens.
- **Sous-titres en images** : pas d’OCR pour PGS/VobSub ni pour un texte uniquement incrusté dans l’image. Un panneau est vocalisé lorsqu’il figure dans la piste textuelle sélectionnée.
- **Fonctions absentes** : pas de traduction automatique, d’audiodescription des scènes, d’accès aux contenus DRM d’autres applications ni de lecture en arrière-plan.
- **Accélération vocale** : aucun modèle vocal neuronal, moteur GPU ou backend NPU propre au projet n’est intégré. Les possibilités d’un moteur Android externe dépendent de ce moteur.
- **Validation sur téléphone** : les tests automatisés ne remplacent pas l’essai des voix réelles, de TalkBack, du HDR et d’une séance prolongée sur l’appareil cible.

## Confidentialité

Le lecteur ne demande aucune permission Internet, microphone, caméra ou accès général au stockage. Il ouvre les fichiers choisis dans le sélecteur Android et ne téléverse pas les vidéos ni les sous-titres. Le moteur vocal est un composant externe : vérifier ses propres permissions et la disponibilité effective de sa voix hors ligne.

Les pages du catalogue s’ouvrent dans le navigateur uniquement sur demande. Les vidéos d’essai et les sous-titres utilisés par les tests sont synthétiques. Les clés privées de signature restent hors du dépôt.

## Documentation et développement

### Étapes préparatoires vers un lecteur expert

Cette branche prépare des interfaces remplaçables autour de Media3, de l’audio,
de la vidéo, de l’extraction des sous-titres et de la narration, puis un modèle
commun de capacités et d’observations. Le diagnostic distingue les réglages
demandés, la configuration transmise et les faits réellement rapportés. La troisième
étape relie les commandes existantes de contraste, de luminosité de l’image et de
comparaison avec l’original à un contrat typé : valeurs bornées, refus explicites
et demandes attachées à la génération du média. Une commande acceptée reste
distincte d’un plan envoyé au rendu et d’un résultat mesuré dans les pixels.
La quatrième étape expose ce contrat dans une interface d’image avec pas de
0,01, saisie décimale, remise à zéro ciblée et état des corrections. Chaque
paramètre d’image, de cadrage, de zoom et de luminosité de l’écran dispose d’une
description de son effet, de son utilisation et de ses limites.
La cinquième étape ajoute une frontière de commandes pour les six réglages du
son du film : niveaux, durées de baisse et de remontée, attente et filtre spectral
existant. Les commandes des panneaux, des curseurs et du geste de volume passent
par la même validation. Les demandes identiques laissent les fondus continuer ;
les paramètres disposent d’explications et de retours individuels aux valeurs
par défaut dans « Volumes rapides ».
La sixième étape ajoute la saisie numérique précise des pourcentages et des
durées, puis des traitements du film en PCM16 mono ou stéréo : égaliseur
paramétrique à cinq bandes, préampli, balance, mélange mono et limiteur de
crêtes numériques. Leur activation générale est désactivée par défaut ; les
descriptions expliquent chaque unité, plage, défaut, transition et limite.
Les observations distinguent une demande soumise, sa consommation par le
processeur et la crête numérique du dernier bloc, sans les présenter comme une
mesure acoustique ou une preuve de traitement du média courant.
La demande du 22 octobre 2026 prévoit plusieurs étapes avant toute livraison
d’application.
Le code de départ est conservé sur
[`backup/avant-modularisation-20261022-32e9886`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/tree/backup/avant-modularisation-20261022-32e9886).
L’étape 1 est également conservée au commit
[`a093e79`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/a093e791b1413aefef6add027225b88c20657c6c),
sur `backup/etape-1-validee-20261022-a093e79`. Le point de retour de l’étape 2 est
[`0319dcb`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/0319dcb9b0cfd71be4231849306f0b248e1dbe02),
sur `backup/etape-2-validee-20261022-0319dcb`. Le point de retour de l’étape 3 est
[`c96b687`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/c96b687f91b2c9f7fadfc44383c8d346047cd30e),
sur `backup/etape-3-validee-20261023-c96b687`. Le point de retour de l’étape 4 est
[`8feafda`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/8feafda50a272cbca9972cf1c948dce5afd269e7),
sur `backup/etape-4-validee-20261023-8feafda`. Le point de retour de l’étape 5 est
[`647de93`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/647de93e99ef778cfa3199f3c225e1fc0b759baf),
sur `backup/etape-5-validee-20261023-647de93`. Le travail de l’étape 6 se trouve sur
`work/etape-6-audio-expert-20261023`.

Le périmètre, les invariants et les contrôles sont détaillés dans
[Modularisation — étape 1](docs/MODULARISATION_ETAPE_1.md),
[Capacités et observations — étape 2](docs/CAPACITES_ETAPE_2.md),
[Commandes d’image — étape 3](docs/COMMANDES_IMAGE_ETAPE_3.md),
[Interface d’image — étape 4](docs/INTERFACE_IMAGE_ETAPE_4.md),
[Commandes du son du film — étape 5](docs/COMMANDES_AUDIO_ETAPE_5.md) et
[Saisie et audio avancé — étape 6](docs/AUDIO_EXPERT_ETAPE_6.md). Les règles de
vocalisation, le mixage audio et les traitements graphiques existants restent
à préserver. Les résultats détaillés de l’étape 5 sont conservés dans la
[demande nº 7](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/pull/7).
Les nouveaux réglages et traitements exigent leur propre validation sur les
sources de l’étape 6, avec les régressions précédentes et les contrôles de
signaux synthétiques ; les effectifs et résultats sont consignés dans la
demande de fusion correspondant au commit exécuté.
Ce travail reste sur une branche séparée, avec une demande de fusion en brouillon,
jusqu’à la livraison demandée ultérieurement. Le lien de téléchargement ci-dessus
désigne toujours l’édition publiée 0.3.2.

Après validation de l’étape 6, la suite proposée est un compresseur paramétrable
du film avec tests de dynamique, puis des profils et une comparaison A/B
contrôlée, puis l’extension prudente des formats et du multicanal. La
vocalisation conserve ses règles ; l’évaluation de moteurs de lecture
alternatifs reste une décision séparée.

| Besoin | Document |
| --- | --- |
| Parcourir tous les documents | [Index de la documentation](docs/README.md) |
| Vérifier le comportement sur un téléphone | [Parcours de tests manuels](docs/TESTS_TELEPHONE.md) |
| Comprendre les versions | [Journal des changements](CHANGELOG.md) |
| Compiler, tester et signer | [Procédure de vérification](TESTING.md) |
| Comprendre le code | [Architecture](docs/ARCHITECTURE.md) |
| Contribuer et terminer une modification | [Règles de contribution](CONTRIBUTING.md) et [consignes pour les agents](AGENTS.md) |
| Retrouver les anciennes branches et APK | [Historique des branches et publications](docs/HISTORIQUE_BRANCHES.md) |
| Explorer les moteurs vocaux | [Catalogue documentaire daté](docs/VOIX_CATALOGUE_2026.md) |

Le dépôt contient `app/` pour l’application et les tests Android, `core/` pour le moteur indépendant d’Android, `docs/` pour la documentation, `examples/` pour les sous-titres synthétiques et `tools/` pour les contrôles. Le workflow de validation est [`.github/workflows/android.yml`](.github/workflows/android.yml).

La compilation utilise Java 17 ; les tests Robolectric Android 36 utilisent Java 21. Les versions Gradle, Android SDK et dépendances sont précisées dans [TESTING.md](TESTING.md) et les fichiers de compilation.

**Tout travail terminé et vérifié doit être intégré à `main`.** Les branches de travail servent à préparer et contrôler les changements ; la page d’accueil, la documentation et les liens de téléchargement doivent rester cohérents avec l’état réellement livré.
