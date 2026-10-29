# Choisir et configurer les moteurs de traduction

[Accueil](../README.md) · [Documentation](README.md) · [Services gratuits et limites](TRADUCTION_GRATUITE_ET_LIMITES.md) · [Tests sur téléphone](TESTS_TELEPHONE.md)

Guide d’utilisation de la **0.5.2**, complété par le GGUF de la **0.5.4-local publiée** et les optimisations de la **candidate 0.5.5-perf** ; état documentaire au **28 octobre 2026**. La publication et les résultats réels figurent dans les [notes 0.5.2](releases/0.5.2.md), les [notes 0.5.4](releases/0.5.4-local.md) et les [notes de la candidate 0.5.5](releases/0.5.5-perf.md). Les références commerciales initiales ont été vérifiées le 24 octobre, puis les offres gratuites le 27 octobre. Les conditions, langues et droits applicables restent ceux affichés dans le compte du fournisseur.

## Fonctions disponibles

La **traduction complète avant lecture** reste disponible. Le mode **progressif** permet de commencer avec un préfixe validé et une avance de **30 secondes par défaut**, réglable de **10 à 120 secondes**. La vidéo attend avant une occurrence indisponible. Une reprise automatique exige une attente créée par la traduction et une intention Lire toujours valide ; Pause utilisateur, déplacement, erreur ou changement de contexte empêchent une reprise tardive. Les textes manquants ne sont pas remplacés silencieusement par l’original. Le [guide Google et lecture progressive](TRADUCTION_GOOGLE_052.md) décrit ce comportement.

La narration utilise les occurrences françaises validées. L’affichage propose indépendamment **original, français, bilingue ou masqué**. Le profil Lecture intégrale préserve toutes les occurrences textuelles extraites de la piste choisie ; les filtres d’exclusion exigent sa désactivation explicite. Moteur vocal et traducteur sont distincts : la voix prononce le résultat fourni par le traducteur.

| Choix affiché | Où travaille le traducteur ? | Ce qu'il faut fournir | Installation de poids sur Android |
| --- | --- | --- | --- |
| Google en ligne sans clé | Serveurs Google, accès web non officiel | Langue source et confirmation ; aucune clé Cloud | Aucune |
| Google local — ML Kit | Téléphone | Paire de langues compatible ; modèles nécessaires | Téléchargement intégré, Wi-Fi par défaut |
| GGUF local — 0.5.4 publiée / candidate 0.5.5 | Téléphone ; CPU, GPU facultatif dans la candidate | Fichier GGUF compatible choisi explicitement et langue source | Copie privée depuis le sélecteur Android ; aucun téléchargement automatique |
| Microsoft Azure Translator | Serveurs Microsoft | Clé API personnelle et région selon la ressource | Aucune |
| DeepL — point d'accès API Free | Serveurs DeepL, endpoint `api-free.deepl.com` | Clé API autorisée pour ce point d'accès | Aucune |
| DeepL — point d'accès API payant | Serveurs DeepL, endpoint `api.deepl.com` | Clé API autorisée pour ce point d'accès | Aucune |
| LibreTranslate — serveur personnel HTTPS | Serveur choisi par l'utilisateur | Adresse HTTPS, clé si l'instance l'exige | Aucune dans cette application |

**ML Kit est intégré pour la traduction exécutée sur le téléphone. La préversion publiée 0.5.4 ajoute l’import privé et l’exécution CPU d’un GGUF compatible. La candidate 0.5.5 prépare des optimisations et le correctif de profil Hy-MT2 ; l’architecture Hunyuan seule ne garantit pas cette compatibilité.** Le [guide publié](TRADUCTION_GGUF_054.md) décrit le SRT autonome, la reprise et l’export ; le [guide candidat 0.5.5](TRADUCTION_GGUF_055.md) distingue CPU, GPU facultatif et mesures réelles. L’application ne parcourt pas Hugging Face et n’importe pas de paquet Argos. Les autres architectures présentées plus loin restent des candidats ; Google Cloud Translation et Apps Script restent distincts des choix intégrés.

Les résultats ont trois usages distincts :

- **Cache de pistes** : réutilisation des traductions complètes validées, dans une limite de 32 Mio par parcours — vidéo ou SRT indépendant ; une entrée peut être évincée.
- **Cache de requêtes GGUF exactes — 0.5.10** : 8 Mio supplémentaires par parcours, réservés au travail local explicitement compatible. Modèle, configuration, langues, texte et historique doivent correspondre ; chaque occurrence garde ses propres identifiants et minutages. Une réutilisation n’est pas présentée comme un nouveau calcul GPU.
- **Journal de reprise** : distinct des caches évictables, borné à 32 Mio par plan. Effacer le cache d’un parcours retire aussi ses requêtes exactes, sans retirer les résultats conservés durablement. Voir les [différences et limites 0.5.10](TRADUCTION_QUALITE_0510.md).
- **Progression** : lots déjà validés conservés pour reprendre avec la même piste et les mêmes paramètres. Un lot au résultat incertain demande une confirmation avant un éventuel renvoi.
- **Conservation et export** : conservation durable explicite d’une traduction complète, ou export SRT/VTT à l’emplacement choisi. L’export peut ensuite être réimporté comme piste française.

