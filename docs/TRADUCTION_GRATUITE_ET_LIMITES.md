# Traduction gratuite et limites des services

[Accueil](../README.md) · [Documentation](README.md) · [Configuration des moteurs](GUIDE_MOTEURS_TRADUCTION.md) · [Guide Google](TRADUCTION_GOOGLE_052.md) · [Notes 0.5.2](releases/0.5.2.md)

Offres vérifiées le **27 octobre 2026** ; documentation des sources **0.5.2** actualisée le **28 octobre**. Contrôler les conditions affichées dans son compte. L’état de validation et de publication figure dans les [notes 0.5.2](releases/0.5.2.md) ; une offre externe et l’intégration d’un adaptateur ne prouvent pas sa disponibilité permanente.

## Quel service choisir pour une piste complète ?

Les sources 0.5.2 proposent **Google en ligne sans clé** pour éviter une configuration Cloud, **Google ML Kit** pour traduire sur le téléphone après téléchargement, et les moteurs précédents. **Azure Translator F0** reste une option avec quota documenté si la création d’une ressource convient. Le choix dépend des langues, de la configuration et de la disponibilité ; aucune API ne garantit une traduction parfaite.

| Service | Offre gratuite vérifiée | Disponibilité dans le lecteur |
| --- | --- | --- |
| Google en ligne sans clé | Accès web gratuit non officiel ; aucun quota illimité garanti | **Intégré en 0.5.2**, avec rythme modéré, reprise et limites visibles |
| Azure Translator F0 | 2 millions de caractères par mois, partagés entre les opérations concernées | Intégré ; compte Azure, ressource F0 et clé personnelle |
| Ancien DeepL API Free | 500 000 caractères par mois ; plus proposé aux nouvelles souscriptions | Intégré ; conserver le point d'accès correspondant à la clé |
| Nouveau DeepL API Developer | 1 million de caractères **au total**, sans renouvellement | L'accès dépend du point d'accès autorisé pour la clé ; aucun quota mensuel gratuit à supposer |
| Google Cloud Translation NMT | 500 000 caractères par mois, sous forme d'un crédit de 10 USD | **Non intégré** ; compte de facturation obligatoire, dépassements payants |
| Google ML Kit | SDK de traduction locale gratuit ; téléchargement préalable de ses modèles | **Intégré en 0.5.2** ; Wi-Fi par défaut, option mobile, langues vérifiées et attribution Google |
| Google Apps Script | `LanguageApp.translate()` ; 5 000 appels/jour pour un compte grand public, sous les autres limites du service | **Non intégré** ; nécessiterait un déploiement personnel et un contrôle d’accès |
| MyMemory | 5 000 caractères/jour sans identification ; 50 000 avec une adresse email valide | **Non intégré** ; quota et taille des segments peu adaptés à de longues pistes |
| LibreTranslate | Logiciel libre ; coût et quota selon le serveur | Intégré pour un serveur HTTPS personnel ; l'hébergement officiel géré est payant |

Une limite mensuelle de caractères et une limite de débit sont indépendantes.
Le volume gratuit disponible ne garantit donc pas l'acceptation de requêtes trop rapprochées.

## Configurer Azure F0 entièrement depuis Android

