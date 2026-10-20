# Cinéma 0.3.1 — corrections de livraison

Cette édition reprend le code 0.3 existant au commit `0f9b2759cff849ffb76c829510788c12965dde32`. L’application porte le même identifiant expérimental `fr.soustitresvoix.player.cinema3`, avec versionCode 4. L’édition Cinéma 0.2 peut rester installée à côté.

## Adaptatif

Le budget tient compte des répliques déjà en attente, des segments restants, des apparitions futures et des échéances de chaque occurrence. Deux « Oui » simultanés peuvent conserver leurs identités et durées distinctes. Une phrase indépendante après un vrai silence n’accélère pas inutilement la phrase actuelle. Les paroles restent déclenchées par les sous-titres effectivement apparus : aucun dialogue futur n’est lu par anticipation.

La lecture directe peut accélérer même sans fichier préparé. Les WAV préparés apportent une durée mesurée ; leur vitesse peut augmenter en cours de lecture si la file se densifie, sans dépasser le plafond choisi. Le réglage affiché n’est changé qu’après acceptation de la vitesse par le lecteur audio. Le moteur externe peut refuser un réglage ; cet échec reste explicite.

La ponctuation originale est conservée, notamment les guillemets français avec espace, les points d’abréviation, les nombres décimaux et les signes de fin de phrase des alphabets couverts. Les paragraphes et les frontières entre locuteurs sont maintenus, y compris lors de l’activation de la voix ou du passage au mode continu. Le refus d’un aperçu trop long ne consomme pas les répliques en attente. Le moteur vocal reste responsable de la prosodie. L’accélération du WAV comprime les pauses avec le reste de l’audio ; aucun algorithme ne supprime les silences PCM.

## Image

Les trois modes distinguent proportions originales, étirement et recadrage. L’activation d’un mode « image entière » remet le zoom à 100 %, annule les déplacements et annule les marges précédemment retirées. Une nouvelle suppression de marges exige ensuite une nouvelle validation. La protection et le cadrage sont rétablis par vidéo ; une vidéo nouvelle n’hérite pas d’un recadrage choisi pour une autre œuvre.

La passe `3bedd8cd` a montré que le réglage de la vue ne suffisait pas : dans le pipeline d’effets Media3 1.11.1, `FinalShaderProgramWrapper` termine son rendu par une présentation `LAYOUT_SCALE_TO_FIT`. La capture conservait donc une image centrale avec des bandes noires malgré `RESIZE_MODE_FILL`. Le correctif ajoute une `Presentation` explicite en fin de chaîne, dimensionnée d’après la surface et bornée à 3 840 pixels sur son grand côté : `LAYOUT_STRETCH_TO_FIT` pour l’image entière étirée, `LAYOUT_SCALE_TO_FIT_WITH_CROP` pour le recadrage volontaire. En mode proportions originales ou dans la bibliothèque, cette présentation supplémentaire est retirée. La protection contre le zoom et le recadrage accidentels reste appliquée par la vue.

La comparaison avec l’original retire marges, contraste, luminosité et Lanczos, mais conserve le cadrage choisi. La géométrie finale est distincte de ces corrections facultatives ; l’exclusion HDR de ces corrections ne constitue pas une qualification de toute la chaîne HDR. Le test des quatre coins exige leur présence aux extrémités de la surface, pas seulement quelque part dans une image réduite. Son résultat se lit dans la CI du commit livré.

Le diagnostic sépare les métadonnées de la source et la sortie traitée. Dimensions, rapport des pixels et rotation proviennent de `videoFormat` pour calculer les proportions déclarées ; `videoSize` est affiché sous « Sortie du traitement vidéo », car une `Presentation` peut déjà en avoir changé les dimensions. La surface disponible, le zoom et le cadrage restent indiqués. La démonstration synthétique contient quatre coins colorés, une grille et un cercle pour contrôler le contenu réellement rendu.

