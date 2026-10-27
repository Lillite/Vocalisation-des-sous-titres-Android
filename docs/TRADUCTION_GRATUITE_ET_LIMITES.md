# Traduction gratuite et limites des services

[Accueil](../README.md) · [Documentation](README.md) · [Configuration des moteurs](GUIDE_MOTEURS_TRADUCTION.md) · [Notes 0.5.1](releases/0.5.1.md)

Offres vérifiées le **27 octobre 2026** ; contrôler les conditions affichées dans son compte. Ce guide décrit
le comportement des sources **0.5.1**, validées avant fusion ; l’état de publication figure dans les [notes de version](releases/0.5.1.md).

## Quel service choisir pour une piste complète ?

**Microsoft Azure Translator F0 est l'option gratuite en ligne recommandée ici** :
son quota mensuel est plus élevé que celui de l'ancien DeepL API Free et son
adaptateur est déjà intégré au lecteur. La qualité reste à apprécier selon les
langues et l'œuvre ; aucune API ne garantit une traduction parfaite.

| Service | Offre gratuite vérifiée | Disponibilité dans le lecteur |
| --- | --- | --- |
| Azure Translator F0 | 2 millions de caractères par mois, partagés entre les opérations concernées | Intégré ; compte Azure, ressource F0 et clé personnelle |
| Ancien DeepL API Free | 500 000 caractères par mois ; plus proposé aux nouvelles souscriptions | Intégré ; conserver le point d'accès correspondant à la clé |
| Nouveau DeepL API Developer | 1 million de caractères **au total**, sans renouvellement | L'accès dépend du point d'accès autorisé pour la clé ; aucun quota mensuel gratuit à supposer |
| Google Cloud Translation NMT | 500 000 caractères par mois, sous forme d'un crédit de 10 USD | **Non intégré** ; compte de facturation obligatoire, dépassements payants |
| Google ML Kit | Traduction exécutée sur le téléphone après téléchargement des modèles | **Non intégré** ; qualité et langues à évaluer avant une future intégration |
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

## Google et les autres possibilités

**Google Cloud Translation** est l'API officielle envisageable : son crédit NMT
mensuel couvre 500 000 caractères, collectivement entre Basic et Advanced.
Il faut activer la facturation ; le crédit n'est pas reportable. Ce service
n'apparaît pas encore dans les choix du lecteur.

Le site **Google Traduction** gratuit n'est pas un point d'accès API officiel
garanti pour traduire automatiquement des films. Les adaptateurs qui exploitent
son interface web peuvent cesser de fonctionner ou être bloqués. Ils ne sont
pas retenus ici comme moteur fiable. **ML Kit** utilise des modèles locaux du
mode hors ligne Google Traduction ; entre langues autres que l'anglais, il
passe par l'anglais, ce qui peut affecter la qualité. Il reste à intégrer.

**MyMemory** limite chaque texte à **500 octets UTF-8** : son quota gratuit et
cette contrainte en font surtout une option à étudier pour de courts contenus.
**LibreTranslate** peut être hébergé librement, mais le serveur doit exister et
rester disponible ; une instance publique gratuite n'offre pas de garantie universelle.

## Références

- DeepL : [erreurs et reprises](https://developers.deepl.com/docs/best-practices/error-handling), [limites de taille](https://developers.deepl.com/docs/resources/usage-limits), [offres API](https://support.deepl.com/hc/en-us/articles/360021200939-DeepL-API-plans).
- Microsoft : [tarifs F0](https://azure.microsoft.com/en-us/pricing/details/translator/), [création de ressource](https://learn.microsoft.com/en-us/azure/ai-services/translator/how-to-create-translator-resource), [limites de débit](https://learn.microsoft.com/en-us/azure/ai-services/translator/service-limits).
- Google : [tarifs Cloud Translation](https://cloud.google.com/products/translate/pricing), [activation et facturation](https://docs.cloud.google.com/translate/docs/setup), [modèles et limites ML Kit](https://developers.google.com/ml-kit/language/translation).
- MyMemory : [quotas](https://mymemory.translated.net/doc/usagelimits.php), [spécification API](https://mymemory.translated.net/doc/spec.php). LibreTranslate : [logiciel et hébergement](https://docs.libretranslate.com/).
- Interface Google non officielle : [avertissements du mainteneur Googletrans](https://py-googletrans.readthedocs.io/en/latest/) ; cette source décrit la bibliothèque, pas un engagement de Google.
