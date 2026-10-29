# Comparatif GGUF étendu — introduit en 0.5.8

[Accueil](../README.md) · [Notes de version](releases/0.5.8-benchmark.md) · [Validation](../TESTING.md) · [Accès OpenCL direct publié](OPENCL_DIRECT_057.md)

La préversion publiée **0.5.8-benchmark / code 15** introduit un comparatif CPU/GPU sur une série de sous-titres synthétiques variés. Le modèle déjà importé reste la référence ; ses poids, sa quantification et les noyaux de calcul ne sont pas changés pour améliorer les chiffres.

La candidate [0.5.9-local](TRADUCTION_LOCALE_059.md) conserve ce corpus et ces commandes ; ses corrections de format et de cache demandent leurs propres mesures.

## Pourquoi une série variée

Le rapport de la **0.5.7-directcl** reçu du OnePlus CPH2747, sous Android 17 / API 37, a confirmé le calcul OpenCL direct sur **Qualcomm Adreno 840** : 14 898 puis 14 832 nœuds GPU observés. Les générations atteignaient **20,09 et 20,18 tokens/s**, contre **9,49 et 9,30 tokens/s** sur CPU. Le chargement GPU prenait **34,254 s**, contre **10,264 s** sur CPU. Le deuxième appel complet prenait **1,212 s** sur GPU et **2,601 s** sur CPU.

Il s’agissait toutefois du même court texte anglais répété : 92 tokens d’entrée sur 93 étaient réutilisés au deuxième passage. Le total du mode, chargement et libération inclus, restait plus long sur GPU : **41,411 s**, contre **22,252 s**. Ces observations ne prédisent ni le temps d’un épisode entier, ni sa qualité linguistique, ni l’autonomie.

## Série et limites

L’essai étendu emploie **32 occurrences intégrées**, dont **29 textes distincts** : dialogues, panneaux, nombres, noms propres, phrases plus longues et répétitions légitimes. Chaque occurrence garde son identifiant et ses minutages synthétiques. Réutiliser un texte ou un préfixe ne supprime jamais son occurrence.

Les deux modes reçoivent le même corpus dans le même ordre, les mêmes poids, profil, langues et limites de génération. Le contexte personnel reste vide. Lorsque le moteur contextuel est disponible, le contexte récent est constitué uniquement des occurrences synthétiques précédentes et des sorties du même mode. Des sorties antérieures différentes peuvent donc produire des entrées contextuelles différentes : cette divergence doit être examinée avant d’attribuer tout écart de temps au matériel.

Le modèle reste chargé pendant une série. Une nouvelle session n’est ouverte qu’après confirmation de la libération de la précédente. Un refus, une annulation ou une sortie incomplète conserve seulement les occurrences terminées ; il ne transforme pas le reste de la série en réussite.

Une sortie synthétique dépassant la borne de **2 048 octets UTF-8 par occurrence** fait échouer cette occurrence entière ; elle n’est pas tronquée pour être déclarée complète. Cette borne appartient au comparatif et ne réduit pas les limites de traduction de production. Le presse-papiers applique aussi une garde à la taille du rapport complet et signale un refus de copie.

Aucune sortie n’est automatiquement déclarée fidèle parce qu’elle est non vide ou identique à celle de l’autre mode. Les textes source et les sorties synthétiques permettent une comparaison humaine. La série ne lit aucun sous-titre personnel et ne modifie aucun fichier vidéo.

## Lancer depuis le téléphone

Après publication et vérification de l’APK :

1. Installer la mise à jour **par-dessus l’édition Traduction actuelle, sans désinstaller**.
2. Ouvrir **Comparer CPU et GPU — essai local intégré** et conserver le modèle déjà importé. Pour la continuité avec le rapport reçu, commencer avec **4 threads CPU** ; ce réglage ne constitue pas un optimum universel.
3. Choisir l’**Ordre du comparatif étendu** : **CPU puis GPU OpenCL** ou **GPU OpenCL puis CPU**. Alterner cet ordre entre des essais comparables aide à voir l’influence de la chauffe et du cache système ; cela ne les élimine pas.
4. Facultativement, cocher **Recharger le modèle après chaque série pour mesurer une réouverture**. Après chaque série terminée et libérée, le même mode est rouvert et traite seulement la première occurrence. Les caches ne sont pas effacés : il ne s’agit pas d’un démarrage à froid garanti.
5. Appuyer sur **Comparer CPU et GPU sur 32 sous-titres intégrés**. La durée dépend du modèle et peut atteindre plusieurs minutes. L’annulation reste disponible.
6. Attendre la fin, puis utiliser **Copier le rapport CPU / GPU** et transmettre le rapport complet, y compris les occurrences ou erreurs conservées.

