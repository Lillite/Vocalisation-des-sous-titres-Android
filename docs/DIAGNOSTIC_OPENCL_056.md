# Diagnostic OpenCL — préversion 0.5.6 publiée

[Accueil](../README.md) · [Notes de version](releases/0.5.6-opencl.md) · [Validation](../TESTING.md) · [Performances GGUF 0.5.5](TRADUCTION_GGUF_055.md)

La préversion publiée **0.5.6-opencl / code 13** corrige le diagnostic trop général des échecs GPU. La traduction locale, les poids du modèle et leur quantification restent conservés. Cette étape ne garantit ni le fonctionnement d’OpenCL sur le téléphone ni une accélération supplémentaire. Le NPU et l’OCR restent hors de ce périmètre.

## Ce qui a été observé

Deux rapports transmis pour le même GGUF et le même texte synthétique montrent un calcul CPU réussi. Le temps total du mode est passé de **26,340 s avec 8 threads** à **21,373 s avec 4 threads**, soit environ **19 % de moins**. Le deuxième appel identique a pris respectivement **3,414 s** et **2,722 s**. Quatre threads constituent donc un réglage provisoire utile pour ce téléphone et ces essais ; ils ne sont pas un optimum universel établi.

La mémoire disponible, l’état thermique, le cache système, les autres applications et une éventuelle mise à jour Android peuvent différer entre essais. Ce relevé ponctuel ne constitue pas une comparaison contrôlée répétée. Un numéro commercial OxygenOS ou Android ne remplace pas le niveau d’API réellement rapporté par le système ; le rapport de la nouvelle candidate doit conserver cette identité pour les essais suivants.

Le cache natif a réellement réutilisé **92 tokens d’entrée sur 93** au deuxième passage. Ce bénéfice porte sur une consigne strictement identique dans la même session ; il ne prédit pas le débit d’un film composé de textes différents. Les deux traductions synthétiques identiques ne constituent pas une évaluation de qualité linguistique.

Les deux modes OpenCL ont échoué avec un message général, en **921 ms** puis **50 ms**. Ces durées ne démontrent ni un calcul GPU, ni un manque de mémoire, ni une incompatibilité définitive. Le diagnostic natif distinguait déjà plusieurs causes, mais les couches de communication perdaient ce détail.

## Lire le nouveau rapport

La version transmet des valeurs techniques limitées à un ensemble connu :

- L’étape de l’échec : accès au pilote, interrogation directe, chargeur ICD, appareil reconnu, chargement du modèle, contexte ou génération.
- La raison correspondante, avec une explication française et un code stable.
- Le statut numérique OpenCL lorsqu’une interrogation l’a réellement produit.
- Les nombres mesurés de plateformes et de GPU ; une valeur non mesurée reste explicitement inconnue.
- La durée native associée au diagnostic et l’indication qu’une réponse de préparation antérieure a été réutilisée, lorsqu’elle l’a été.

Le rapport ne copie ni journal natif brut, ni chemin de fichier, ni message arbitraire du pilote. Le mode **CPU uniquement** ne doit pas ouvrir le pilote OpenCL. Le mode **GPU OpenCL uniquement — sans repli CPU** conserve un échec visible ; un repli CPU ne peut pas devenir un faux succès GPU. Le mode automatique conserve ses règles de repli explicite et de libération confirmée.

Le **premier appel comprend son chargement éventuel** : ne pas ajouter de nouveau le temps de chargement au total de cet appel. L’absence de mesure séparée signifie qu’aucune mesure de chargement réussi n’a été obtenue ; elle ne prouve pas qu’aucune tentative n’a eu lieu. La durée d’un mode inclut sa préparation, ses appels et sa libération.

## Essai à effectuer sur le téléphone

Pour reproduire l’essai avec cette version publiée :

1. Installer la mise à jour par-dessus l’édition Traduction actuelle, **sans la désinstaller**.
2. Conserver le GGUF TranslateGemma déjà importé et choisir **4 threads CPU** pour ce nouvel essai. Aucun nouveau téléchargement de modèle n’est nécessaire.
3. Ouvrir **Comparer CPU et GPU — essai local intégré** et lancer le comparatif sur le texte intégré.
4. Laisser terminer les deux passages CPU puis la tentative GPU ; l’annulation reste disponible.
5. Utiliser **Copier le rapport CPU / GPU** et transmettre le rapport complet, y compris l’identité Android et la raison d’échec éventuelle.

L’exemple est anglais vers français, avec un contexte personnel vide. Les deux modèles ne sont jamais chargés simultanément. Les originaux vidéo et sous-titres ne sont pas modifiés. Le lecteur et la vocalisation restent soumis à leurs protections de Pause, de déplacement, d’occurrences et de callbacks périmés.

Un diagnostic plus précis peut conduire à une correction ultérieure du chemin GPU. Il ne permet pas d’annoncer à l’avance laquelle sera nécessaire, ni si le GPU sera plus rapide que le CPU pour ce modèle. Aucun traitement soutenu écran éteint, aucune autonomie, qualité vocale ou compatibilité TalkBack réelle n’est déduit des essais automatisés.

## Diagnostic reçu après publication

Sur le OnePlus CPH2747 sous Android 17 / API 37, le rapport a identifié **OPENCL_ICD / ICD_ENTRY_POINT_MISSING**, alors qu’une plateforme était visible par l’accès direct au pilote. Le [guide de la candidate 0.5.7](OPENCL_DIRECT_057.md) décrit l’accès direct préparé pour ce blocage. Les **706 tests réussis de la 0.5.6**, dont 110 sous Android 17, restent attachés à cette version et ne valident pas la suivante.
