# Architecture et garanties visées

## Noyau déterministe

`NarrationMachine` est une machine à états indépendante d’Android. Les événements (lecture, pause, déplacement, nouveau sous-titre, disponibilité de la voix, fin ou échec d’une phrase) retournent une liste ordonnée d’effets. L’adaptateur exécute ces effets sur le thread principal. Chaque énoncé dispose d’un identifiant nouveau ; les callbacks anciens ne peuvent donc pas terminer la phrase courante.

En mode confort, la fin d’un intervalle de sous-titre marque la phrase en cours comme expirée. La vidéo attend la fin des phrases expirées, sans sacrifier le texte suivant. En mode continu, l’ancienne phrase peut être interrompue au changement de sous-titre. Une pause manuelle invalide la voix et interdit toute reprise sans action explicite.

`SubtitleText` nettoie les styles connus et découpe les textes longs sans couper une paire de substitution UTF-16. `SubtitleImport` lit les encodages de manière stricte, refuse les fichiers vides, binaires ou trop grands et conserve les originaux.

## Adaptateur Android

`PlaybackSession` relie Media3, la machine à états et les interruptions audio. `AndroidSpeech` relaie les callbacks du moteur vocal vers le thread principal et borne l’attente d’une réponse. `PlayerPreferences` conserve les réglages et la dernière position. La baisse de volume affecte tout le mix du film : ce n’est pas une suppression des voix originales.

Le sélecteur de fichiers Android accorde un accès ciblé au document choisi. Les sous-titres externes sont importés dans le répertoire privé de l’application, sans modification du fichier original. Les vidéos ne sont ni copiées sur un serveur ni envoyées à une IA.

## Interface

`MainActivity` utilise des contrôles natifs, textuels, de grande taille, sans geste obligatoire. La synthèse des sous-titres est distincte des annonces de l’interface. Les erreurs quittent le plein écran pour rester visibles. La lecture s’arrête lorsque l’activité passe en arrière-plan.

## Vérification

Le noyau possède des tests JVM reproductibles. La compilation complète, Android Lint, l’inspection du manifeste final et de la signature sont exécutés par le workflow. Deux tests instrumentés sont prévus sur Android 35. Leur réussite est à constater dans les rapports de chaque exécution, et ne remplace pas les essais d’écoute et d’accessibilité sur le téléphone cible.

La démonstration binaire est générée automatiquement par Gradle à partir d’un SRT synthétique, afin de conserver un dépôt textuel inspectable. Cela ajoute FFmpeg aux prérequis de compilation, sans l’intégrer à l’APK.
