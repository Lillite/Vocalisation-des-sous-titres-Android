# Traduction GGUF locale — candidate 0.5.5 performances

[Accueil](../README.md) · [Notes de version](releases/0.5.5-perf.md) · [Validation](../TESTING.md) · [Parcours publié 0.5.4](TRADUCTION_GGUF_054.md)

La candidate **0.5.5-perf / code 12** prépare l’optimisation de la traduction locale existante. Le fichier GGUF, le SRT et les calculs restent sur le téléphone. Aucun passage par PocketPal, serveur ou compte n’est nécessaire ; aucun poids n’est inclus ou téléchargé automatiquement. La traduction textuelle fonctionne indépendamment de l’OCR, qui n’est pas ajouté par cette étape.

## Conserver le parcours de traduction

L’import privé, la préparation du modèle déjà importé, le SRT autonome et son exemple intégré de huit occurrences restent décrits dans le [guide 0.5.4](TRADUCTION_GGUF_054.md). Il n’est pas nécessaire de télécharger de nouveau un GGUF déjà accessible au sélecteur Android. Un modèle stocké uniquement dans l’espace privé de PocketPal doit être rendu accessible par une méthode permise par cette application.

Une traduction validée conserve chaque identifiant, minutage, chevauchement et répétition légitime. Le code écrit le SRT ; le modèle produit uniquement le texte traduit. Le calcul du modèle n’a pas le droit de fusionner des occurrences pour gagner du temps. Une limite de sortie, une erreur ou une annulation ne devient pas une traduction complète.

La reprise conserve les occurrences déjà validées pour la même source, le même modèle et les mêmes paramètres compatibles. L’export complet en SRT ou VTT crée un nouveau fichier UTF-8, utilisable avec un lecteur vidéo ou un outil MKV compatible. L’application ne remuxe pas la vidéo.

Le lecteur peut utiliser un préfixe validé avec les protections du mode progressif. S’il rattrape la traduction, il attend avant le texte manquant. Une Pause volontaire, un déplacement ou un contexte périmé empêche une reprise tardive. La fin du calcul ne donne jamais une autorisation générale de relancer la vidéo.

## Comprendre les optimisations

Le modèle était déjà chargé une fois par tâche et utilisait une lecture mémoire mappée en 0.5.4. Cette candidate travaille sur les calculs répétés et les backends de calcul ; elle ne présente pas ces propriétés existantes comme de nouveaux gains.

| Mécanisme | Ce qu’il peut réduire | Ce qu’il ne prouve pas |
| --- | --- | --- |
| Optimisations CPU ARM64 avec KleidiAI | Coût de certaines opérations compatibles sur les appareils concernés | Utilisation du GPU ou du NPU, gain identique pour tous les modèles |
| Réutilisation exacte du préfixe de tokens | Recalcul de la partie strictement identique d’une nouvelle consigne | Réutilisation approximative d’une traduction, suppression d’une occurrence |
| Backend GPU facultatif | Déport de certaines opérations lorsque le pilote et le modèle le permettent | Déport de toutes les couches, débit supérieur ou meilleure qualité |

Le cache de préfixe appartient à une session de calcul et aux tokens réellement compatibles. Il reste distinct du cache de traductions terminées et des occurrences sauvegardées sur disque. Un changement de modèle ou de paramètres, une erreur ou une interruption exige d’invalider les états incompatibles. Les tests doivent distinguer la réutilisation effective de sa simple activation.

L’identité de traduction passe à **GGUF v2** et inclut le profil ainsi que le mode demandé, automatique, CPU ou OpenCL. Les anciens résultats ne sont pas mélangés aux nouveaux calculs. Le contexte des deux dialogues précédents reste conservé : l’optimisation native ne raccourcit pas la consigne pour gagner du temps.

## Choisir le calcul et le profil

Le sélecteur **Exécution du modèle GGUF** propose :

| Choix | Comportement demandé |
| --- | --- |
| **Automatique (recommandé)** | Essaie OpenCL lorsque les conditions le permettent ; un repli CPU affiche sa raison. |
| **CPU uniquement** | Garde le calcul sur le processeur, y compris les optimisations ARM64 compatibles. |
| **GPU OpenCL uniquement — sans repli CPU** | Demande OpenCL ; une indisponibilité ou un échec reste visible au lieu de produire silencieusement une mesure CPU. |

Le mode automatique utilise notamment un budget mémoire prudent : poids du modèle plus environ **1 Gio**, comparés à la mémoire disponible. Un refus lié à ce budget est une **estimation préalable**, pas la preuve d’un échec réel par manque de mémoire. Le mode GPU seul tente le chemin OpenCL disponible ; cette étape n’effectue pas de recherche automatique du nombre optimal de couches ni d’optimisation partielle garantie.

En cas d’échec du chemin GPU, le mode automatique peut tenter **une seule reprise CPU**, avec une raison visible et après libération confirmée du calcul précédent. Les choix CPU et GPU explicites ne provoquent pas de changement de backend masqué. La progression continue de conserver chaque occurrence validée.

OpenCL et KleidiAI sont préparés pour la version **ARM64**. Le chemin **x86_64 d’émulateur reste CPU**, sans ces accélérations. La bibliothèque OpenCL du téléphone est facultative ; son absence doit laisser l’application installable et le mode CPU disponible.

Le sélecteur **Profil de traduction du modèle GGUF** propose **Automatique (recommandé)**, **TranslateGemma** et **Hy-MT / Hy-MT2**. Un choix explicite adapte la consigne au modèle ; il ne transforme pas ses poids et ne rend pas une architecture incompatible utilisable.

## Reconnaissance du GGUF Hy-MT2