Ces trois fonctions réutilisent un texte déjà traduit ; elles restent distinctes du calcul d’une nouvelle traduction locale avec ML Kit ou le GGUF choisi. La conservation privée dépend du stockage de l’application ; un export constitue un fichier séparé. Pour un calcul GGUF interrompu, la reprise conserve les occurrences validées sans notion de facturation externe.

## Parcours depuis le téléphone

1. Ouvre la vidéo et choisis la **piste textuelle** à traduire. Pour des
   sous-titres uniquement incrustés dans l'image ou des pistes bitmap, une étape
   OCR est nécessaire ; ce module de traduction ne réalise pas cet OCR.
2. Ouvre **« Traduire les sous-titres en français »**. Choisis le fournisseur et
   indique explicitement la langue de la piste. La cible de ce parcours est le
   français.
3. Renseigne les informations du fournisseur décrites ci-dessous. La clé
   personnelle se saisit dans l'application, jamais dans une issue GitHub,
   une capture publique ou un message de diagnostic.
4. Choisis éventuellement **Traduction progressive**, son avance de 10 à 120 secondes et un plafond de nouveaux caractères UTF-16. Appuie sur **« Indexer la piste et estimer la traduction »**. Le lecteur se
   met en pause et indexe la piste entière.
   Cette première opération sert à contrôler les occurrences et leur volume ;
   elle n'envoie pas encore le texte au traducteur.
5. Vérifie le fournisseur, le nombre d'occurrences et l'estimation affichée avant
   de confirmer avec **« Traduire / réutiliser le cache »**. L'estimation en
   unités UTF-16 n'est pas une facture
   garantie : les règles de comptage du fournisseur restent applicables.
6. Attends la piste complète, ou le tampon validé si tu as choisi le mode progressif. Une réponse incomplète n’est jamais présentée comme une piste complète. DeepL conserve ses reprises bornées après **429/529**. Google en ligne possède sa politique distincte décrite plus bas ; l’arrivée d’un résultat ne doit jamais annuler une Pause utilisateur.
7. Si la vocalisation est activée, sélectionne une voix française prête dans
   **« Voix et confort »**. Appuie ensuite sur **« Lire »** quand la piste
   française ou le tampon progressif est annoncé prêt. L'application vérifie la voix française avant
   cette lecture uniquement lorsque la vocalisation est activée.

L'annulation ne restitue pas les caractères déjà consommés chez le fournisseur.
La reprise réutilise les lots validés si la piste et les paramètres correspondent. Un lot sans résultat confirmé peut toutefois consommer des caractères une seconde fois. Le retour à la vocalisation originale reste un choix explicite ; un
échec de traduction ne doit pas provoquer une substitution silencieuse.

### Codes de langues utiles

Le champ attend le code de la langue **des sous-titres**, pas celui de l'audio.
Pour une piste multilingue, choisir une seule langue peut mal traiter certains
passages ; ne pas considérer la détection phrase par phrase comme garantie.

| Langue | Azure | DeepL | LibreTranslate |
| --- | --- | --- | --- |
| Anglais | `en` | `en` | `en` si installé |
| Espagnol | `es` | `es` | `es` si installé |
| Allemand | `de` | `de` | `de` si installé |
| Italien | `it` | `it` | `it` si installé |
| Serbe en alphabet latin | `sr-Latn` | `sr`, si disponible pour ton accès | Vérifier l'instance |
| Serbe en alphabet cyrillique | `sr-Cyrl` | `sr`, si disponible pour ton accès | Vérifier l'instance |

La liste DeepL actuelle mentionne `SR`, mais il faut contrôler les capacités du
compte et de la paire. **Ne pas recopier `sr-Latn` d'Azure dans DeepL** : ces
services n'utilisent pas nécessairement les mêmes variantes. Le lecteur met les
codes DeepL en majuscules pour la requête. Ne pas remplacer une langue non prise
en charge par une langue voisine, notamment le serbe par le croate.