L’ancien bouton **Comparer CPU puis GPU sur le texte intégré** conserve l’essai court à deux passages identiques. Il permet de retrouver la mesure historique ; ses résultats doivent être distingués de ceux de la série étendue.

## Lire les mesures

Le rapport sépare les durées d’appel, celles de la série et les mesures natives. Les appels complets comprennent déjà leur chargement éventuel : il ne faut pas ajouter une deuxième fois la durée de chargement au total.

Le profil natif de chargement distingue :

- initialisation du moteur ;
- préparation du parcours OpenCL lorsqu’elle a lieu ;
- chargement du modèle ;
- création du contexte ;
- durée totale de l’ouverture et nombre de tentatives.

Ces phases regroupent plusieurs opérations internes. La durée du chargement du modèle ne sépare pas la lecture des poids, les allocations, transferts, transformations ou compilations éventuellement réalisées par le moteur et le pilote. Une phase mesurée à 0 ms peut être plus courte que la résolution de l’horloge ; une mesure absente reste indisponible.

Les mesures d’entrée et de génération sont agrégées sur les appels natifs terminés et mesurés reçus pour chaque occurrence. Les tentatives natives échouées sans compteurs transmis restent incluses seulement dans le temps complet de l’occurrence. Les latences de session distinguent le premier appel avec chargement des suivants ; la médiane, le 95e centile et le maximum dépendent de l’échantillon effectivement terminé. Le débit de génération, calculé à partir des tokens et du temps natif de génération, n’est pas le débit global de traduction avec chargement, traitement de l’entrée et autres surcoûts.

La sélection OpenCL et l’accès direct ne suffisent pas : les passages GPU exigent un calcul réellement observé. Les nœuds observés ne mesurent pas un pourcentage d’occupation du GPU. La mémoire résidente du processus ne représente pas toutes les allocations du GPU ; son pic depuis le démarrage ne constitue pas un pic isolé par mode. La température de batterie n’est pas celle du CPU ou du GPU.

## Observer le lecteur pendant le calcul

Le bouton **Fermer le panneau et laisser l’essai continuer** permet d’utiliser le lecteur pendant le calcul. Le comparatif observe des états ponctuels : présence d’un média, état de lecture ou d’attente, position et compteurs disponibles. Il ne transmet ni titre, URI, chemin ni contenu de sous-titres personnels dans le rapport. La position reste une métadonnée de lecture.

Ces observations ne commandent ni Lire, ni Pause, ni déplacement ou vocalisation. Une pause demandée par l’utilisateur reste prioritaire. Les observations de plus de deux secondes, ou provenant de médias différents, ne doivent pas être présentées comme une mesure continue comparable. Le compteur d’images abandonnées est un cumul de session du lecteur : son delta ne constitue pas une attribution exacte au média courant, à la traduction ou au GPU et n’explique pas à lui seul sa cause.

Comparer d’abord sans lecture, puis avec la même vidéo et les mêmes réglages. Le déplacement naturel des positions entre modes, les interactions de l’utilisateur, les voix externes et l’activité d’autres applications limitent la comparaison. Le comparatif ne prouve ni vocalisation intégrale d’un film, ni écoute réelle, ni TalkBack, ni absence de saccades pendant toute sa durée. Les règles de narration restent vérifiées par leurs régressions propres et par des essais manuels distincts.

## Validation et étape suivante

La publication exige la validation de l’APK de la proposition exacte avant fusion, puis une reconstruction de même contenu applicatif et provenance après fusion, sans rejouer les suites réussies. Les effectifs exacts et résultats appartiennent aux rapports du run concerné ; aucun résultat futur n’est présumé.

L’étape suivante pourra comparer un moteur NPU facultatif sur une référence GPU ainsi documentée. **Aucun calcul NPU ni OCR de film n’est intégré à ce comparatif.** La traduction et le futur OCR conservent des responsabilités indépendantes. Le choix définitif du meilleur modèle reste ouvert.
