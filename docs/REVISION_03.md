# Révision 0.3 — périmètre et limites

> **Document historique — révision 0.3.** Les branches, identifiants d’application et constats ci-dessous sont ceux de cette édition. Pour l’édition actuelle, consulter l’[accueil du projet](../README.md), la [procédure de validation](../TESTING.md) et l’[historique des branches](HISTORIQUE_BRANCHES.md).

Code de test, identifiant `fr.soustitresvoix.player.cinema3`, branche `cinema-0.3`. Les branches 0.1 et 0.2 restent intactes. Pas de promesse « zéro bug ». Aucune mesure sur le OnePlus n’est inventée : les résultats Android réellement exécutés sont dans Actions, les mesures locales dans l’application.

## Adaptatif

La 0.2 rétablissait systématiquement la vitesse normale après un défaut de cache. La 0.3 propose une estimation calibrée dans ce cas et utilise la durée WAV quand elle existe. La vitesse maximale autorisée est distincte de la vitesse réellement demandée. Le plafond proposé au départ est 180 %, réglable 100–250 % de la vitesse de base. Un moteur externe peut refuser ou interpréter ce réglage différemment.

Les segments restants de la même réplique sont réservés dans le budget. Une fenêtre des prochaines répliques estime la pression d’ensemble ; les sous-titres ne sont JAMAIS prononcés avant leurs événements de lecture. Des échéances connues retardent la pause jusqu’à la fin de la réplique + la tolérance choisie (600 ms par défaut, 0–2000 ms). Si l’échéance n’est pas connue, l’ancien mécanisme confort reste la sécurité. Une pause peut donc toujours être nécessaire : ni mots supprimés, ni silence PCM retiré, ni résumé automatique.

## Préparation

Une indexation locale explicite parcourt le conteneur sans décoder les pistes audio/vidéo ; elle lit aussi les fichiers SRT/VTT/ASS ajoutés. « Préparer 5 minutes » prépare la piste courante à partir de la position ; « Toute la vidéo » part du début. Pendant ce traitement, le film reste en pause. L’index est en mémoire pour le média courant, pas persistant sur disque. Limites : 8 Mio pour une piste externe, 32 Gio parcourus, 100 000 entrées et 12 millions de caractères. Un dépassement échoue explicitement ; un format que Media3 n’expose pas en texte reste non indexable.

Le cache audio est persistant mais borné (16–2048 Mio), avec identités moteur/version/date de mise à jour/voix/paramètres/règles de texte. Un modèle changé à l’intérieur d’un moteur sans changement des métadonnées Android demande le bouton « modèle mis à jour ». Aucun moteur n’expose nécessairement le hash de ses poids via TextToSpeech : ce point n’est pas deviné. Les empreintes SHA-256 des fichiers détectent une corruption, pas la qualité du timbre. En cas de défaut, reprise directe de la même phrase. La préparation entière peut dépasser le cache et évincer des fichiers anciens : « parcours terminé » n’est pas une garantie que tout l’audio tient encore en cache.

L’anticipation normale se mesure en secondes estimées de voix, complétée par une limite dure de segments. Profils : économie 15 s/64 Mio/32 segments ; équilibré 45 s/256 Mio/128 ; intensif 180 s/1024 Mio/512. Le mode intensif n’active aucun NPU à lui seul. Une préparation peut s’arrêter si le moteur ne produit pas un WAV exploitable ou si la pression thermique est sévère.

## Image

Raccourci « Étirement intégral » : toute la fenêtre, proportions modifiées, zoom réinitialisé et protection contre pincement/recadrage accidentel. Trois intitulés explicites distinguent proportions originales, image étirée et image recadrée. Mode mémorisé par URI ; si un fournisseur change l’URI, cette association ne survit pas automatiquement. Le poinçon de caméra et les coins physiques ne deviennent pas des pixels utilisables.

Diagnostic : taille vidéo, rapport de pixels, dimensions de fenêtre, mode, zoom et marges explicitement approuvées. Détection optionnelle : sept images de synchronisation réparties, analyse bornée 480×270, exclusion des scènes sombres. Une proposition n’est appliquée qu’après un aperçu et une confirmation ; elle ne prouve PAS l’absence de texte dans toutes les images non échantillonnées. Aucun fichier original n’est modifié. Revenir aux marges nulles annule le traitement.

Recadrage approuvé, contraste global modéré et luminosité sont réalisés par les effets Media3. Comparaison marche/arrêt immédiate. Ce n’est ni du contraste local sophistiqué, ni de la super-résolution neuronale, ni une restauration de détail perdu. Traitements désactivés pour les transferts HDR/HLG/Dolby Vision reconnus. Aucun rendu HDR réel n’est certifié par un test SDR d’émulateur.

## Audio et codecs

Ducking anticipé à partir de l’horaire du prochain sous-titre. Un traitement expérimental optionnel atténue une large bande vocale du film PCM16 avec transition progressive ; il ne sépare pas les sources et affecte aussi certains instruments. Désactivé par défaut. Les autres formats audio ne sont pas traités. Aucun boost incontrôlé, aucune suppression de mots narrés.

Un inventaire des décodeurs déclarés et un essai avec priorité logicielle sont proposés. Cet essai utilise les décodeurs déjà présents ; il n’ajoute ni FFmpeg, ni LibVLC, ni tous les codecs manquants. Changement explicite avec reprise demandée par l’utilisateur. Préférence de cadence vidéo sans changement d’affichage non transparent, selon les capacités Android. Aucune interpolation d’images.

## Mesures

Fenêtres bornées de temps de synthèse, délai demande→début de restitution, recherche de cache et pauses de confort : effectif, médiane, p95, p99, maximum. Le callback de démarrage ou MediaPlayer.start ne mesure pas le temps jusqu’au tympan ni toute latence Bluetooth. Les mesures CPU/PSS du lecteur n’incluent pas le moteur vocal externe. Température de batterie ≠ température de puce. Le banc de six extraits prépare l’audio ; l’écoute et les longues séances alimentent les autres mesures. Aucune consommation en watts ni gain chiffré sur OnePlus n’est déclaré sans mesure.

## NPU et moteurs

Le catalogue distingue APK moteurs, projets source, passerelles distantes et modèles. La recherche documentaire ne prouve pas l’installation ni la qualité sur OnePlus. Aucun poids neuronal / runtime QNN propriétaire n’est intégré à cet APK. L’API TTS ne permet pas de sélectionner le backend matériel d’un moteur externe. Voir ETUDE_ACCELERATION.md.
