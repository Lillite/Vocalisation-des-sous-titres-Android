# Vérifier la version 0.4.0 sur le téléphone

[Documentation](README.md) · [Installation et présentation](../README.md) · [Guide des traducteurs](GUIDE_MOTEURS_TRADUCTION.md)

Ce parcours concerne **Sous-titres en voix — Traduction 0.4.0**, version interne `0.4.0`, code `6`, identité `fr.soustitresvoix.player.translation`. **Les essais sur un téléphone réel décrits ici restent à effectuer : ils ne sont pas déclarés réussis par une compilation ou les tests sur émulateur.** Pour chaque ligne, noter « réussi », « échec » ou « non testé », puis les actions et résultats observés.

## Préparer une séance reproductible

Noter le modèle du téléphone, sa version Android, le moteur vocal et le nom exact de la voix. Garder un volume confortable et commencer par les fichiers synthétiques. Le lecteur ne demande pas d’envoyer une vidéo personnelle pour un diagnostic.

| Élément | Valeur à relever |
|---|---|
| Application | Nom affiché, version 0.4.0, date de l’essai |
| Téléphone | Modèle, Android, orientation, taille de police |
| Voix | Moteur, voix, langue, données installées, débit |
| Narration | Mode Confort, Adaptatif ou Continu ; reprise après Pause |
| Traduction | Fournisseur, langue source, serveur/région si applicable ; jamais la clé |
| Accessibilité | TalkBack activé ou non, version si connue |
| Résultat | Réussi, échec ou non testé ; attendu puis observé |

## Installer la bonne édition

1. Depuis Android, ouvrir la [release 0.4.0](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.4.0), puis télécharger [SousTitresVoix-0.4.0-traduction.apk](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/download/v0.4.0/SousTitresVoix-0.4.0-traduction.apk). Les archives « Source code » ne sont pas installables.
2. Ouvrir le fichier téléchargé. Si Android le demande, autoriser cette installation depuis le navigateur ou le gestionnaire de fichiers utilisé. Aucun ordinateur n’est nécessaire.
3. Ouvrir **Sous-titres en voix — Traduction 0.4.0**. Cette édition s’installe **à côté de la stable 0.3.2** ; ne pas désinstaller la 0.3.2 pour cet essai. Sa signature distincte impose cette identité séparée. Les fichiers vidéo d’origine ne sont pas modifiés.
4. Choisir de nouveau les vidéos, le moteur vocal, la voix et les réglages dans cette édition : ses données privées ne sont pas celles de la 0.3.2.

L’APK publié mesure **5 066 480 octets**. Sa somme SHA-256 est `30d1126392a9fd995867f599b12436b1e2d9e8e1d41bc60d20dca76c9b88f298` ; le fichier [SHA256SUMS](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/download/v0.4.0/SHA256SUMS) fournit la même référence. Le commit source est [`32864ba`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/tree/32864ba732d834e71d87b887ae63f0d7bc09eb8c). La procédure de vérification technique figure dans [TESTING.md](../TESTING.md).

## Choisir une voix et vérifier la narration originale

1. Ouvrir **Réglages de la voix et du confort**. Si nécessaire, utiliser **Choisir le moteur vocal de CE lecteur**, puis installer les données vocales dans ce moteur. Le choix du lecteur ne modifie pas le moteur de TalkBack.
2. Utiliser **Choisir une voix hors ligne** et choisir une voix française pour la démonstration intégrée et la future narration traduite. Une voix disponible en ligne uniquement ne devient pas hors ligne en désactivant Internet.
3. Appuyer sur **Écouter un essai de la voix**. Vérifier que le film reste en pause. Ajuster le débit et les volumes à un niveau confortable.
4. Choisir **Confort : phrases complètes**, puis **Essai intégré : sous-titres et mire de cadrage**.

| Essai | Résultat à vérifier |
|---|---|
| Écouter la première phrase | Une seule lecture de cette occurrence, sans boucle |
| Laisser finir une phrase longue en Confort | La vidéo attend si nécessaire ; la phrase n’est pas coupée pour rattraper le film |
| Appuyer sur Pause au milieu d’une phrase | Vidéo et voix s’arrêtent ; aucune reprise spontanée |
| Reprendre explicitement | Le comportement correspond au choix de **Choisir la reprise après Pause** |
| Reculer de dix secondes pendant la narration | L’ancienne phrase ne se poursuit pas au nouvel instant |
| **Répéter la dernière phrase — met en pause** | La phrase est répétée ; le film reste en pause à la fin |
| Atteindre la fin de la démonstration en Confort | La dernière phrase peut se terminer |
| Refaire l’essai en Adaptatif | Accélération limitée au plafond choisi, puis pause si le temps manque ; aucun mot sacrifié pour tenir le délai |