Sources : [langues Azure](https://learn.microsoft.com/en-us/azure/ai-services/translator/language-support),
[langues DeepL](https://developers.deepl.com/docs/getting-started/supported-languages),
[langues LibreTranslate](https://docs.libretranslate.com/guides/supported_languages/).

## Google en ligne sans clé et Google local

Ces deux choix disposent de parcours distincts, avec une attribution Google et une langue source explicite. L’utilisateur confirme l’initialisation de ML Kit et le téléchargement de ses modèles. Les textes restent sur le téléphone avec ML Kit, mais Google déclare une télémétrie technique : appareil, application, identifiants d’installation, performances, tailles d’entrées/sorties, langues et événements. Voir la [déclaration de collecte ML Kit](https://developers.google.com/ml-kit/android-data-disclosure).

- **En ligne** : accès web non officiel, sans compte Cloud ni clé à saisir. Intervalle minimal de **1 000 ms** par défaut, réglable de **500 à 10 000 ms** ; appels sérialisés, au plus trois reprises temporaires et budget cumulé de 120 secondes d’attente par requête, hors réseau, avec respect de `Retry-After`. Un refus ne déclenche ni rotation d’IP ni autre point d’accès de contournement. Aucune disponibilité permanente n’est promise.
- **Local** : bibliothèque **ML Kit 17.0.3**, contrôle de la paire de langues et téléchargement des modèles nécessaires. Le Wi-Fi est requis par défaut ; une option explicite permet le réseau mobile. Une fois les modèles prêts, les textes sont traduits sur l’appareil. La qualité peut différer de Google en ligne et les langues disponibles ne sont pas identiques.
- **Réutilisation** : le cache/progrès compatible évite les demandes déjà validées. La réutilisation en mémoire d’une traduction de texte strictement identique n’enlève aucune occurrence de la piste et aucune vocalisation.

Le [guide Google 0.5.2](TRADUCTION_GOOGLE_052.md) détaille les paramètres, la lecture pendant la traduction, les téléchargements, les limites de preuve et les projets examinés. Une préparation ou une restauration de séance démarre en pause ; la reprise progressive est réservée à l’attente causée par la traduction avec une intention de lecture encore valide.

## Microsoft Azure Translator : configuration complète

### À quoi correspond ce choix ?

Il utilise l'API de traduction de texte Azure, version 3.0. L'application envoie
des lots de textes et reçoit les traductions correspondantes. Les sous-titres
partent vers Microsoft ; le fichier vidéo et sa piste audio ne sont pas envoyés
par cet adaptateur. L'option de traitement des grossièretés est `NoAction`, pour
éviter une suppression volontaire du texte par ce réglage.

### Obtenir les accès depuis Android

1. Dans ton navigateur Android, ouvre le [portail Azure](https://portal.azure.com/)
   et connecte-toi à ton compte Microsoft. Un compte et une souscription Azure
   actifs sont nécessaires ; vérifie les conditions demandées à l'inscription.
2. Crée une ressource **Translator** destinée à la traduction de texte standard.
   Pour ce parcours, un déploiement Azure OpenAI ou un modèle LLM n'est pas requis.
3. Choisis le palier **F0** si tu souhaites commencer par le quota gratuit et si
   celui-ci est disponible dans ta souscription. N'accepte pas un palier S1 en
   supposant qu'il est équivalent au gratuit.
4. Après création, ouvre la ressource, puis **« Keys and Endpoint » / « Clés et
   point de terminaison »**. Copie une clé dans le champ prévu du lecteur.
5. Pour une ressource régionale, renseigne son identifiant de région tel qu'il
   figure dans Azure. Ne mets pas ton adresse, ton pays, l'URL du portail ou le
   nom libre de la ressource dans ce champ. Une ressource globale n'exige pas
   la même information qu'une ressource régionale ou multiservice.
6. Choisis **Microsoft Azure Translator** dans le lecteur et prépare la piste.

L'adresse réseau utilisée par cette étape est fixée à
`https://api.cognitive.microsofttranslator.com/translate`. Il n'existe pas de
champ permettant d'y substituer une adresse Azure privée, une URL Azure OpenAI
ou un déploiement personnalisé. Vérifie donc que ta ressource permet cet accès.
La [procédure Microsoft](https://learn.microsoft.com/en-us/azure/cognitive-services/translator/how-to-create-translator-resource)
décrit la création et les clés ; la
[référence d'authentification](https://learn.microsoft.com/en-us/azure/ai-services/translator/text-translation/reference/authentication)
distingue clé globale, clé régionale et région obligatoire selon la ressource.

### Quota, confidentialité et limites

La [page tarifaire officielle](https://azure.microsoft.com/en-us/pricing/details/translator/)
annonce **2 millions de caractères par mois** pour le palier F0, selon les
opérations qui partagent ce quota. Le débit et les tailles de requêtes ont aussi
leurs propres limites ; un quota mensuel disponible ne garantit pas l'acceptation
d'une rafale. Le palier payant S1 a une facturation distincte. Consulte le prix
affiché pour ta région et ta devise ; ce guide ne fixe aucun montant payant.

La [politique Microsoft sur les données de traduction](https://learn.microsoft.com/en-us/azure/ai-foundry/responsible-ai/translator/data-privacy-security?view=foundry-classic)
décrit l'absence de conservation des données soumises à la traduction de texte.
Cela reste un traitement distant. Le coût, les compteurs et l'accès se vérifient
dans Azure. En cas de clé exposée, révoque ou régénère-la dans le portail et
remplace-la dans le lecteur.

## DeepL : compte, point d'accès et facturation

### Deux adresses d'API, pas deux modèles locaux

Les deux entrées DeepL du lecteur servent à choisir le point d'accès auquel ta
clé donne droit. Elles appellent la même famille d'API de traduction de texte :

| Entrée du lecteur | Adresse utilisée |
| --- | --- |
| DeepL — point d'accès API Free | `https://api-free.deepl.com/v2/translate` |
| DeepL — point d'accès API payant | `https://api.deepl.com/v2/translate` |

Le nom « API Free » de l'adresse n'annonce pas qu'un nouveau compte dispose
nécessairement de l'ancienne offre mensuelle. Le
[guide de démarrage DeepL](https://developers.deepl.com/docs/getting-started/quickstart)
explique la clé et le choix de l'hôte. Un abonnement au traducteur Web ou à
l'application DeepL n'est pas automatiquement un abonnement API.

### Obtenir une clé depuis Android

1. Ouvre la [page développeurs DeepL](https://www.deepl.com/en/developers) dans le
   navigateur et choisis une offre **API**, en contrôlant son quota et ses coûts.
2. Connecte-toi au compte API approprié. La documentation peut demander un compte
   distinct du compte utilisé pour l'abonnement de traduction Web.
3. Dans la gestion du compte, ouvre les clés API et copie ta clé. Ne saisis pas
   le mot de passe du compte DeepL dans le champ clé.
4. Dans le lecteur, choisis l'entrée correspondant à **l'endpoint documenté pour
   ton plan**, puis colle la clé. Aucune région Azure n'est nécessaire.
5. Renseigne la langue source, prépare la piste et confirme seulement après
   avoir contrôlé le volume. En cas de refus, vérifie d'abord la clé et l'hôte
   autorisé dans ton compte ; ne multiplie pas les tentatives sur des plans
   différents en supposant qu'un accès payant a été acheté.

### Attention à l'évolution des offres

Au contrôle du **24 octobre 2026**, la
[page officielle des plans API](https://support.deepl.com/hc/en-us/articles/360021200939-DeepL-API-plans)
distingue notamment :

- **API Developer** : essai gratuit d'un total de **1 million de caractères**,
  qui ne se renouvelle pas une fois épuisé ;
- **API Growth** : abonnement et consommation au-delà du volume inclus selon le
  contrat ; consulter les contrôles de coût du compte ;
- **API Free historique** : **500 000 caractères par mois**, mais cette offre
  n'est plus proposée aux nouvelles souscriptions ;
- **API Pro historique** : ancien plan, également fermé aux nouvelles
  souscriptions ; des clés de comptes existants peuvent toujours correspondre
  à cet accès.

L'application ne souscrit aucun abonnement à ta place et ne transforme pas une
clé gratuite en clé payante. Les réglages de plafond du compte fournisseur sont
à configurer avant un usage suivi. Les conditions de conservation et de
traitement des textes dépendent du plan applicable ; ne pas attribuer aux essais
gratuits toutes les garanties commerciales d'un autre plan.

Les textes d'un lot DeepL sont traduits séparément : la présence de répliques
voisines dans le même tableau ne garantit pas qu'elles servent de contexte entre
elles. Les options suivantes sont accessibles depuis la 0.5.0 :

| Option DeepL | Fonction et limite |
| --- | --- |
| Contexte facultatif | Synopsis, noms et indications utiles, jusqu’à 24 000 caractères ; envoyé avec chaque lot. |
| Glossaire et révision | Identifiant d’un glossaire déjà créé dans ton compte ; modifier la révision locale après une modification distante pour invalider les anciens résultats. L’application ne crée pas le glossaire. |
| Modèle | Valeur prise en charge par le compte et la paire, par exemple `quality_optimized`, `latency_optimized` ou `prefer_quality_optimized`. |
| Registre | `more`, `less`, `prefer_more` ou `prefer_less`, selon la langue et le compte. |
| Consultation du quota | Requête au compte DeepL sans texte de sous-titres ; valeurs à l’instant de la consultation, sans réservation du solde. |

Contexte, glossaire, révision, modèle et registre font partie de l’identité des résultats réutilisables. Une option refusée doit être corrigée explicitement. La comparaison d’un échantillon entre plusieurs configurations exige une confirmation et peut consommer le quota de chaque destination ; elle ne produit pas de classement automatique de fidélité.

Source technique : [traduire un texte avec DeepL](https://developers.deepl.com/api-reference/translate/request-translation).

### Débit temporaire et quota : deux situations distinctes

Une réponse **HTTP 429/529** indique une limitation temporaire du débit. Le
correctif 0.5.1 espace les appels DeepL de **500 ms** au minimum et réessaie
au plus **cinq fois** le même lot après 1, 2, 4, 8 puis 16 secondes, avec un
léger aléa. Un `Retry-After` valide est respecté. L'attente cumulée est limitée
à **120 secondes par lot**, hors durée des échanges réseau ; si le serveur exige
un délai supérieur au budget restant, le lecteur s'arrête sans renvoyer trop tôt.
L'annulation reste possible pendant ces attentes. Les lots gardent leur limite
de **20 textes**, et les résultats déjà validés sont conservés pour la reprise,
sous réserve de réussite du stockage et de paramètres correspondants.

**HTTP 456** signifie une limite de caractères, de coût ou de clé. Le solde
global restant ne suffit pas à exclure un plafond propre à une clé. Il faut
contrôler le compte avant de reprendre. Les **500/502/503/504**, erreurs réseau
et résultats incomplets ne sont pas renvoyés automatiquement : la consommation
du lot peut être incertaine. Les autres moteurs et la consultation du quota
DeepL conservent une reprise explicite. Une nouvelle demande peut refacturer
un lot dont la réponse a été perdue, mais réutilise les lots validés disponibles.

Source : [gestion des erreurs DeepL](https://developers.deepl.com/docs/best-practices/error-handling).

## LibreTranslate : utiliser un serveur choisi

### Fonction et conditions

LibreTranslate est un logiciel serveur libre fondé sur Argos Translate. Tu peux
utiliser une instance que tu contrôles ou un hébergement autorisé. Le texte est
traité **sur ce serveur**, même si tu as un excellent téléphone. Le téléchargement
d'un paquet Argos sur Android ne crée pas cette instance et ne l'intègre pas
dans le lecteur.

Le logiciel gratuit n'implique pas qu'un service hébergé soit gratuit, illimité
ou disponible sans clé. L'instance officielle propose aussi un hébergement géré.
Le propriétaire de l'instance décide des langues, quotas, clés, journaux et
conditions de confidentialité.

### Configuration depuis Android

1. Obtiens auprès de ton hébergeur ou de l'administrateur de ton instance une
   **adresse HTTPS** et, si nécessaire, une clé. Il doit confirmer que l'instance
   implémente l'API LibreTranslate et le couple de langues voulu.
2. Choisis **LibreTranslate — serveur personnel HTTPS** dans le lecteur.
3. Saisis l'adresse de base, par exemple `https://traduction.exemple.fr`, ou
   l'adresse se terminant par `/translate` fournie par l'administrateur. Le
   lecteur ajoute ce chemin s'il manque et conserve un éventuel préfixe serveur.
   Les adresses d'exemple ne sont pas des services opérationnels.
4. Renseigne la clé uniquement si l'instance l'exige. Laisse ce champ vide quand
   l'instance permet explicitement l'accès sans clé.
5. Choisis la langue source, prépare et confirme l'envoi. Si l'instance renvoie
   une page HTML d'identification, un protocole différent ou une redirection,
   ce n'est pas une réponse de traduction compatible avec cet adaptateur.

`http://localhost:5000`, l'adresse souvent montrée dans les tutoriels serveur,
n'est pas utilisable par cet adaptateur HTTPS. Sur ton téléphone, `localhost`
désigne ton téléphone ; il ne désigne pas automatiquement un serveur à domicile.
Une interface de traduction Web et son endpoint API peuvent aussi avoir des
adresses différentes. Un certificat valide est requis : l'application ne
désactive pas la validation TLS.

La création d'un serveur relève d'une installation distincte ; ce guide ne
demande ni ordinateur personnel ni commandes système à l'utilisateur Android.
Sans serveur disponible, ce choix ne devient pas fonctionnel par la seule saisie
d'un nom de moteur. Avant de payer un hébergement, vérifie langues, prix et accès
API. Le lecteur ne crée et ne finance pas ce serveur.

Sources : [présentation LibreTranslate](https://docs.libretranslate.com/),
[usage de l'API](https://docs.libretranslate.com/guides/api_usage/),
[paramètres de l'instance](https://docs.libretranslate.com/guides/installation/),
[opération de traduction](https://docs.libretranslate.com/api/operations/translate/).

## Hugging Face et les autres catalogues : ce qu'on y trouve

**Hugging Face est notamment un catalogue et un hébergeur de fichiers de modèles.
Ce n'est pas, à lui seul, le moteur d'exécution de ces fichiers sur Android.**
Une fiche de modèle peut proposer des poids à télécharger, une démonstration
Web, une API distante ou un exemple Python. Ce sont des usages différents.

| Élément | Rôle | Exemple |
| --- | --- | --- |
| Modèle | Poids entraînés qui déterminent le comportement linguistique | Index-Translate-2B, NLLB-200 |
| Runtime | Logiciel qui charge et calcule ces poids | llama.cpp, un runtime mobile compatible avec l'architecture |
| Format | Organisation des poids et métadonnées | GGUF, Safetensors, ONNX, paquet `.argosmodel` |
| Quantification | Représentation de poids à précision réduite | Q4_K_M ou int8 selon le runtime |
| Catalogue | Recherche, fiche, versions, licences et fichiers | Hugging Face, ModelScope, catalogue Argos |
| Service d'inférence | Exécution chez un prestataire | Endpoint distant ; ses quotas et coûts propres |

Un fichier Safetensors n'est pas un APK. ONNX n'annonce pas automatiquement un
modèle mobile exploitable. Deux fichiers GGUF peuvent viser des architectures
différentes : le suffixe ne suffit pas à établir leur compatibilité. Un port MLX
pour Apple Silicon n'est pas une distribution Android. La taille du fichier ne
donne pas la mémoire totale de l'inférence, qui comprend aussi les allocations
du runtime et de la génération.

### Consulter et télécharger depuis le navigateur Android

Cette procédure sert à comprendre et conserver un fichier ; **elle ne l'active
pas dans cette version du lecteur**.

1. Pars de la fiche de l'éditeur indiquée dans le tableau suivant. Vérifie le
   compte qui publie le dépôt, le nom exact et la fiche de modèle. Une reprise
   communautaire doit être identifiée comme telle.
2. Lis la licence des poids et les langues, puis les formats proposés dans
   **« Files and versions » / « Fichiers et versions »**. Vérifie que la paire
   voulue existe ; le nombre global de langues n'est pas une mesure de qualité.
3. Si le modèle est soumis à accès contrôlé, connecte-toi à Hugging Face depuis
   le navigateur et lis les conditions avant de demander l'accès. Des informations
   du compte peuvent être partagées avec l'éditeur ; l'accès peut être automatique
   ou demander une décision de celui-ci.
4. Pour un futur import, le guide de la version du lecteur devra préciser le
   runtime, la révision du modèle et le fichier exact. Ne télécharge pas plusieurs
   gigaoctets au hasard : l'application actuelle ne possède pas cet import.
5. Lorsqu'un fichier compatible aura été retenu, télécharge-le avec une connexion
   et un espace suffisants ; conserve son nom et sa révision. Pour un modèle
   découpé, tous les morceaux et fichiers de tokenizer nécessaires doivent
   correspondre à la même révision.
6. L'implémentation future devra vérifier une empreinte publiée, par exemple
   SHA-256, et refuser un fichier tronqué ou incompatible. Une empreinte obtenue
   seulement du même téléchargement non fiable n'établit pas son authenticité.
   Il n'existe pas encore de bouton de vérification/import à utiliser dans ce
   lecteur.

Un abonnement Hugging Face n'est pas automatiquement nécessaire pour télécharger
des poids publics autorisés. Un bouton de déploiement, un endpoint dédié ou un
fournisseur d'inférence peut en revanche engager un service distinct : vérifier
l'offre avant activation. Il n'existe pas de compatibilité universelle entre
les API de ces fournisseurs et les adaptateurs actuels.

Sources : [téléchargement de modèles](https://huggingface.co/docs/hub/models-downloading),
[modèles à accès contrôlé](https://huggingface.co/docs/hub/models-gated).

## ML Kit intégré et autres modèles locaux envisageables

Cette sélection couvre plusieurs architectures utiles à comparer ; elle n'est
pas un inventaire de tous les modèles existants. **Aucun résultat de débit,
d'autonomie, de qualité ou d'accélération NPU sur OnePlus 15 n'est établi par ce
guide.** Cette sélection historique n’établit aucun classement définitif. ML Kit est intégré depuis la 0.5.2 ; la préversion publiée 0.5.4 ajoute un runtime GGUF et des consignes de traduction. La candidate 0.5.5 prépare les optimisations et la reconnaissance corrigée de Hy-MT2. L’intégration ne prouve ni la compatibilité de tous les fichiers, ni la qualité linguistique ou les performances du téléphone.

| Candidat et source primaire | Caractéristiques documentées | Licence / distribution | Intérêt et condition pour Android |
| --- | --- | --- | --- |
| [Index-Translate-2B](https://huggingface.co/IndexTeam/Index-Translate-2B), [GGUF de l'équipe](https://huggingface.co/IndexTeam/Index-Translate-2B-GGUF) | 2 milliards de paramètres, famille fondée sur Qwen3.5 ; couverture annoncée de 150 langues | Apache-2.0 annoncée ; GGUF quantifiés publiés par IndexTeam | Premier candidat spécialisé à mesurer avec un runtime natif prenant réellement en charge cette architecture et son gabarit |
| [Google ML Kit Translation](https://developers.google.com/ml-kit/language/translation/android) | SDK Android et modèles à la demande ; plus de 50 langues | SDK et modèles soumis aux conditions Google, attribution et métriques à déclarer | **Intégré en 0.5.2** ; langues validées par le SDK, modèles à la demande et attribution Google ; vérifier la disponibilité exacte d’une langue avant préparation |
| [TranslateGemma 4B IT](https://huggingface.co/google/translategemma-4b-it), [annonce Google](https://blog.google/innovation-and-ai/technology/developers-tools/translategemma/) | Famille Gemma 3 de traduction ; tailles 4B, 12B et 27B, 55 langues annoncées | Conditions Gemma applicables à ce modèle ; accès aux poids à accepter | Consignes intégrées depuis la 0.5.4 ; conversion exacte, langues, mémoire et qualité à vérifier ; distinct de Gemini Nano et ML Kit |
| [Hy-MT2-7B](https://huggingface.co/tencent/Hy-MT2-7B-GGUF) | Famille de traduction Hunyuan dense, GGUF publiés par Tencent | Conditions du modèle à consulter dans le dépôt des poids | Profil de traduction introduit en 0.5.4, mais métadonnées du GGUF officiel refusées par cette version ; correctif préparé en 0.5.5. Ni débit ni fidélité d’un film ne sont déduits d’un chargement court |
| [NLLB-200 distilled 600M](https://huggingface.co/facebook/nllb-200-distilled-600M) | Encodeur-décodeur de traduction multilingue ; variante distillée 600M ; codes de langue et d'écriture propres | **CC-BY-NC-4.0**, modèle présenté pour la recherche | Comparateur relativement compact ; tokenizer, jeton cible et runtime doivent être adaptés ; licence non commerciale à vérifier avant distribution/usage commercial |
| [MADLAD-400 3B MT](https://huggingface.co/google/madlad400-3b-mt) | Modèle T5 de traduction ; large couverture annoncée, plus de 450 langues dans la description d'entraînement | Apache-2.0 annoncée ; fiche décrivant une conversion et un chargement quantifié avec Candle | Comparateur large couverture, plus volumineux ; un GGUF T5/Candle n'est pas une preuve de compatibilité avec llama.cpp |
| [M2M100 418M](https://huggingface.co/facebook/m2m100_418M) | Encodeur-décodeur de traduction directe entre 100 langues ; langue cible forcée à la génération | MIT annoncée sur le dépôt des poids | Base compacte à évaluer, sans présumer qu'un ancien modèle bat les solutions récentes ; pas de chargement natif déjà intégré |
| [OPUS-MT en-fr](https://huggingface.co/Helsinki-NLP/opus-mt-en-fr), [Marian](https://github.com/marian-nmt/marian) | Modèles par couple ou groupe de langues ; exemple en-fr avec tokenizer SentencePiece ; Marian est le toolkit de calcul | Exemple en-fr annoncé Apache-2.0 ; code Marian MIT ; vérifier chaque autre paquet | Possibilité de paquets ciblés plutôt qu'un gros modèle universel ; la direction en-fr ne donne pas automatiquement es-fr ou serbe-fr |
| [Argos Translate](https://github.com/argosopentech/argos-translate), [index des paquets](https://github.com/argosopentech/argospm-index) | Bibliothèque hors ligne avec paquets `.argosmodel` ; pivot possible par langue intermédiaire | Code Argos MIT ; vérifier les notices des paquets exacts | Alternative à porter/emballer dans Android ; importer un paquet suppose un runtime Argos compatible, absent ici |

### Ce que ces choix impliquent en pratique

**Index-Translate-2B.** La distribution GGUF de l'équipe donne environ 1,31 Go pour
Q4_K_M, 1,45 Go pour Q5_K_M et 2,08 Go pour Q8_0. Ces tailles sont celles des
fichiers publiés, pas des besoins RAM. La prise en charge Android de
[llama.cpp](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md)
rend un développement natif envisageable ; il faut ensuite prouver que le modèle,
la révision du runtime et le backend choisis fonctionnent ensemble. Les options
de GPU ne promettent pas l'utilisation du NPU. Le miroir
[ModelScope de l'équipe](https://www.modelscope.cn/models/IndexTeam/Index-Translate-2B)
offre une autre source à vérifier, sans supprimer le besoin de runtime.

**ML Kit.** Les paquets, d'environ 30 Mo chacun selon Google, sont gérés par le
SDK et non par un import Hugging Face. La traduction est locale après obtention
des modèles. Google indique un pivot anglais entre deux langues non anglaises
et recommande une évaluation pour les besoins de fidélité élevée. Les
[conditions ML Kit](https://developers.google.com/ml-kit/terms) décrivent aussi
des échanges de métriques et de données de performance : « traitement local »
ne signifie donc pas « aucun contact réseau ». Les
[obligations d'attribution](https://developers.google.com/ml-kit/language/translation/translation-terms)
sont prises en compte par l’intégration. Ce moteur est désormais disponible en 0.5.2 avec la version 17.0.3 ; le choix local n’annonce aucune accélération NPU ni mesure de débit sur le téléphone.

**TranslateGemma.** Le compte officiel Google et les
[conditions Gemma](https://ai.google.dev/gemma/terms) constituent le point de
départ. Une conversion déposée par un autre compte doit préciser son modèle
d'origine, sa révision et ses changements ; l'existence de telles conversions
ne les fait pas devenir des fichiers officiels Google. Les tailles 12B et 27B
ne sont pas un objectif à imposer au téléphone au seul motif qu'elles sont plus
grandes. Le français et chaque langue source doivent être contrôlés dans la
fiche et le gabarit du fichier retenu.

**NLLB, MADLAD et M2M100.** Ce sont des architectures de traduction avec encodage,
décodage et règles de langue spécifiques. Un exemple Transformers/Python sur la
fiche n'est pas une procédure d'installation Android. Une conversion ONNX,
CTranslate2 ou autre demande son propre support natif, ses tokenizers et ses
tests ; changer seulement le suffixe du fichier n'effectue aucune conversion.
Les unités 600M, 3B et 418M décrivent des ordres de grandeur de paramètres et non
un classement de fidélité. Une langue à faible ressource doit être testée dans
la direction exacte vers le français.

**OPUS-MT, Marian et Argos.** Distinguer les poids OPUS-MT, le toolkit Marian et
la bibliothèque Argos évite de mélanger trois niveaux. Les catalogues indiquent
des couples directionnels ; une traduction par pivot peut couvrir un couple
absent, avec un risque supplémentaire de perte de sens. Le
[guide des paquets Argos](https://argos-translate.readthedocs.io/en/latest/source/gui.html)
montre le format `.argosmodel` dans son propre logiciel. Les menus qu'il décrit
ne sont pas des menus de ce lecteur Android.

## Import GGUF et conditions pour d’autres familles

ML Kit possède son téléchargement intégré. Le [parcours GGUF publié en 0.5.4](TRADUCTION_GGUF_054.md) permet un choix de fichier local, avec copie privée, empreinte, progression et contrôle du moteur. Il ne fournit pas de catalogue de poids ou de téléchargement intégré. Pour une autre famille, les exigences suivantes restent à établir :

1. Un moteur Android ARM64 embarqué et versionné, compatible avec l'architecture
   exacte du modèle. Le CPU, le GPU et le NPU doivent être des capacités mesurées
   ou explicitement indisponibles, pas des promesses de catalogue.
2. Un petit catalogue vérifié : éditeur, modèle, langues, licence, révision,
   quantification, tokenizer/gabarit, taille, espace temporaire et empreintes.
3. Un téléchargement ou un choix de fichier **depuis Android**, avec progression,
   annulation, reprise sûre et contrôle d'intégrité ; un modèle non compatible
   doit être rejeté avant de commencer la vidéo.
4. Une vérification locale de chargement et un essai synthétique court, puis un
   banc d'évaluation des dialogues, panneaux, noms, nombres, négations, idiomes
   et répétitions légitimes. Préserver le nombre d'occurrences ne prouve pas à
   lui seul la fidélité de leur traduction.
5. Une comparaison entre référence et quantification, puis mesure de durée,
   mémoire maximale, consommation et chauffe sur le téléphone cible. Une
   génération interrompue ou hors langue ne doit pas produire une piste annoncée
   comme complète.
6. La même activation atomique de la piste française et la même protection de la
   narration que pour les services en ligne. La réutilisation hors ligne doit
   être testée séparément du téléchargement initial.

Ces points distinguent les contrôles techniques d’import de la validation d’un modèle en utilisation réelle. Le choix définitif d’un modèle ou d’une quantification demande toujours une comparaison sur les mêmes extraits et sur le téléphone cible. Les adaptateurs réseau et ML Kit restent disponibles séparément.

## Dépannage et choix raisonnable pour démarrer

| Symptôme | Vérification à faire |
| --- | --- |
| Google en ligne limité ou refusé | Respecter l’attente annoncée ; augmenter l’intervalle pour une prochaine tâche ; aucun réglage ne garantit l’acceptation du service |
| Modèles ML Kit indisponibles | Vérifier le Wi-Fi par défaut, l’option réseau mobile si souhaitée, l’espace et la langue ; ne pas annoncer une traduction prête pendant le téléchargement |
| Lecture en attente de traduction | Tampon validé insuffisant ; le lecteur attend pour conserver les textes, avec reprise uniquement si l’intention Lire reste valide |
| Clé refusée | Compte API, clé révoquée, endpoint du plan, région Azure ; ne pas publier la clé |
| Débit temporaire DeepL, 429/529 | Attente et reprises bornées en 0.5.1 ; consulter le statut avant une reprise manuelle |
| Quota atteint, 456 | Tableau de bord du fournisseur et plafond propre à la clé ; décider avant toute nouvelle tentative |
| Langue refusée | Code propre au fournisseur, disponibilité de la paire ; pour LibreTranslate, modèles réellement installés |
| Adresse LibreTranslate refusée | HTTPS, certificat, absence de mot de passe dans l'URL, bonne API et absence de redirection |
| Préparation impossible | Piste textuelle chargée et index complet ; des pixels ne deviennent pas du texte sans OCR |
| Français prêt mais lecture refusée | Voix française prête dans « Voix et confort » |
| Changement de vidéo ou de piste | Préparer une nouvelle traduction correspondant à la nouvelle sélection |
| Fichier Hugging Face téléchargé mais inutilisable | Choisir un GGUF compatible et attendre sa préparation ; accès, identité, profil, mémoire et backend doivent être distingués. La candidate 0.5.5 corrige la reconnaissance du GGUF officiel Hy-MT2 ; ML Kit gère ses propres modèles séparément. |

Pour commencer sans achat, **Azure F0 est un premier choix en ligne cohérent si
l'ouverture de la ressource convient**. DeepL peut servir de comparaison avec
l'accès réellement disponible sur ton compte. Un serveur LibreTranslate contrôlé
est pertinent quand tu en as déjà un ou souhaites administrer cette solution.
Google en ligne sans clé constitue une option supplémentaire à disponibilité non garantie. Pour le local, **ML Kit est utilisable après téléchargement des modèles compatibles** ; la préversion publiée 0.5.4 propose aussi le GGUF choisi sur le téléphone, avec des optimisations préparées en 0.5.5. Index-Translate-2B et les autres familles non prises en charge par les consignes de cette intégration restent des candidats à mesurer.

Le meilleur choix linguistique se décide sur les mêmes extraits et les mêmes
langues vers le français. Il faut comparer fidélité, omissions, cohérence des
noms et du registre, puis temps de préparation et ressources utilisées. Aucun
abonnement, nombre de paramètres ou score publié dans un autre contexte ne
démontre à lui seul un « meilleur absolu » pour toutes les pistes du lecteur.

## Clés, stockage et coût : précautions de ce parcours

Les clés enregistrées par le module de traduction sont chiffrées en AES-GCM avec
une clé cryptographique gérée par Android Keystore. En cas d'échec du coffre,
l'application refuse l'enregistrement au lieu de stocker le secret en clair.
Ce mécanisme protège le stockage ; il ne rend pas inoffensive une clé copiée
dans un autre logiciel ou exposée par une capture. Les comptes Azure, DeepL et
LibreTranslate restent distincts. Aucun jeton Hugging Face n'est nécessaire pour
les adaptateurs intégrés ; les deux choix Google ne demandent pas de clé API personnelle.

La case **« Mémoriser cette clé, chiffrée avec Android Keystore sur ce téléphone »**
est facultative. Le bouton **« Effacer la clé mémorisée pour ce moteur »** retire
la copie enregistrée pour le fournisseur sélectionné. Si une piste complète
correspondante est déjà en cache, le champ clé peut rester vide pour tenter sa
réutilisation ; si elle a été évincée ou corrompue, une nouvelle traduction exige
les accès requis et peut consommer le quota de nouveau.

Le transport de traduction utilise HTTPS, la validation des certificats du
système, des délais réseau et des réponses bornées. Il refuse les redirections.
DeepL **429/529** et Google en ligne suivent leurs politiques bornées respectives décrites plus haut ; elles ne doivent pas être confondues. Les erreurs ambiguës des fournisseurs facturables ne sont pas renvoyées automatiquement. Les messages destinés
à l'utilisateur ne recopient ni le corps d'erreur du prestataire ni ses secrets.
Le cache de traduction contient toutefois du texte privé de sous-titres : un
stockage privé d'application n'est pas synonyme de chiffrement de ce cache.

La confirmation montre le moteur et le volume estimé avant la traduction ; l’envoi des textes concerne seulement les moteurs en ligne. Elle
ne peut pas garantir le solde restant chez le prestataire ni modifier son
contrat : contrôle le tableau de bord du compte et ses plafonds. Une annulation
arrête le travail restant sans annuler une requête déjà traitée. Avant de
relancer une piste interrompue, prends en compte cette consommation possible.

Référence sur le mécanisme de clés :
[Android Keystore](https://developer.android.com/privacy-and-security/keystore).
