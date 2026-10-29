# Traduction locale — contexte et cache 0.5.9

[Accueil](../README.md) · [Documentation](README.md) · [Comparatif CPU/GPU](COMPARATIF_LOCAL_058.md) · [Notes 0.5.9](releases/0.5.9-local.md) · [Validation](../TESTING.md)

La préversion publiée **0.5.9-local / code 16** corrige le format de traduction, la conservation du contexte récent et la réutilisation du cache natif. Le modèle reste choisi explicitement sur le téléphone. Ses poids, sa quantification, les noyaux de calcul et la taille du contexte natif ne changent pas.

Les corrections portent sur l’intégration du modèle dans l’application. Elles ne prouvent pas que toute traduction est fidèle ni que chaque sous-titre sera traduit plus vite. Une réponse terminée reste une validation de structure, pas une certification linguistique.

## Texte courant et contexte récent

Le texte de l’occurrence à traduire est conservé intégralement. Les identifiants, minutages, dialogues, panneaux, chevauchements et répétitions légitimes restent gérés par l’application ; le modèle n’a pas à les recréer.

L’historique contient au plus deux paires source/traduction terminées. La version précédente coupait chaque membre de ces paires à 300 points de code. Cela pouvait supprimer une proposition ou une négation d’un seul côté et fabriquer un exemple incomplet. La 0.5.9 transmet des **paires entières**. Si l’entrée dépasse la capacité du modèle, elle retire d’abord la paire historique la plus ancienne entière. Elle ne raccourcit pas le sous-titre courant pour conserver l’historique.

Une occurrence trop longue conserve le mécanisme de subdivision : chaque caractère de la source appartient à un fragment, les traductions complètes sont réunies, et une sortie interrompue ou limitée ne devient pas une occurrence terminée. Une impossibilité de traduction produit une erreur perceptible plutôt qu’une suppression silencieuse.

## Format TranslateGemma

