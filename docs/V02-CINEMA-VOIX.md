# Version Cinéma 0.2 — périmètre et protocole

Cette version s'installe en parallèle de la 0.1 (`fr.soustitresvoix.player.cinema`). Elle ne supprime ni ne modifie l'installation précédente. La signature debug précédente n'étant pas disponible, ne pas promettre une mise à jour en place. Les réglages de voix devront être choisis une première fois dans la nouvelle application.

## Fonctions

- Plein écran immersif : vidéo seule, barres système masquées, commandes superposées. Retour déverrouille d'abord les commandes, puis quitte le plein écran, sans reconstruire le lecteur.
- Adapter/Remplir/Étirer ; zoom 100–300 %, recentrage ; le zoom vise la surface vidéo, pas la couche de sous-titres. Gestes facultatifs, volume média à droite, luminosité propre à cette fenêtre à gauche, déplacement de l'image lorsqu'elle est zoomée. Sous TalkBack, les gestes du lecteur sont désactivés et les commandes ne sont pas masquées automatiquement.
- Moteur vocal choisi pour cette application, sans changer les réglages de TalkBack. Voix annoncées hors ligne seulement. Aucun modèle neuronal n'est intégré arbitrairement à l'APK.
- Modes confort/continu conservés ; mode adaptatif optionnel. Avec un intervalle indexé, le facteur est calculé d'après les durées audio en cache ou, à défaut, une estimation explicite de 65 ms/caractère à vitesse 1. Le plafond relatif est configurable, 135 % par défaut. Le mode adaptatif conserve toujours la pause confort s'il ne suffit pas ; aucune réplique n'est résumée.
- Préparation spéculative par `synthesizeToFile`, segments de phrase <= 280 unités UTF-16, limites de 24 segments en attente, un seul travail TTS à la fois, priorité absolue au texte à écouter maintenant. Cache privé de 64 Mio par défaut (16–128 configurables), fichier individuel limité à 8 Mio, durée maximale 180 s. La limite disque peut être dépassée temporairement par un fichier en cours de synthèse (8 Mio maximum) et un segment épinglé en lecture. Aucun cache n'est conservé comme donnée durable : les restes sont nettoyés au redémarrage.
- Invalidation par moteur/version/voix/vitesse/texte ; changement de filtre annule les travaux spéculatifs. Déplacement et changement de média annulent la file obsolète. Le cache est désactivable et vidable. L'économiseur d'énergie et un état thermique Android >= modéré suspendent la préparation, pas la lecture en cours.
- Indexation locale des SRT/VTT/ASS externes ; indexation MKV des pistes S_TEXT/UTF8 et ASS/SSA non compressées, non chiffrées et sans lacing, avec BlockDuration explicite. Les blocs vidéo/audio sont sautés sans décodage. Limites : 15 s de travail d'indexation, 50 000 répliques et 8 Mio de texte. Media3 reste l'autorité pour déclencher la parole. Sans index exploitable : vocalisation directe et confort conservés, pas de faux calcul de durée.
- Reprise au début de la réplique (par bloc de 1000 caractères maximum dans le noyau), au début du segment réellement interrompu, ou sur commande explicite. Pas de reprise prétendue au mot près.
- Baisse du film : profondeur, attaque, remontée et maintien anti-pompage configurables. Le mixage affecte toute la bande sonore, pas seulement les dialogues d'origine.
- Filtres indépendants avec aperçu : crochets, étiquettes explicites en capitales, lignes débutant par une note musicale, notes NDT. Ce sont des règles syntaxiques facultatives, pas une compréhension certaine de l'identité des personnages. Le fichier original n'est pas modifié.
- Diagnostic partageable sans noms de fichiers ni dialogues : temps CPU du lecteur, mémoire du lecteur, température de BATTERIE, état thermique Android, décodeur signalé par Media3, images perdues, première image, préparation vocale et cache. Aucun pourcentage GPU/NPU inventé. Le processus du moteur TTS n'est pas inclus dans les mesures CPU/mémoire du lecteur.

## Moteurs à écouter et installer

Pages officielles avec échantillons et APK moteur Android :
- Miro français : https://k2-fsa.github.io/sherpa/onnx/tts/all/French/vits-piper-fr_FR-miro-high.html
- Tom français : https://k2-fsa.github.io/sherpa/onnx/tts/all/French/vits-piper-fr_FR-tom-medium.html
- Acapela : https://play.google.com/store/apps/details?id=com.acapelagroup.android.tts
- Google : https://play.google.com/store/apps/details?id=com.google.android.tts

Pour le OnePlus 15, choisir un APK **arm64-v8a** lorsqu'une architecture est demandée. Sur les pages Sherpa, choisir **Android TTS Engine APK**, pas une simple démonstration de génération sonore. Les modèles .onnx isolés ne s'importent pas directement dans ce lecteur. Ouvrir d'abord l'application du moteur pour télécharger/installer les voix, puis relancer le lecteur et choisir le moteur et sa voix française. Ne pas désactiver Play Protect. La présence dans le catalogue n'est pas une validation de compatibilité de chaque APK avec chaque téléphone ; tester l'échantillon, puis la démonstration. Écouter avant un achat Acapela.

Le Snapdragon 8 Elite Gen 5, l'Adreno 840 et le NPU du OnePlus 15 ne donnent pas un accès automatique au NPU à tout moteur Android TTS. L'accélération dépend du moteur, de ses opérateurs et de ses bibliothèques. Cette version ne prétend pas intégrer QNN ni forcer un moteur externe à utiliser le GPU/NPU. Elle exploite le décodeur choisi par Media3 et la préparation vocale locale, sans charger inutilement tous les cœurs.

## Validation sur téléphone à effectuer

1. Essai intégré, voix française, confort d'abord, puis adaptatif.
2. Entrer/sortir du plein écran pendant une réplique, tourner l'appareil, masquer les contrôles, afficher les barres Android et revenir. Pas de reprise ou de doublon involontaire.
3. Contrôles avec TalkBack et très grandes polices : aucun bouton inaccessible ou masqué pendant l'exploration.
4. Tester adapter et remplir (bandes noires légitimes si proportions différentes), zoom et recentrage, volume et luminosité. Quitter : orientation et luminosité précédentes restaurées.
5. Trois politiques de reprise ; changements rapides de piste et déplacements. Une phrase ancienne ne doit pas réapparaître.
6. Cache activé/désactivé, deux voix et deux vitesses, puis export du diagnostic. Vérifier les compteurs et l'index sur un MKV compatible.
7. Lecture prolongée sur OnePlus 15 : voix compréhensible, pas de surchauffe gênante ni d'accumulation incontrôlée de cache. Les valeurs réelles ne peuvent être connues avant cet essai.

Le moteur TTS synthétique des tests instrumentés produit du silence PCM déterministe. Il valide le chemin Android -> WAV -> cache -> MediaPlayer, pas le naturel d'une voix. Il n'est pas inclus dans l'APK de l'utilisateur. Les résultats de tests exécutés sont dans Actions ; ne pas assimiler la présence d'un test à sa réussite.
