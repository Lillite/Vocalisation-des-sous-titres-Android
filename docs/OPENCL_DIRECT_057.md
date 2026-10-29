# Accès OpenCL direct — préversion publiée 0.5.7

[Accueil](../README.md) · [Notes de version](releases/0.5.7-directcl.md) · [Validation](../TESTING.md) · [Diagnostic publié 0.5.6](DIAGNOSTIC_OPENCL_056.md)

La préversion publiée **0.5.7-directcl / code 14** apporte un accès direct facultatif au pilote OpenCL déjà fourni par le téléphone. Elle conserve le modèle importé, ses poids, sa quantification et les consignes de traduction. Le CPU reste indépendant du pilote graphique.

## Résultat publié et essai reçu

La [0.5.7 publiée](releases/0.5.7-directcl.md) a réussi 708 tests, puis la reconstruction, la signature et le téléchargement public vérifiés. Son rapport reçu du OnePlus le 29 octobre 2026 confirme le parcours direct et du calcul sur **Adreno 840** : 14 898 puis 14 832 nœuds GPU, générations à **20,09 et 20,18 tokens/s**, contre **9,49 et 9,30** sur CPU. Le chargement GPU reste plus long : **34,254 s**, contre **10,264 s**. Le court essai répète le même texte et ne prédit pas un film entier.

Le [comparatif étendu préparé en 0.5.8](COMPARATIF_LOCAL_058.md) doit ensuite distinguer les phases du chargement et traiter des occurrences variées. Les sections suivantes conservent le diagnostic initial qui a motivé la liaison directe ; elles ne demandent pas de répéter une validation déjà terminée.

## Ce que le rapport initial de la 0.5.6 établissait

Le rapport de la **0.5.6-opencl**, exécutée sur **OnePlus CPH2747, Android 17 / API 37, arm64-v8a**, a confirmé deux réponses CPU complètes avec quatre threads. Le modèle de 2 489 909 760 octets et le texte synthétique étaient identiques aux essais précédents. Le mode CPU a pris **17,943 s**, avec **12,41 puis 11,84 tokens par seconde** pour la génération, et **92 tokens d’entrée sur 93 réutilisés** au deuxième passage.

La tentative GPU a échoué à l’étape **OPENCL_ICD**, avec la raison **ICD_ENTRY_POINT_MISSING**. Le pilote directement interrogé exposait **une plateforme** ; la durée native avant ce diagnostic était **127 ms**. Cela établit l’accès à une plateforme du pilote et le manque de la fonction attendue par le chargeur ICD. Cela ne démontre ni un calcul GPU, ni une incompatibilité générale du téléphone, ni un manque de RAM.

Ces durées ponctuelles ne constituent pas une comparaison contrôlée répétée avec les essais précédents. Elles ne permettent pas d’attribuer un gain à Android 17, ni d’établir un optimum universel de threads ou la qualité d’une traduction de film.

## Le changement préparé

Le moteur ne passe plus par le chargeur Khronos ICD pour joindre le pilote du téléphone. Une liaison directe propre à l’application résout les fonctions OpenCL nécessaires dans la bibliothèque du système. Elle est préparée seulement lorsqu’un mode de calcul demande OpenCL ; le mode CPU seul ne doit ni charger ni sonder ce pilote.

Les fonctions requises absentes provoquent un diagnostic borné. Les fonctions optionnelles ne doivent pas rendre inutilisable un pilote qui satisfait les besoins réellement utilisés. La raison et, lorsqu’elle est identifiée, la fonction standard manquante sont limitées aux valeurs connues de l’application ; aucun journal brut ou chemin privé n’est ajouté au rapport.

Les noyaux de calcul et leurs sources épinglées restent identiques. Aucun pilote du fabricant, modèle de production ou nouveau service réseau n’est distribué. L’extension ICD absente n’est plus une condition de ce parcours direct ; le chargement du modèle et l’exécution des opérations restent à vérifier.

## Lire le prochain essai

L’accès **direct** décrit la liaison au pilote. Le moteur sélectionné, les couches déportées et le travail GPU effectivement observé sont des mesures distinctes. Un chargement réussi ou une plateforme visible ne suffisent pas à annoncer une accélération. Le mode GPU strict conserve un échec perceptible si le calcul GPU n’est pas établi ; un repli CPU demeure explicitement signalé.

Le premier appel et la durée du mode comprennent déjà le chargement éventuel. La seconde entrée identique peut profiter du préfixe en mémoire, sans prédire le débit d’un film comportant des sous-titres différents.

Après publication et vérification de l’APK :

1. Installer la mise à jour **par-dessus l’édition Traduction actuelle, sans désinstaller**.
2. Conserver le modèle déjà importé et choisir **4 threads CPU** pour cet essai.
3. Ouvrir **Comparer CPU et GPU — essai local intégré**.
4. Lancer le comparatif et laisser terminer les deux passages CPU, puis la tentative GPU.
5. Utiliser **Copier le rapport CPU / GPU** et transmettre le rapport complet, avec l’identité Android, les mesures et l’éventuelle erreur.

Le fonctionnement réel du pilote du OnePlus et le gain éventuel doivent être mesurés sur le téléphone. Les essais synthétiques hôte et émulateur valident leurs parcours respectifs ; ils ne mesurent ni qualité linguistique, chauffe prolongée, autonomie ou TalkBack réel. La lecture et la vocalisation conservent leurs règles de Pause, de déplacement et de traitement de chaque occurrence. Le NPU et l’OCR restent hors de cette étape.