1. Ouvrir le [portail Azure](https://portal.azure.com/) dans le navigateur du téléphone
   et se connecter avec son compte Microsoft. Une souscription Azure active est
   nécessaire ; vérifier les conditions demandées à l'inscription.
2. Créer une ressource **Translator** pour la traduction de texte. Sélectionner
   explicitement **F0 — Free**, si disponible dans la souscription. Le palier **S1**
   est payant ; ne pas le choisir en supposant qu'il offre le même fonctionnement.
3. Ouvrir la ressource puis **Clés et point de terminaison / Keys and Endpoint**.
   Copier une clé et relever la région de la ressource.
4. Dans le lecteur, ouvrir **Traduire les sous-titres en français**, choisir
   **Microsoft Azure Translator**, puis saisir la clé et le code de région
   demandé, par exemple `francecentral`. Laisser la région vide seulement pour
   une clé globale qui le permet ; le nom du pays ne remplace pas ce code.
5. Sélectionner la langue originale des sous-titres, puis **Indexer la piste et
   estimer la traduction**. Contrôler le volume et confirmer la traduction.
   Pour attendre la piste entière, laisser la traduction progressive désactivée.
6. Attendre l'annonce de préparation, puis appuyer sur **Lire**. Les sous-titres
   sont envoyés à Microsoft ; cet adaptateur n'envoie ni la vidéo ni l'audio.

La saisie d'une clé ne crée pas de compte ou de ressource Azure. Ne jamais publier
la clé sur GitHub. Le lecteur propose une mémorisation chiffrée sur le téléphone.
Le [guide détaillé des moteurs](GUIDE_MOTEURS_TRADUCTION.md) décrit les champs et les langues.

## DeepL : pourquoi « limite atteinte » avec du quota restant ?

Le message seul ne suffit pas pour identifier la cause. Le code HTTP distingue :

- **429 ou 529** : limitation temporaire du débit ; attendre peut résoudre le problème.
- **456** : quota de période, plafond de coût ou limite propre à la clé atteint.
  Une limite de clé peut bloquer la traduction malgré un solde global disponible.
- **500, 502, 503 ou 504** : incident côté service ou passerelle ; reprise explicite.
- **401/403** : accès ou clé à vérifier ; **413** : requête trop volumineuse.

Le correctif 0.5.1 conserve les lots de **20 textes maximum** et les résultats
validés. Il espace les appels DeepL d'au moins **500 ms** et autorise au plus
**cinq nouvelles tentatives**, uniquement après **429/529** : attente de
**1, 2, 4, 8 puis 16 secondes**, avec un léger aléa. Un en-tête `Retry-After`
valide est respecté dans une limite de **120 secondes d'attente cumulée par lot**,
hors durée réseau : au-delà, arrêt sans raccourcir le délai demandé. L'attente est annulable.

Les erreurs **456, 500/502/503/504** ne sont pas relancées automatiquement. Une erreur
d'entrée/sortie, une connexion perdue ou une réponse réseau absente n'entraînent pas de
renvoi automatique : le serveur peut déjà avoir consommé les caractères sans
que le téléphone ait reçu le résultat. Après un échec, une reprise utilise les
lots validés conservés lorsque l'identité de la piste et les paramètres correspondent.
Un lot sans résultat confirmé peut néanmoins être facturé de nouveau à la reprise.
Les données conservées dépendent du stockage privé disponible et ne constituent pas un export durable.

## Google en ligne : accès non officiel intégré

Le choix Google en ligne utilise un adaptateur Kotlin natif. Il ne demande pas de clé Cloud et n’utilise ni Python embarqué, ni extraction de page HTML, ni serveur Lingva imposé. Le [guide Google](TRADUCTION_GOOGLE_052.md) présente les projets examinés et les compromis retenus.

Les appels sont espacés de **1 000 ms par défaut**, avec un réglage de **500 à 10 000 ms**. Le travail sérialise les appels ; certaines réponses temporaires, dont 429 et les erreurs serveur prises en charge, autorisent au plus **trois nouvelles tentatives**, dans un budget d’attente cumulé de **120 secondes par requête**, hors réseau. `Retry-After` est respecté ; un délai trop long entraîne une suspension sans requête anticipée. Les refus ne déclenchent aucune rotation d’IP ou multiplication de points d’accès.

Le cache et la progression évitent de retraduire les résultats validés. Une traduction identique peut être réutilisée en mémoire, mais les occurrences répétées restent toutes présentes et vocalisées. Aucun rythme ne garantit que Google acceptera toute une piste ; conserver la possibilité d’attendre et de reprendre reste nécessaire.

## Google local : ML Kit intégré

ML Kit traduit sur le téléphone après téléchargement des modèles nécessaires. Le Wi-Fi est requis par défaut ; l’utilisateur peut autoriser explicitement les données mobiles. Les langues sont contrôlées par le SDK et les traductions attribuées à Google.

Les modèles sont ceux du mode hors ligne Google Traduction. Entre deux langues non anglaises, le pivot par l’anglais peut affecter la fidélité. Il faut évaluer la paire et l’œuvre ; aucune mesure sur OnePlus 15 n’est déduite de cette intégration. L’initialisation et les téléchargements sont soumis à une confirmation explicite dans le lecteur. Les textes de traduction restent sur le téléphone ; le SDK collecte néanmoins une télémétrie technique (appareil, application, performances, tailles, langues et événements), décrite dans la [déclaration Google](https://developers.google.com/ml-kit/android-data-disclosure). Un traitement local ne signifie donc pas absence de toute activité réseau.

## Autres possibilités étudiées, non intégrées

**Google Cloud Translation** est une API officielle distincte : le crédit NMT mensuel couvre 500 000 caractères collectivement entre Basic et Advanced. La facturation doit être activée ; le crédit n’est pas reportable et les dépassements sont payants. Cette option n’est pas un bouton caché du moteur Google sans clé.

**Google Apps Script** propose officiellement `LanguageApp.translate()`, avec un quota publié de **5 000 appels par jour** pour un compte grand public et une limite d’exécution de **six minutes**. Un service personnel pourrait être déployé, mais il demanderait sa configuration et son contrôle d’accès. Il n’est pas intégré et ne constitue pas un accès illimité partagé par les utilisateurs du lecteur.

**MyMemory** limite chaque texte à **500 octets UTF-8** ; ses quotas rendent cette piste surtout intéressante pour de courts contenus. **LibreTranslate**, déjà intégré, peut être hébergé librement, mais le serveur doit exister et rester disponible ; une instance publique gratuite n’offre pas de garantie universelle.

## Références

- DeepL : [erreurs et reprises](https://developers.deepl.com/docs/best-practices/error-handling), [limites de taille](https://developers.deepl.com/docs/resources/usage-limits), [offres API](https://support.deepl.com/hc/en-us/articles/360021200939-DeepL-API-plans).
- Microsoft : [tarifs F0](https://azure.microsoft.com/en-us/pricing/details/translator/), [création de ressource](https://learn.microsoft.com/en-us/azure/ai-services/translator/how-to-create-translator-resource), [limites de débit](https://learn.microsoft.com/en-us/azure/ai-services/translator/service-limits).
- Google : [tarifs Cloud Translation](https://cloud.google.com/products/translate/pricing), [activation et facturation](https://docs.cloud.google.com/translate/docs/setup), [modèles et limites ML Kit](https://developers.google.com/ml-kit/language/translation).
- MyMemory : [quotas](https://mymemory.translated.net/doc/usagelimits.php), [spécification API](https://mymemory.translated.net/doc/spec.php). LibreTranslate : [logiciel et hébergement](https://docs.libretranslate.com/).
- Apps Script : [fonction LanguageApp](https://developers.google.com/apps-script/reference/language/language-app), [quotas](https://developers.google.com/apps-script/guides/services/quotas), [applications web](https://developers.google.com/apps-script/guides/web).
- ML Kit : [SDK Android](https://developers.google.com/ml-kit/language/translation/android), [attribution](https://developers.google.com/ml-kit/language/translation/translation-terms), [conditions](https://developers.google.com/ml-kit/terms).
- Interface Google non officielle : [avertissements du mainteneur Googletrans](https://py-googletrans.readthedocs.io/en/latest/) ; cette source décrit la bibliothèque, pas un engagement de Google.
