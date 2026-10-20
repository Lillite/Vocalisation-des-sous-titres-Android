# Cinéma 0.3.1 — corrections de livraison

Cette édition reprend le code 0.3 existant au commit `0f9b2759cff849ffb76c829510788c12965dde32`. L’application porte le même identifiant expérimental `fr.soustitresvoix.player.cinema3`, avec versionCode 4. L’édition Cinéma 0.2 peut rester installée à côté.

## Adaptatif

Le budget tient compte des répliques déjà en attente, des segments restants, des apparitions futures et des échéances de chaque occurrence. Deux « Oui » simultanés peuvent conserver leurs identités et durées distinctes. Une phrase indépendante après un vrai silence n’accélère pas inutilement la phrase actuelle. Les paroles restent déclenchées par les sous-titres effectivement apparus : aucun dialogue futur n’est lu par anticipation.

La lecture directe peut accélérer même sans fichier préparé. Les WAV préparés apportent une durée mesurée ; leur vitesse peut augmenter en cours de lecture si la file se densifie, sans dépasser le plafond choisi. Le réglage affiché n’est changé qu’après acceptation de la vitesse par le lecteur audio. Le moteur externe peut refuser un réglage ; cet échec reste explicite.

La ponctuation originale est conservée, notamment les guillemets français avec espace, les points d’abréviation, les nombres décimaux et les signes de fin de phrase des alphabets couverts. Le filtre facultatif des noms de locuteurs conserve les frontières entre leurs interventions. Le moteur vocal reste responsable de la prosodie. L’accélération du WAV comprime les pauses avec le reste de l’audio ; aucun algorithme ne supprime les silences PCM.

## Image

Les trois modes distinguent proportions originales, étirement et recadrage. L’activation d’un mode « image entière » remet le zoom à 100 %, annule les déplacements et annule les marges précédemment retirées. Une nouvelle suppression de marges exige ensuite une nouvelle validation. La protection et le cadrage sont rétablis par vidéo ; une vidéo nouvelle n’hérite pas d’un recadrage choisi pour une autre œuvre.

Le diagnostic indique les dimensions codées, les dimensions déclarées, le rapport des pixels, le rapport d’affichage, la surface disponible et le nom du cadrage. La démonstration synthétique contient quatre coins colorés, une grille et un cercle pour contrôler le contenu réellement rendu.

L’analyse de sept images reste une proposition prudente et annulable. Elle ne garantit pas l’absence d’un sous-titre incrusté dans une marge entre les images échantillonnées. Les dialogues d’analyse sont fermés à la destruction de l’activité. La garde HDR suit aussi les changements de format vidéo pendant la lecture. La minuterie des commandes tient compte du délai recommandé par les services d’accessibilité Android.

## Préparation et cache

L’indexation vérifie l’annulation lors des lectures et avant de publier son résultat, y compris à 100 %. Les extracteurs inutilisés sont libérés. Un paquet textuel illisible, un horaire invalide ou un dépassement de limite signale un index incomplet. Une préparation de plus de 100 000 segments distincts est refusée explicitement. Le lecteur ne déclare pas qu’une préparation tronquée couvre toute la vidéo.

Un cache corrompu conduit à la lecture directe de la même réplique. Un ancien callback d’un lecteur de fichier déjà libéré ne peut pas terminer cette voix de secours. La limite d’espace reste applicable : préparer toute la vidéo ne garantit pas de conserver tous ses WAV si le cache est trop petit ou si Android libère cet espace.

Un état thermique Android sévère annule également l’indexation, pas seulement la synthèse anticipée. Une préparation complète annulée doit être relancée. L’anticipation normale peut reprendre après retour à un état compatible.

## Catalogue

41 notices documentaires distinguent moteurs, variantes, famille Sherpa, passerelles, moteur constructeur, sources, références anciennes, offres éditeur, démonstration et modèles. Il ne s’agit pas de 41 moteurs indépendants testés. Chaque notice présente une instruction d’installation adaptée à sa catégorie et ses sources consultées le 20 octobre 2026. Le lecteur sélectionne les voix déclarées locales par les moteurs ; une passerelle dépendant d’Internet peut donc être inutilisable ici.

## Validation reproductible

Le noyau a été compilé localement avec Kotlin 2.2.21 : 54 scénarios initiaux, 31 Cinéma et 55 de révision, soit 140 scénarios distincts. Les suites initiale et de révision ont été répétées avec les graines 42, 2026 et 7351, pour 120 000 transitions pseudo-aléatoires lors de la dernière passe. Les journaux locaux sont produits sous `build/checks/` et ne remplacent pas la compilation Android.

Le workflow exécute séparément les tests Gradle, Lint, la compilation des deux APK, les contrôles du manifeste et de la signature, puis les tests instrumentés. Il utilise un émulateur AOSP API 36, conserve les rapports XML, les captures et les diagnostics, et refuse une exécution vide ou des tests ignorés. La capture `stretch-four-corners.png` accompagne un test des pixels de l’image composée après un ancien zoom et recadrage.

Les résultats de la nouvelle exécution Android doivent être lus dans le run associé au commit livré ; ce document ne les préjuge pas. L’ancienne exécution 0.3 avait échoué avec une fenêtre ANR de SystemUI, documentée par sa capture. Aucun test n’a été supprimé pour contourner cet échec.

## Limites à conserver dans toute présentation

- Ni « zéro bug », ni efficacité mesurée sur un OnePlus non connecté à cet environnement.
- Les tests TTS utilisent un service synthétique de contrôle ; ils ne jugent pas le naturel d’une vraie voix.
- Les effets SDR utilisent le pipeline graphique Media3. Aucun traitement neuronal de super-résolution, contraste local avancé ou backend vocal NPU n’a été intégré.
- L’atténuation de bande vocale PCM16 est expérimentale et affecte aussi certains instruments. Ce n’est pas une séparation des dialogues et des ambiances.
- Les décodeurs proposés sont ceux déjà disponibles sur Android. Il n’y a pas de bibliothèque FFmpeg ou LibVLC de lecture ajoutée à cet APK.
- L’APK utilise une signature de test ; une chaîne de signature de production pérenne reste à organiser pour des mises à jour durables.
