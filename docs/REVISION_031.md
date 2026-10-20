# Cinéma 0.3.1 — corrections de livraison

Cette édition reprend le code 0.3 existant au commit `0f9b2759cff849ffb76c829510788c12965dde32`. L’application porte le même identifiant expérimental `fr.soustitresvoix.player.cinema3`, avec versionCode 4. L’édition Cinéma 0.2 peut rester installée à côté.

## Adaptatif

Le budget tient compte des répliques déjà en attente, des segments restants, des apparitions futures et des échéances de chaque occurrence. Deux « Oui » simultanés peuvent conserver leurs identités et durées distinctes. Une phrase indépendante après un vrai silence n’accélère pas inutilement la phrase actuelle. Les paroles restent déclenchées par les sous-titres effectivement apparus : aucun dialogue futur n’est lu par anticipation.

La lecture directe peut accélérer même sans fichier préparé. Les WAV préparés apportent une durée mesurée ; leur vitesse peut augmenter en cours de lecture si la file se densifie, sans dépasser le plafond choisi. Le réglage affiché n’est changé qu’après acceptation de la vitesse par le lecteur audio. Le moteur externe peut refuser un réglage ; cet échec reste explicite.

La ponctuation originale est conservée, notamment les guillemets français avec espace, les points d’abréviation, les nombres décimaux et les signes de fin de phrase des alphabets couverts. Les paragraphes et les frontières entre locuteurs sont maintenus, y compris lors de l’activation de la voix ou du passage au mode continu. Le refus d’un aperçu trop long ne consomme pas les répliques en attente. Le moteur vocal reste responsable de la prosodie. L’accélération du WAV comprime les pauses avec le reste de l’audio ; aucun algorithme ne supprime les silences PCM.

## Image

Les trois modes distinguent proportions originales, étirement et recadrage. L’activation d’un mode « image entière » remet le zoom à 100 %, annule les déplacements et annule les marges précédemment retirées. Une nouvelle suppression de marges exige ensuite une nouvelle validation. La protection et le cadrage sont rétablis par vidéo ; une vidéo nouvelle n’hérite pas d’un recadrage choisi pour une autre œuvre.

Le diagnostic indique les dimensions codées, les dimensions déclarées, le rapport des pixels, le rapport d’affichage, la surface disponible et le nom du cadrage. La démonstration synthétique contient quatre coins colorés, une grille et un cercle pour contrôler le contenu réellement rendu.

L’analyse de sept images reste une proposition prudente et annulable. Elle ne garantit pas l’absence d’un sous-titre incrusté dans une marge entre les images échantillonnées. Les dialogues d’analyse sont fermés à la destruction de l’activité. La garde HDR suit aussi les changements de format vidéo pendant la lecture. La minuterie des commandes tient compte du délai recommandé par les services d’accessibilité Android.

Le retour à la bibliothèque rétablit l’image entière. La reconstruction du plein écran ferme ses anciens dialogues et ignore leurs anciennes commandes. Le redimensionnement Lanczos facultatif utilise la surface réellement affichée, avec une borne de 3 840 pixels sur son grand côté. Il est désactivé pour les contenus HDR et lors de la comparaison avec l’original. C’est un rééchantillonnage classique ; son intérêt et son coût doivent être comparés sur l’appareil.

## Préparation et cache

L’indexation vérifie l’annulation lors des lectures et avant de publier son résultat, y compris à 100 %. Les extracteurs inutilisés sont libérés. Un paquet textuel illisible, un horaire invalide ou un dépassement de limite signale un index incomplet. Une préparation de plus de 100 000 segments distincts est refusée explicitement. Le lecteur ne déclare pas qu’une préparation tronquée couvre toute la vidéo.

Un cache corrompu conduit à la lecture directe de la même réplique. Un ancien callback d’un lecteur de fichier déjà libéré ne peut pas terminer cette voix de secours. La limite d’espace reste applicable : préparer toute la vidéo ne garantit pas de conserver tous ses WAV si le cache est trop petit ou si Android libère cet espace.

Les réglages effectivement appliqués au moteur sont conservés ensemble. Une modification du débit, de la voix, des filtres ou de la révision du modèle annule la préparation précédente avant de changer les préférences. La fin d’une recherche annulée ne bloque plus le nouveau lot. Une préparation n’est terminée qu’après validation et publication du WAV. L’espace de cache inclut aussi le moteur Android par défaut et les versions déclarées des moteurs installés ; cette invalidation prudente couvre une mise à jour d’un moteur de secours, sans prétendre connaître le modèle interne qu’un moteur externe ne déclare pas.

L’index complet correspond à la source de la piste sélectionnée : une piste interne n’utilise pas par erreur le fichier de sous-titres externe joint à la vidéo. Une préparation explicitement demandée reste possible avec la vocalisation désactivée ; l’anticipation automatique, elle, suit son activation.

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

Une passe intermédiaire a exécuté 48 tests Android : 37 réussis et 11 échecs, sans test ignoré. Dix de ces échecs découlaient de l’exception du processeur audio vide ; une perte de focus après rotation restait distincte. Le harnais prévalide désormais le seul écran d’accueil immersif du système sur l’émulateur jetable et conserve aussi les sorties de diagnostic supplémentaires d’Espresso. Les assertions de rotation, de fermeture du dialogue et de lecture restent exécutées ; aucune fenêtre d’erreur ou d’ANR n’est fermée automatiquement. Seule la nouvelle passe complète permet de qualifier ces corrections.

## Limites à conserver dans toute présentation

- Ni « zéro bug », ni efficacité mesurée sur un OnePlus non connecté à cet environnement.
- Les tests TTS utilisent un service synthétique de contrôle ; ils ne jugent pas le naturel d’une vraie voix.
- Les effets SDR utilisent le pipeline graphique Media3. Aucun traitement neuronal de super-résolution, contraste local avancé ou backend vocal NPU n’a été intégré.
- L’atténuation de bande vocale PCM16 est expérimentale et affecte aussi certains instruments. Ce n’est pas une séparation des dialogues et des ambiances.
- Les décodeurs proposés sont ceux déjà disponibles sur Android. Il n’y a pas de bibliothèque FFmpeg ou LibVLC de lecture ajoutée à cet APK.
- L’APK utilise une signature de test ; une chaîne de signature de production pérenne reste à organiser pour des mises à jour durables.
