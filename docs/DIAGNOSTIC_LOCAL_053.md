# Diagnostic local — préversion publiée 0.5.3

[Accueil](../README.md) · [Documentation](README.md) · [Notes du candidat](releases/0.5.3-diagnostic.md) · [Validation](../TESTING.md)

La version **0.5.3-diagnostic / code 10**, [publiée comme préversion](releases/0.5.3-diagnostic.md), prépare les moteurs locaux. Elle mesure le téléphone, essaie un court décodage vidéo et permet d’exécuter réellement un modèle GGUF sur le **CPU**. Ce diagnostic ne réalise ni OCR de film ni traduction de sous-titres avec TranslateGemma. La [0.5.2](releases/0.5.2.md) reste la stable recommandée. L’intégration de traduction GGUF de la [candidate 0.5.4](TRADUCTION_GGUF_054.md) constitue une étape séparée.

## Ouvrir le diagnostic

Dans **Réglages de la voix et du confort → Diagnostic**, ouvrir **Diagnostic local : appareil, vidéo et modèle**. Chaque mesure ou essai demande une action explicite. Ouvrir le panneau ne charge aucun modèle, ne lance aucune voix et ne démarre pas la vidéo.

Les étapes sont indépendantes : on peut relever les mesures, essayer une vidéo ou essayer un modèle sans devoir faire les autres opérations. Ce découpage prépare aussi l’indépendance future de l’OCR et de la traduction.

## Mesurer le téléphone

**Actualiser les mesures du téléphone** relève les informations exposées par Android : modèle d’appareil, API, architectures, nombre de processeurs disponibles, RAM physique et disponible, mémoire du processus, tas natif, espace privé disponible et état thermique lorsque ces valeurs sont accessibles. La RAM physique est distinguée d’une extension de mémoire sur le stockage.

L’inventaire des décodeurs donne leurs types vidéo et leur classification déclarée matérielle ou logicielle. Une déclaration de codec ne prouve pas qu’il a décodé le fichier choisi. La présence d’un GPU ou d’un NPU ne prouve aucune accélération du modèle : **aucun essai GPU/NPU n’est exécuté dans cette étape**.

Ces mesures sont ponctuelles. La taille d’un GGUF sur disque ne donne pas la RAM nécessaire au chargement, au contexte et à la génération ; un modèle peut donc échouer malgré un fichier qui semble tenir dans l’espace disponible.

## Essayer le décodage d’une vidéo

1. Commencer par **Tester la démonstration vidéo intégrée**, avec le contenu synthétique de l’application.
2. Utiliser ensuite **Choisir une vidéo pour un essai de décodage** si nécessaire. Le fichier reste sur le téléphone et n’est pas modifié.
3. Lire le résultat : le diagnostic demande au plus **8 images sur une fenêtre de 10 secondes** et distingue ce qui a effectivement été décodé, les temps observés, l’annulation et les erreurs.

Cet échantillon ne parcourt pas un film complet, ne reconnaît pas les textes et ne produit pas de SRT. Il ne valide ni tous les codecs d’un conteneur ni l’ensemble du fichier. L’ajout complet des anciens formats, dont AVI, reste une étape ultérieure ; un sélecteur qui accepte un fichier ne garantit pas son décodage par Android.

## Essayer un modèle GGUF local

Le moteur intégré est **llama.cpp**, exécuté par JNI dans un **processus de service distinct** de celui du lecteur. Cette première version utilise seulement le CPU et une courte phrase synthétique. Elle ne transforme pas PocketPal en serveur et ne dépend pas de son application : le fichier GGUF sélectionné est chargé directement par ce lecteur.

Commencer par **Tester le moteur CPU intégré** : le petit modèle synthétique fourni avec l’application effectue un vrai chargement et un vrai calcul, sans rien télécharger. Ce contrôle vérifie le moteur, pas la qualité de traduction ni la capacité à charger un grand modèle. Il ne remplace pas le modèle personnel importé.

1. Si aucun modèle adapté n’est déjà présent, attendre une recommandation fondée sur les mesures du téléphone. Aucun téléchargement de poids n’est imposé pour consulter le diagnostic ou essayer la vidéo.
2. Appuyer sur **Choisir un modèle GGUF local**, puis sélectionner explicitement le fichier Android. Cette sélection déclenche une copie privée nécessaire au moteur, avec l’espace disque correspondant. L’original et les fichiers de PocketPal ne sont pas déplacés.
3. Appuyer sur **Tester le modèle sur une phrase synthétique**. L’import seul ne lance pas une inférence.
4. Consulter le chargement, le traitement de la phrase, les jetons générés, les durées, la mémoire observée et l’état final. Une erreur ou une annulation ne doit jamais devenir un succès.

Un GGUF n’est pas automatiquement compatible : son architecture, ses métadonnées et les ressources nécessaires doivent être acceptées par le moteur. Un chargement et une courte génération réussis établissent une exécution CPU ; ils ne valident pas la qualité linguistique, le format de requête d’un modèle spécialisé comme TranslateGemma, ni son adaptation à un long film. Le choix et le réglage du meilleur modèle de traduction restent à faire après ces mesures.

Le runtime est épinglé dans les sources à la révision `8ca4060b7e30e76baaa5670ee1ec1b760b7f8552` de [llama.cpp](https://github.com/ggml-org/llama.cpp). La licence MIT et les attributions du moteur tiers restent distinctes du travail propre à l’application.

L’essai utilise un contexte de 512 tokens et une sortie limitée à 32 tokens. Ces bornes servent à mesurer un essai court ; elles ne définissent pas les futurs traitements de sous-titres. Rester dans l’application pendant l’essai. **Supprimer la copie du modèle importé** permet aussi de nettoyer une copie incomplète laissée par un arrêt de l’application, en conservant toujours l’original.

## Annuler et récupérer le rapport

**Annuler l’essai en cours** demande son arrêt. **Fermer le diagnostic** annule également le travail de ce panneau et invalide ses réponses tardives. Le service distinct permet de contenir une défaillance du chargement natif ; il ne garantit pas que le système dispose de suffisamment de mémoire pour tout modèle. Ces commandes ne relancent ni la lecture ni une vocalisation.

Le rapport peut être **copié dans le presse-papiers** par l’action prévue dans le panneau. Il n’y a pas d’export de fichier de rapport à cette étape. Les informations du diagnostic excluent le nom et l’adresse du fichier personnel ainsi que les sous-titres du film ; la phrase de modèle est synthétique. Copier reste une action volontaire, et partager ensuite le rapport est un choix distinct de l’utilisateur.

Aucune vidéo ni aucun poids ne sont téléversés, aucun poids n’est téléchargé automatiquement et aucun nouveau service Internet n’est ajouté par ce diagnostic. Les moteurs de traduction déjà présents gardent leur fonctionnement propre et leurs confirmations existantes.

## Ce que les essais permettront de décider

Le relevé et les échecs éventuels servent à choisir ensuite un modèle, sa quantification et les ressources laissées au lecteur. Les performances, la chauffe, l’autonomie, TalkBack et la qualité d’une traduction demandent une vérification sur le téléphone réel. Les tests automatisés sur contenus synthétiques ne remplacent pas ces observations.

Les prochaines étapes pourront ajouter l’OCR seul, la traduction seule et leur enchaînement sur choix explicite, puis le traitement progressif pendant le visionnage. Le diagnostic ne prétend pas déjà fournir ces traitements de film.