Pour Adaptatif, une base à 100 %, un plafond à 180 % et une tolérance de 600 ms constituent un point de départ à ajuster à l’écoute. Le mode **Continu : peut couper les phrases** ne convient pas pour vérifier la vocalisation complète.

## Préparer la piste anglaise synthétique

1. Télécharger le fichier [essai-traduction-en.srt](https://raw.githubusercontent.com/Lillite/Vocalisation-des-sous-titres-Android/main/examples/essai-traduction-en.srt). Si le navigateur affiche son texte, utiliser son option de téléchargement ou d’enregistrement et conserver l’extension `.srt`.
2. Ouvrir la démonstration intégrée ou une vidéo synthétique d’au moins 24 secondes.
3. Choisir **Ajouter un fichier de sous-titres**, sélectionner le SRT téléchargé, puis vérifier que cette piste est choisie dans **Choisir la piste de sous-titres**.
4. Revenir au début et garder la lecture en pause. La langue de cette piste est l’anglais : utiliser `en`, même si l’audio de la vidéo est dans une autre langue.

Le fichier contient **cinq occurrences** : un panneau continu, un dialogue qui lui est superposé, deux occurrences distinctes contenant les mêmes mots, puis une phrase plus longue. Le traducteur peut produire des formulations françaises différentes ; vérifier le sens et la conservation des cinq occurrences, sans exiger une phrase française unique.

| Occurrence | Horaire source | Texte source | Contrôle |
|---|---|---|---|
| 1 | 00:00–00:12 | `STATION ENTRANCE` | Le panneau continu n’est pas relu à chaque nouveau dialogue |
| 2 | 00:02–00:05 | `Welcome. Please follow me.` | Le dialogue superposé au panneau n’est pas perdu |
| 3 | 00:06–00:08 | `Wait here.` | Première occurrence conservée |
| 4 | 00:09–00:11 | `Wait here.` | Deuxième occurrence également conservée |
| 5 | 00:13–00:24 | `The next train will arrive soon. We have enough time to finish this sentence before leaving.` | Phrase complète en Confort, avec pause si nécessaire |

## Traduire cette piste vers le français

La traduction initiale nécessite un fournisseur configuré et une connexion réseau. Les [instructions par fournisseur](GUIDE_MOTEURS_TRADUCTION.md) expliquent Azure, les deux points d’accès DeepL et les serveurs LibreTranslate. Une clé de moteur vocal ou un abonnement d’une autre application ne constitue pas automatiquement une clé API de traduction.

1. Ouvrir **Traduire les sous-titres en français**. Vérifier que le film se met en pause.
2. Choisir le fournisseur correspondant au compte et lire sa description. Pour DeepL, choisir explicitement API Free ou le point d’accès payant adapté ; pour Azure, saisir la région de la ressource si nécessaire ; pour LibreTranslate, saisir l’adresse de base HTTPS du serveur.
3. Indiquer `en` comme langue originale et saisir la clé personnelle si une nouvelle traduction en exige une. La case de mémorisation chiffrée est facultative. Ne pas partager de capture contenant une clé.
4. Appuyer sur **Indexer la piste et estimer la traduction**. Vérifier l’annonce de **cinq occurrences** et le volume estimé. Cette phase d’indexation n’envoie pas la piste au fournisseur.
5. Lire la confirmation : fournisseur/serveur, volume et avertissements. L’application ne mesure ni le solde du compte ni le coût exact. Appuyer sur **Traduire / réutiliser le cache** autorise l’envoi du texte si aucun cache complet correspondant n’existe. La vidéo et l’audio ne sont pas envoyés par cette traduction.
6. Attendre le résultat dans l’application. Une réussite active uniquement une piste complète validée ; le film et la voix doivent rester en pause. Choisir une voix française prête si la vocalisation est activée, puis appuyer explicitement sur Lire.
7. Vérifier que la voix prononce le français tandis que l’affichage vidéo conserve les sous-titres anglais originaux. Observer les cinq occurrences du tableau et leur correspondance temporelle.
8. Ouvrir de nouveau le panneau et utiliser **Revenir explicitement à la piste originale**. Vérifier la pause, puis choisir une voix correspondant à la piste originale avant de reprendre.

Une validation structurelle ne garantit pas une traduction linguistiquement parfaite. Relever séparément une formulation incorrecte, une occurrence manquante et un problème de synchronisation.

## Vérifier le cache, l’annulation et les erreurs

| Essai | Résultat à vérifier |
|---|---|
| Une traduction complète a réussi ; fermer/réouvrir l’application, sélectionner exactement le même média et la même piste, puis demander le même moteur/langues/serveur sans clé | Si le cache correspondant est encore présent, réutilisation complète sans nouvelle requête |
| Après cette réussite, couper le réseau et répéter la demande | La piste complète en cache peut être réutilisée ; une piste absente du cache ne peut pas être traduite hors ligne |
| Annuler l’indexation ou fermer le panneau avant confirmation | Aucune confirmation tardive ni reprise automatique du film |
| Annuler une traduction déjà confirmée | Aucun résultat partiel activé et aucune reprise automatique ; une requête déjà reçue par le fournisseur peut avoir été facturée |
| Changer de piste ou de vidéo pendant le travail | Une ancienne réponse ne remplace pas la nouvelle piste |
| Sans cache correspondant, demander une traduction sans clé alors que le fournisseur l’exige | Erreur visible ; aucune substitution silencieuse par la piste originale |
| Utiliser **Effacer la clé mémorisée pour ce moteur** | La clé de ce moteur/serveur est effacée ; sa suppression n’efface pas les traductions complètes déjà en cache |
| Avec LibreTranslate, changer l’adresse du serveur | La clé d’un autre serveur n’est pas conservée dans le champ |

Noter « non testé » lorsqu’une condition ne peut pas être reproduite sans modifier un compte ou engager un coût. Ne pas multiplier les traductions payantes pour provoquer des pannes. Une réponse réseau perdue peut déjà avoir été facturée ; le lecteur ne la relance pas automatiquement. Le cache privé est limité à 32 Mio et peut être évincé ; il ne sauvegarde pas de progression partielle et ne fournit pas d’export de traduction.

## Vérifier les autres pistes textuelles

Choisir une vidéo synthétique avec une piste intégrée, puis un fichier externe SRT, VTT ou ASS/SSA connu. Pour les panneaux et sous-titres FULL, vérifier que les dialogues superposés restent prononcés, que deux événements distincts portant les mêmes mots sont conservés et qu’un panneau réapparaissant plus tard est de nouveau lu. Un changement de piste ou un déplacement ne doit pas laisser continuer une ancienne phrase.

Un MKV peut contenir un codec non pris en charge. Les sous-titres en images PGS/VobSub et le texte incrusté dans l’image nécessitent un OCR absent de cette version. Les formats de modèles GGUF, SafeTensors et ONNX ne s’importent pas dans ce lecteur ; voir l’[étude de traduction locale](ETUDE_TRADUCTION_LOCALE.md).

## Vérifier l’image et le plein écran

1. Dans la démonstration, activer **Plein écran cinéma**, puis **Étirement intégral**. Les quatre coins rouge, vert, bleu et jaune doivent rester visibles. Si l’écran et la vidéo ont des proportions différentes, le cercle central devient une ellipse.
2. Ouvrir les réglages d’image et comparer **Image entière, proportions originales**, **Tout l’écran — image entière étirée** et **Tout l’écran, image recadrée**. Le recadrage volontaire doit demander confirmation lorsque la protection est active.
3. Après un zoom ou recadrage, revenir à un mode « image entière » : vérifier zoom à 100 %, déplacements annulés et marges retirées annulées.
4. Consulter **Diagnostic du cadrage et des bandes noires** et comparer taille vidéo, surface disponible et mode actif.
5. Sur un extrait adapté, essayer **Analyser les marges encodées — aperçu obligatoire**. Une proposition doit précéder l’application. Vérifier **Annuler toute suppression de marges**, puis ouvrir une autre vidéo : elle ne doit pas hériter des anciennes marges.
6. Essayer une saisie précise du contraste ou de la luminosité, puis son rétablissement. Comparer avec l’original et lire le message lorsqu’un traitement n’est pas disponible dans le contexte courant.

L’analyse échantillonnée ne certifie pas l’absence de texte dans toutes les marges d’une œuvre. Les coins arrondis et l’emplacement physique de la caméra restent des contraintes de l’écran. Les [réglages d’image](INTERFACE_IMAGE_ETAPE_4.md) détaillent les valeurs et limites.

## Vérifier le son du film et les traitements avancés

Commencer avec les traitements avancés désactivés et un volume modéré. Ne pas employer un fort gain pour rendre un test plus audible.

| Essai | Résultat à vérifier |
|---|---|
| Régler séparément volume du film et volume de la voix | Les commandes produisent des effets distincts |
| Essayer atténuation et fondus du film pendant une phrase | Changements progressifs conformes aux réglages, sans reprise après Pause |
| Ouvrir **Traitements audio avancés du film** | Traitements optionnels désactivés par défaut, paramètres expliqués |
| Saisir un préampli négatif, puis rétablir | Valeur relue correctement et retour à la valeur prévue |
| Régler une bande de l’égaliseur à faible gain, puis la désactiver | Effet sur le son du film ; la voix du lecteur conserve son parcours séparé |
| Tester balance et mono avec un extrait stéréo adapté | Effet attendu gauche/droite et retour au réglage initial |
| Activer puis désactiver le limiteur à niveau confortable | État et paramètres conservés ; absence de prétention de mesure true-peak |
| Fermer et rouvrir les réglages | Valeurs affichées conformes aux choix conservés |

Ces traitements concernent le **PCM16 mono/stéréo effectivement reçu**. Un test sur ce parcours ne prouve pas un fonctionnement en passthrough, multicanal ou sur tous les codecs. Pour les valeurs, domaines et interactions, consulter les [commandes audio](COMMANDES_AUDIO_ETAPE_5.md) et l’[audio avancé](AUDIO_EXPERT_ETAPE_6.md).

## Préparation vocale et moteur externe

- Essayer **Préparer les 5 prochaines minutes**, puis annuler : état compréhensible et aucune reprise spontanée.
- Avec un extrait court, essayer **Indexer et préparer toute la vidéo**, puis vérifier la lecture et la réouverture de l’application. Une préparation achevée ne garantit pas qu’un long film tient encore dans le cache vocal limité.
- Changer de voix ou de débit : l’ancien audio préparé ne doit pas être réutilisé comme s’il était à jour.
- Utiliser **Vider le cache vocal**, puis vérifier la synthèse directe. Ce cache audio est distinct du cache de textes traduits.
- Si un moteur devient indisponible, vérifier l’erreur et la possibilité de choisir un autre moteur. Ce scénario peut rester « non testé » ; il n’exige pas de désinstaller un moteur employé ailleurs.

## Accessibilité, interruptions et séance prolongée

Vérifier les libellés, l’ordre de navigation et l’accès aux champs avec TalkBack, notamment le formulaire de traduction et sa confirmation. Essayer les polices agrandies, le portrait/paysage et l’agrandissement système. Les sous-titres ne doivent pas être lus involontairement deux fois par TalkBack et la synthèse du lecteur. Les commandes doivent rester accessibles sans geste obligatoire.

Tester la mise en arrière-plan, le verrouillage, une interruption audio et le branchement/débranchement d’un casque. Vérifier l’absence de reprise inattendue. Une traduction confirmée n’est pas un travail durable garanti après fermeture du processus ; revenir dans l’application et vérifier son état.

Effectuer enfin une séance prolongée avec les fichiers et le moteur habituels. Relever synchronisation, intelligibilité, pauses, blocages éventuels, chauffe et consommation observée. Un émulateur ne mesure pas les performances, la qualité acoustique ou l’autonomie du téléphone.

## Signaler un résultat exploitable

Indiquer version, téléphone, Android, moteur et voix, mode vocal, étapes exactes, résultat attendu puis observé. Pour la traduction, ajouter fournisseur, langue source et utilisation éventuelle du cache, sans clé API. Pour la lecture, préciser conteneur, codecs, type de sous-titres et instant concerné.

**Copier le diagnostic complet** peut aider. Relire le contenu avant partage et retirer toute information personnelle inutile. Privilégier un court exemple de sous-titres synthétiques reproduisant le problème ; ne pas joindre un film personnel ni les fichiers privés de signature.