Sources primaires : [FinalShaderProgramWrapper, version 1.11.1](https://github.com/androidx/media/blob/1.11.1/libraries/effect/src/main/java/androidx/media3/effect/FinalShaderProgramWrapper.java) et [discussion Media3 2842](https://github.com/androidx/media/issues/2842). La discussion décrit le contournement par `Presentation` et les interruptions possibles lors de sa réapplication ; elle ne prouve pas le succès de notre correctif sur le téléphone.

L’analyse de sept images reste une proposition prudente et annulable. Elle ne garantit pas l’absence d’un sous-titre incrusté dans une marge entre les images échantillonnées. Les dialogues d’analyse sont fermés à la destruction de l’activité. La garde HDR suit aussi les changements de format vidéo pendant la lecture. La minuterie des commandes tient compte du délai recommandé par les services d’accessibilité Android.

Le retour à la bibliothèque rétablit l’image entière. La reconstruction du plein écran ferme ses anciens dialogues et ignore leurs anciennes commandes. Le redimensionnement Lanczos facultatif utilise la surface réellement affichée, avec une borne de 3 840 pixels sur son grand côté. Il est désactivé pour les contenus HDR et lors de la comparaison avec l’original. C’est un rééchantillonnage classique ; son intérêt et son coût doivent être comparés sur l’appareil.

Les modifications de traitements et de taille sont regroupées sur 32 ms. `VisualTools` construit un `RenderPlan` à partir des valeurs finales ; si ce plan est identique au plan appliqué, aucune nouvelle liste d’effets n’est envoyée au lecteur. Le rappel différé est remplacé par toute modification plus récente et annulé à la fermeture. La réinitialisation d’un média écarte le rappel précédent avant de planifier son nouvel état. Ce regroupement réduit les reconstructions inutiles ; il ne remplace pas le contrôle de progression réelle après un changement de chaîne.

Le [rendu vidéo dédié](../app/src/main/java/fr/soustitresvoix/player/CinemaVideoRenderer.kt) conserve le graphe standard et les paramètres par défaut de Media3. Il reçoit l’interface publique `VideoSink` par le point d’extension protégé `changeVideoSinkInputStream`, sans référencer la classe interne `PlaybackVideoGraphWrapper`. Lors d’une nouvelle liste d’effets, il vide la sortie en attente avec `sink.flush(true)`, autorise la première image avant la reprise par `allowReleaseFirstFrameBeforeStarted()`, puis transmet la liste à Media3 sur le thread du renderer. Une liste identique est ignorée. Cette opération ne déclenche ni déplacement, ni arrêt, ni lecture automatique ; elle peut entraîner une brève reconstruction avec changement de disponibilité du lecteur. Aucun cache permanent ni rejeu garanti de l’image exacte déjà affichée n’est ajouté : l’image de remplacement vient du décodeur. En fin de flux, sans image restante, la comparaison visuelle immédiate n’est pas garantie avant une reprise ou un déplacement.

Le test `gpuEffectsCanBeAppliedAndBypassedOnSdrVideo` exige une progression réelle avec les effets, puis un changement mesurable des pixels de la mire pendant la pause lors du bypass et du rétablissement des corrections. Il contrôle que la position et l’intention de lecture restent inchangées pendant ces opérations, puis que la vidéo avance après reprise explicite. Les cibles portrait et paysage et leur plafond sont aussi vérifiés. Ces assertions décrivent le critère de validation ; leur résultat appartient à la CI du commit livré.

## Préparation et cache

L’indexation vérifie l’annulation lors des lectures et avant de publier son résultat, y compris à 100 %. Les extracteurs inutilisés sont libérés. Un paquet textuel illisible, un horaire invalide ou un dépassement de limite signale un index incomplet. Une préparation de plus de 100 000 segments distincts est refusée explicitement. Le lecteur ne déclare pas qu’une préparation tronquée couvre toute la vidéo.

Un cache corrompu conduit à la lecture directe de la même réplique. Un ancien callback d’un lecteur de fichier déjà libéré ne peut pas terminer cette voix de secours. La limite d’espace reste applicable : préparer toute la vidéo ne garantit pas de conserver tous ses WAV si le cache est trop petit ou si Android libère cet espace.

Les réglages effectivement appliqués au moteur sont conservés ensemble. Une modification du débit, de la voix, des filtres ou de la révision du modèle annule la préparation précédente avant de changer les préférences. La fin d’une recherche annulée ne bloque plus le nouveau lot. Une préparation n’est terminée qu’après validation et publication du WAV. L’espace de cache inclut aussi le moteur Android par défaut et les versions déclarées des moteurs installés ; cette invalidation prudente couvre une mise à jour d’un moteur de secours, sans prétendre connaître le modèle interne qu’un moteur externe ne déclare pas.

L’index complet correspond à la source de la piste sélectionnée : une piste interne n’utilise pas par erreur le fichier de sous-titres externe joint à la vidéo. Une préparation explicitement demandée reste possible avec la vocalisation désactivée ; l’anticipation automatique, elle, suit son activation.

La passe du commit `3bedd8cd8bb92999bf99f0d52472ffd0e6db1163` a révélé une autre distinction d’identifiants. Avec un fichier de sous-titres joint, Media3 1.11.1 préfixe les identifiants de format de chaque source fusionnée : la piste externe devient `1:external-subtitle` et les pistes du conteneur portent le préfixe extérieur `0:`. L’index issu du démultiplexage conserve les identifiants d’origine. La sélection externe utilise désormais l’identifiant fusionné exact ; `subtitleIndexId` rétablit l’identité d’index en retirant uniquement cette couche connue, seulement lorsqu’un sous-titre joint est effectivement présent. Il ne supprime pas arbitrairement tous les deux-points d’un identifiant de conteneur. Cette correction relie sélection, anticipation, indexation complète et échéances aux mêmes occurrences. Sa validation se lit dans la CI du commit livré ; les quatre scénarios précédemment arrêtés avant d’obtenir leur piste externe ne l’attestaient pas.

Source du comportement Media3 : [MergingMediaPeriod, version 1.11.1](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/MergingMediaPeriod.java).

Un état thermique Android sévère annule également l’indexation, pas seulement la synthèse anticipée. Une préparation complète annulée doit être relancée. L’anticipation normale peut reprendre après retour à un état compatible.

## Mesures et traitement audio

Le banc de lecture propose deux minutes et trente minutes à partir de la position courante. La durée cible suit la progression de la vidéo ; le temps réel inclut les attentes. Les résultats présentent les latences p50, p95, p99 et maximales, les attentes vocales, les accès au cache et les images abandonnées rapportées par le décodeur. Le temps CPU est celui du lecteur : il n’inclut pas le processus d’un moteur vocal externe.

L’empreinte de comparaison inclut le cadrage réellement affiché, la taille de la surface, son zoom et ses déplacements, ainsi que l’affichage et la taille des sous-titres. Elle distingue donc une prévisualisation en proportions originales d’un plein écran ou de deux zooms différents, même lorsque certaines préférences enregistrées restent identiques.

Un changement de fichier, de position, de réglage ou de disposition interrompt la mesure avec sa raison. Un arrêt interne du lecteur, notamment pour des sous-titres en images non vocalisables, clôture également la mesure. Si la durée cible coïncide avec la fin du fichier, la voix s’arrête avec l’extrait mesuré. Le rapport reste consultable après recréation de l’activité. Le retour au début de l’extrait n’entraîne pas de lecture automatique. Les identités du fichier et des réglages permettent de contrôler la comparaison, sans assimiler une même taille de cache à un contenu identique. Les mesures restent locales et l’envoi des diagnostics de lecture Media3 à la plateforme est désactivé.

L’atténuation de bande vocale conserve sa sortie progressive. Une fois inactive, le PCM est recopié exactement, sans exécuter le filtre échantillon par échantillon. Le changement de format audio est appliqué au vidage approprié du processeur, pour ne pas utiliser les paramètres du format suivant sur les derniers buffers de l’ancien.

La chaîne Media3 peut interroger ce processeur avec sa sentinelle audio vide avant la première trame. Cette entrée, ainsi qu’un fragment ne contenant aucune trame complète, est laissée intacte. Une régression exécutée avec le véritable `AudioProcessingPipeline` couvre ces appels : recopier la sentinelle sur elle-même provoquait une exception et arrêtait la vidéo.

## Catalogue

41 notices documentaires distinguent moteurs, variantes, famille Sherpa, passerelles, moteur constructeur, sources, références anciennes, offres éditeur, démonstration et modèles. Il ne s’agit pas de 41 moteurs indépendants testés. Chaque notice présente une instruction d’installation adaptée à sa catégorie et ses sources consultées le 20 octobre 2026. Le lecteur sélectionne les voix déclarées locales par les moteurs ; une passerelle dépendant d’Internet peut donc être inutilisable ici.

## Validation reproductible

Une première passe locale avec Kotlin 2.2.21 a réussi 54 scénarios initiaux, 31 Cinéma et 55 de révision, soit 140 scénarios distincts, ainsi que 120 000 transitions pseudo-aléatoires avec les graines 42, 2026 et 7351. Ce résultat précède les derniers correctifs et ne les certifie pas. La suite enrichie contient 60 scénarios de révision et sept scénarios de banc de lecture, soit 152 scénarios distincts. Ses résultats doivent être vérifiés dans l’exécution liée au commit livré. Les journaux locaux sous `build/checks/` ne remplacent pas la compilation Android.

Le workflow exécute séparément les tests Gradle, Lint, la compilation des deux APK, les contrôles du manifeste et de la signature, puis les tests instrumentés. Il utilise un émulateur AOSP API 36, conserve les rapports XML, les captures et les diagnostics, et refuse une exécution vide ou des tests ignorés. La capture `stretch-four-corners.png` accompagne un test des pixels de l’image composée après un ancien zoom et recadrage.

Les résultats Android doivent être lus dans [l’exécution associée au commit livré](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/workflows/cinema3.yml?query=branch%3Acinema-0.3.1) ; ce document ne les préjuge pas. Les preuves comprennent `source-commit.txt`, `build-console.txt`, `android-test-summary.json`, `signature.txt` et `apk-sha256.txt`. L’ancienne exécution 0.3 avait échoué avec une fenêtre ANR de SystemUI. Le premier candidat 0.3.1 avait ensuite été arrêté avant les tests par une condition de démarrage incompatible avec l’option sans animation ; elle accepte maintenant la propriété d’animation absente, tout en exigeant Android démarré, un service de paquets réactif et une fenêtre focalisée sans ANR. Aucun test n’a été supprimé ou ignoré pour contourner ces échecs.

Une passe intermédiaire a exécuté 48 tests Android : 37 réussis et 11 échecs, sans test ignoré. Dix de ces échecs découlaient de l’exception du processeur audio vide ; une perte de focus après rotation restait distincte. Le harnais prévalide désormais le seul écran d’accueil immersif du système sur l’émulateur jetable et conserve aussi les sorties de diagnostic supplémentaires d’Espresso. Les assertions de rotation, de fermeture du dialogue et de lecture restent exécutées ; aucune fenêtre d’erreur ou d’ANR n’est fermée automatiquement. La qualification de ces corrections exige les preuves de la passe complète du commit livré.

### Constats de la passe `3bedd8cd`

Les preuves du commit `3bedd8cd8bb92999bf99f0d52472ffd0e6db1163` indiquent **52 tests Android exécutés, 46 réussis, six échecs, zéro entrée `errors` et aucun test ignoré**. Les étapes tests du noyau, Lint, compilation des deux APK, manifeste et signature avaient abouti ; ce succès partiel ne valide pas les six comportements en échec.

| Échecs observés | Cause ou constat retenu pour la correction |
|---|---|
| `manualPreparationCompletesWhileNarrationIsDisabled`, `anActualOversizedSubtitleStopsTheBenchmarkWithItsErrorReason`, `completePreparationIndexesTheSelectedInternalOrExternalTrack`, `benchmarkFinishingAtFileEndStopsAnOngoingCachedReply` | La piste externe n’était pas reconnue sous son identifiant préfixé par la fusion Media3. Ces quatre tests s’arrêtaient dans leurs conditions de disponibilité ou de sélection ; ils n’avaient pas encore validé le comportement ensuite attendu. |
| `stretchKeepsAllFourRenderedCornersAfterPreviousZoomAndCrop` | La capture `stretch-four-corners.png` contient les quatre repères dans une image centrée, avec des bandes noires en haut et en bas. La propriété `PlayerView.resizeMode = FILL` était correcte, mais le rendu final n’étirait pas l’image sur toute la surface. |
| `gpuEffectsCanBeAppliedAndBypassedOnSdrVideo` | La vidéo ne progressait pas après les modifications rapprochées des effets. La correction regroupe les changements, évite les réapplications identiques et vide la sortie graphique avant de changer la chaîne. Le test exige la progression réelle, des pixels modifiés pendant la pause lors du bypass et du retour aux corrections, le maintien de la position et de la pause, puis une reprise explicite effective. |

Ces échecs restent des constats historiques associés à ce commit. La validation des corrections se lit dans le run complet du commit livré, avec les assertions conservées, notamment les pixels des quatre coins et la progression réelle du lecteur. Cette section ne préjuge pas du succès de ce run.

### Constats de la passe `d73562a`

Les deux APK ont compilé et les 14 méthodes JUnit du noyau ont réussi, mais Lint a signalé trois erreurs `RestrictedApi` pour les références à `PlaybackVideoGraphWrapper` dans `CinemaVideoRenderer`. Cette passe a été arrêtée avant les tests Android : elle n’atteste donc pas le rendu corrigé. Le renderer utilise désormais le `VideoSink` public fourni par `changeVideoSinkInputStream`, en conservant le même vidage et la même autorisation de première image. Aucune règle Lint n’est supprimée et aucune API interne n’est appelée. Le résultat du correctif se lit dans les preuves de la passe complète du commit livré.

### Constats de la passe `b2b61c6`

La [cinquième passe, exécution 39456732983](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/39456732983), a exécuté **52 tests Android : 51 réussis, un échec, zéro entrée `errors` et aucun test ignoré**. Les tests du noyau, Lint, les deux compilations APK, le manifeste et la signature ont réussi. La mire des quatre coins et les quatre scénarios auparavant bloqués par l’identifiant de piste externe ont également réussi.

Le seul échec, `gpuEffectsCanBeAppliedAndBypassedOnSdrVideo`, comparait une position de 1 671 ms capturée immédiatement après la demande de pause à 1 683 ms après son application asynchrone. La vérification du fonctionnement de Media3 1.11.1 confirme que l’état `playWhenReady` devient visible sur le thread d’interface avant que le thread de lecture ait arrêté l’horloge et publié sa dernière position. Le correctif concerne uniquement la synchronisation du test : attendre hors du thread d’interface une barrière FIFO `PlayerMessage` publique, puis `waitForIdleSync`, et relever ensuite la position de référence avant de changer les effets. Les égalités de position restent strictes, sans tolérance ajoutée ; aucun changement de production n’est motivé par cet écart de 12 ms. La validation de ce test corrigé se lit dans la passe complète du commit livré et n’est pas déduite de ces 51 réussites.

## Limites à conserver dans toute présentation

- Ni « zéro bug », ni efficacité mesurée sur un OnePlus non connecté à cet environnement.
- Les tests TTS utilisent un service synthétique de contrôle ; ils ne jugent pas le naturel d’une vraie voix.
- Les effets SDR utilisent le pipeline graphique Media3. Aucun traitement neuronal de super-résolution, contraste local avancé ou backend vocal NPU n’a été intégré.
- L’atténuation de bande vocale PCM16 est expérimentale et affecte aussi certains instruments. Ce n’est pas une séparation des dialogues et des ambiances.
- Les décodeurs proposés sont ceux déjà disponibles sur Android. Il n’y a pas de bibliothèque FFmpeg ou LibVLC de lecture ajoutée à cet APK.
- L’APK utilise une signature de test ; une chaîne de signature de production pérenne reste à organiser pour des mises à jour durables.