Sans contexte personnel, le rendu texte suit le [template TranslateGemma épinglé avec llama.cpp](https://github.com/ggml-org/llama.cpp/blob/8ca4060b7e30e76baaa5670ee1ec1b760b7f8552/models/templates/google-translategemma-4b-it.jinja), notamment les **trois caractères de nouvelle ligne** entre le deux-points final de l’instruction et la source. Les tours précédents alternent les rôles utilisateur et modèle.

Le champ de contexte personnel constitue une **adaptation manuelle de l’application**, pas un champ supplémentaire du format officiel Google. Lorsqu’il est renseigné, la même adaptation est utilisée pour les tours précédents et le tour courant. L’application évite ainsi de reconstruire un ancien tour avec des instructions différentes. L’utilité et la fidélité de cette adaptation demandent une évaluation réelle avec le modèle choisi.

Le [protocole officiel Google](https://huggingface.co/google/translategemma-4b-it) demande un format spécifique et distingue ses usages pris en charge des autres techniques de consigne. Le profil Hy-MT reste distinct, avec sa structure de traduction et de contexte propres.

Des chaînes réservées reconnues par les modèles ne doivent pas devenir des marqueurs de conversation à l’intérieur d’un sous-titre ou d’un contexte. La garde est renforcée et refuse visiblement les entrées concernées. Elle n’efface ni ne remplace ces caractères pour produire une prétendue traduction complète. Cette garde ne garantit pas qu’un modèle résiste à toute instruction exprimée en langage naturel.

## Cache natif exact

Le cache réutilise uniquement un **préfixe de tokens strictement identique** et encore disponible dans la mémoire du moteur. La 0.5.9 conserve aussi les tokens de sortie réellement décodés avec succès. Lorsqu’une traduction terminée revient dans le début du prochain historique, ces calculs peuvent être réutilisés.

Cette optimisation ne retokenise pas approximativement une sortie pour reconstruire la mémoire. Elle ne déplace pas arbitrairement les états du modèle et ne suppose pas qu’un même texte possède toujours le même contexte. Au moins un token de l’entrée est recalculé pour disposer des logits finaux. Les gardes de disponibilité de la mémoire, d’erreur et d’annulation restent requises.

Lorsque les deux paires d’historique glissent, le début de la consigne change et une grande partie du préfixe peut légitimement cesser d’être réutilisable. Le gain dépend donc du texte et de son historique ; il n’est pas un facteur d’accélération garanti pour une série entière.

## Résultats conservés et nouvelle identité

Les nouvelles tâches GGUF utilisent une identité de configuration **v3**. Une traduction préparée avec l’ancien format ne doit pas être présentée comme si elle avait été calculée avec les nouvelles instructions.

La mise à jour ne supprime pas les anciens résultats conservés. Une ancienne sélection peut toujours relire son résultat disponible sous son identité d’origine. Pour mesurer le nouveau moteur, démarrer une nouvelle traduction avec la configuration actuelle. Le modèle déjà importé peut être conservé ; aucun nouveau téléchargement de poids n’est nécessaire pour tester cette version.

## Chargement, GPU et libération

Le modèle est déjà chargé une seule fois par tâche et réutilisé entre ses occurrences. La version ne garde pas un modèle supplémentaire en mémoire entre tâches indépendantes.

La fermeture demandée depuis l’interface reste asynchrone. Les tâches de traduction locales attendent sur leur worker un accusé de libération natif borné avant d’annoncer la fin du travail. Un échec de libération reste perceptible et ne garantit pas que la ressource soit à nouveau disponible ; il ne justifie pas un chargement simultané d’un second modèle.

Le choix **Automatique** conserve ses règles de disponibilité et de réserve mémoire. Une réussite dans le comparatif **GPU OpenCL strict** ne garantit pas qu’Automatique choisira le GPU dans d’autres conditions. Lire le moteur effectivement sélectionné et le motif d’un éventuel repli dans le rapport. La réserve mémoire n’est pas diminuée arbitrairement pour forcer un GPU.

Le CPU reste disponible et son démarrage ne dépend pas du pilote GPU. Aucun NPU ni OCR de film n’est intégré. L’OCR et la traduction restent des opérations indépendantes dans le projet.

## Essai sur le téléphone

Après distribution vérifiée, installer la mise à jour par-dessus l’édition Traduction actuelle, sans désinstaller. Garder d’abord le même GGUF et le même nombre de threads pour comparer les versions.

1. Ouvrir le [comparatif étendu](COMPARATIF_LOCAL_058.md), choisir le même ordre CPU/GPU que pour le rapport de référence et conserver les mêmes options de réouverture.
2. Lancer **Comparer CPU et GPU sur 32 sous-titres intégrés**. Les 32 occurrences et leurs minutages synthétiques n’ont pas été remplacés pour favoriser les correctifs.
3. Copier le rapport complet. Comparer les sorties, le nombre d’occurrences terminées, les tokens calculés et réutilisés, les durées d’entrée et de génération, ainsi que le chargement. Des sorties différentes peuvent aussi modifier le contexte des occurrences suivantes.
4. Évaluer ensuite une nouvelle traduction d’un SRT synthétique. Contrôler notamment les nombres, négations, noms, pronoms, conditions, indications de lieu et panneaux ; une phrase fluide peut néanmoins changer le sens.
5. Pour l’usage simultané, refaire un essai avec lecture et voix, puis vérifier Pause, déplacement et changement de média. Les mesures avec vidéo en pause ne valident pas la fluidité ni la vocalisation pendant le calcul.

Un gain mesuré sans perte de structure ne démontre pas à lui seul la fidélité d’un film. Les relevés RAM sont ponctuels, la mémoire résidente ne comptabilise pas toutes les allocations GPU, et la température de batterie ne mesure pas celle du CPU ou du GPU. Les preuves propres à la version figurent dans [TESTING.md](../TESTING.md) et ses [notes](releases/0.5.9-local.md).