Le GGUF officiel Hy-MT2-7B peut déclarer l’architecture `hunyuan-dense` et un nom interne tel que `Global_Step_560`, au lieu d’un nom contenant « Hy-MT2 ». La 0.5.4 pouvait le refuser à cause de cette reconnaissance de profil ; le fichier n’était pas nécessairement absent ou déplacé.

Le profil automatique reconnaît notamment les fichiers officiels Hy-MT2-7B Q8, Q6 et Q4 par leur **empreinte de contenu publique exacte et l’architecture `hunyuan-dense`**. Le seul nom `Global_Step_560` ne suffit pas. Les noms spécialisés, métadonnées de famille et consignes TranslateGemma reconnues peuvent également établir le profil ; tout modèle Hunyuan n’est pas accepté indistinctement.

Si la famille reste ambiguë, le choix explicite du profil permet de préciser la demande, sous réserve d’une architecture compatible : `gemma3` pour TranslateGemma, `hunyuan-dense` pour Hy-MT. Le nom du fichier et sa taille approximative ne suffisent pas à identifier le modèle. Un fichier incompatible ou ambigu doit produire une erreur perceptible et distincte d’un problème d’accès, d’une copie périmée, d’un manque de mémoire effectivement établi ou d’un backend indisponible. La lecture bornée des métadonnées ne valide pas à elle seule tous les poids ni leur qualité.

## Mesurer sur le téléphone

La présence d’un GPU dans l’inventaire Android, d’une bibliothèque native dans l’APK ou d’une option GPU ne prouve pas qu’un calcul y a été exécuté. Le rapport sépare le backend demandé, le backend effectivement choisi et le travail réellement déporté. La preuve GPU exige des **nœuds de calcul effectivement exécutés sur ce backend** ; une couche chargée en mémoire GPU ne suffit pas.

Le **Comparatif local CPU / GPU** utilise uniquement le modèle déjà importé et un court texte anglais intégré :

1. Préparer le modèle local et choisir son profil, puis ouvrir **Comparer CPU et GPU — essai local intégré**, dans la bibliothèque ou les réglages de traduction.
2. Choisir les threads CPU demandés, de **1 à 8**, défaut **4**.
3. Appuyer sur **Comparer CPU puis GPU sur le texte intégré**.
4. Consulter les deux passages CPU puis les deux passages GPU stricts. Les modèles ne sont jamais chargés simultanément. Le second passage identique permet d’observer la réutilisation éventuelle du préfixe.
5. Utiliser **Copier le rapport CPU / GPU**, ou **Annuler l’essai comparatif CPU / GPU** pour interrompre le calcul.

Ce comparatif ne lit pas de vidéo ou de sous-titre personnel. Ses sorties sont présentées pour examen humain : une sortie identique sur CPU et GPU ne prouve pas une traduction fidèle. Un GPU indisponible ou sans travail effectivement observé n’est pas présenté comme un passage GPU validé. L’ordre fixe CPU puis GPU, le cache système et la chauffe influencent les mesures ; le rapport n’est pas un classement universel des backends.

**Fermer le panneau et laisser l’essai continuer** garde le comparatif actif. La destruction de l’activité l’annule ; conserver l’application ouverte. Le rapport peut inclure mémoire résidente du processus de calcul et son pic, RAM et état thermique ponctuels, ainsi que des relevés de batterie avant et après. La température de batterie n’est pas la température du processeur, et ces courts relevés ne mesurent pas l’autonomie d’un film.

Comparer une seule variable à la fois avec **le même GGUF et sa quantification, le même SRT, les mêmes langues et le même contexte**. Conserver séparément le temps de chargement, le calcul de la consigne, la génération et les tokens effectivement réutilisés. Contrôler aussi la mémoire disponible, l’état thermique et la fluidité de la vidéo et de la voix pendant le calcul.

Pendant une traduction, les compteurs de consigne, génération, tokens et débit concernent le **dernier appel natif**, qui peut être un fragment d’occurrence. Le temps total de l’occurrence inclut ses tentatives. Ces valeurs ne sont pas un débit agrégé du film. Le court texte fixe du comparatif n’a pas besoin de cette fragmentation.

Un nombre de threads supérieur ne garantit pas une meilleure vitesse. Une première génération après chargement ne se compare pas directement avec un calcul réutilisant un préfixe. Les résultats miniatures de CI vérifient un comportement technique ; ils ne sont pas une mesure du débit ou de la fidélité des traductions sur le téléphone.

Le GPU peut être indisponible, manquer de mémoire ou ne pas exécuter certaines opérations du modèle. Son gain dépend du pilote, du modèle et de la quantification. Aucune accélération chiffrée n’est promise. Le **NPU n’est pas intégré**. La chauffe, l’autonomie, les longues traductions, la qualité linguistique et TalkBack réel restent à évaluer séparément.

## Confidentialité et livraison

Les nouveaux essais restent locaux et ne demandent ni envoi de texte ni téléchargement de poids. Les rapports ne doivent pas contenir le chemin privé du modèle, son nom de fichier personnel ou les sous-titres d’une œuvre. Les tests automatisés utilisent des données synthétiques.

llama.cpp reste épinglé à la révision `8ca4060b7e30e76baaa5670ee1ec1b760b7f8552` ; les bibliothèques et modèles conservent leurs propres auteurs et conditions. Les [attributions](../ATTRIBUTIONS.md) donnent aussi les versions et textes de licence OpenCL et KleidiAI conservés dans l’APK. La [validation](../TESTING.md) doit attribuer compilation, JVM et instrumentation au commit exact. La distribution reste soumise à la reconstruction après fusion, à la comparaison du contenu testé, au certificat durable et au téléchargement public vérifié. La 0.5.2 reste la dernière stable pendant la préparation de cette préversion.
