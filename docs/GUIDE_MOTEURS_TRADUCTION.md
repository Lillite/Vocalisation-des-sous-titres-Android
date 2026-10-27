# Choisir et configurer les moteurs de traduction

Guide vérifié le **24 octobre 2026**. Les offres commerciales, les langues et les
fichiers des catalogues peuvent évoluer. Les liens ci-dessous permettent de
contrôler l'offre effectivement proposée à ton compte avant toute souscription.

La politique de reprise ci-dessous décrit le **correctif candidat 0.5.1**, mis à
jour le 27 octobre 2026. Voir aussi le [guide des offres gratuites et des limites](TRADUCTION_GRATUITE_ET_LIMITES.md).

## Ce qui est disponible dans cette étape

Le parcours ajouté prépare **toute la piste textuelle avant la lecture**, traduit
vers le français, puis transmet uniquement les occurrences françaises au moteur
vocal. Les moteurs vocaux et les moteurs de traduction ont des fonctions
distinctes : une meilleure voix ne corrige pas une traduction, et une traduction
ne fournit pas nécessairement une voix.

L'affichage des sous-titres sur la vidéo conserve la piste originale : cette
étape change la source de la narration et ne promet pas un rendu visuel traduit.
Les filtres vocaux facultatifs restent applicables ; leur configuration peut
donc encore influencer les événements effectivement lus.

| Choix affiché | Où travaille le traducteur ? | Ce qu'il faut fournir | Installation de poids sur Android |
| --- | --- | --- | --- |
| Microsoft Azure Translator | Serveurs Microsoft | Clé API personnelle et région selon la ressource | Aucune |
| DeepL — point d'accès API Free | Serveurs DeepL, endpoint `api-free.deepl.com` | Clé API autorisée pour ce point d'accès | Aucune |
| DeepL — point d'accès API payant | Serveurs DeepL, endpoint `api.deepl.com` | Clé API autorisée pour ce point d'accès | Aucune |
| LibreTranslate — serveur personnel HTTPS | Serveur choisi par l'utilisateur | Adresse HTTPS, clé si l'instance l'exige | Aucune dans cette application |

**Aucun moteur de traduction exécuté sur le téléphone et aucun import de modèle
Hugging Face ne sont intégrés pour l'instant.** Les modèles locaux présentés plus
loin sont des candidats documentés pour une étape future. Leur téléchargement
ne les active pas dans ce lecteur.

Un cache conserve les pistes complètes validées dans le stockage privé de
l'application, sous réserve d'espace et de réussite de l'écriture. Il peut être
supprimé par Android. Une piste conservée peut être réutilisée sans refaire les
requêtes lorsque son identité et ses paramètres correspondent. Cela ne rend pas
local le moteur qui l'a produite. Le cache n'est pas un export SRT durable.

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
4. Appuie sur **« Indexer la piste et estimer la traduction »**. Le lecteur se
   met en pause et indexe la piste entière.
   Cette première opération sert à contrôler les occurrences et leur volume ;
   elle n'envoie pas encore le texte au traducteur.
5. Vérifie le fournisseur, le nombre d'occurrences et l'estimation affichée avant
   de confirmer avec **« Traduire / réutiliser le cache »**. L'estimation en
   unités UTF-16 n'est pas une facture
   garantie : les règles de comptage du fournisseur restent applicables.
6. Attends la fin de la traduction. Une réponse incomplète ou une erreur ne doit
   pas activer une piste incomplète en mode intégral. DeepL peut réessayer un
   refus de débit **429/529** ; les autres erreurs nécessitent une reprise explicite.
7. Si la vocalisation est activée, sélectionne une voix française prête dans
   **« Voix et confort »**. Appuie ensuite sur **« Lire »** quand la piste
   française est annoncée prête. L'application vérifie la voix française avant
   cette lecture uniquement lorsque la vocalisation est activée.

L'annulation ne restitue pas les caractères déjà consommés chez le fournisseur.
Une nouvelle demande après un échec peut donc consommer de nouveau des
caractères. Le retour à la vocalisation originale reste un choix explicite ; un
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
elles. L'adaptateur actuel n'expose pas les glossaires, le contexte additionnel
ou le choix du modèle DeepL. Ces possibilités de l'API ne sont pas annoncées
comme des commandes déjà présentes dans le lecteur.

Source technique : [traduire un texte avec DeepL](https://developers.deepl.com/api-reference/translate/request-translation).

### Débit temporaire et quota : deux situations distinctes

Une réponse **HTTP 429/529** indique une limitation temporaire du débit. Le
correctif candidat espace les appels DeepL de **500 ms** au minimum et réessaie
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
les API de ces fournisseurs et les quatre adaptateurs actuels.

Sources : [téléchargement de modèles](https://huggingface.co/docs/hub/models-downloading),
[modèles à accès contrôlé](https://huggingface.co/docs/hub/models-gated).

## Modèles et solutions locales envisageables

Cette sélection couvre plusieurs architectures utiles à comparer ; elle n'est
pas un inventaire de tous les modèles existants. **Aucun résultat de débit,
d'autonomie, de qualité ou d'accélération NPU sur OnePlus 15 n'est établi par ce
guide.** Les candidats n'ont pas été installés ni évalués sur ce téléphone.

| Candidat et source primaire | Caractéristiques documentées | Licence / distribution | Intérêt et condition pour Android |
| --- | --- | --- | --- |
| [Index-Translate-2B](https://huggingface.co/IndexTeam/Index-Translate-2B), [GGUF de l'équipe](https://huggingface.co/IndexTeam/Index-Translate-2B-GGUF) | 2 milliards de paramètres, famille fondée sur Qwen3.5 ; couverture annoncée de 150 langues | Apache-2.0 annoncée ; GGUF quantifiés publiés par IndexTeam | Premier candidat spécialisé à mesurer avec un runtime natif prenant réellement en charge cette architecture et son gabarit |
| [Google ML Kit Translation](https://developers.google.com/ml-kit/language/translation/android) | SDK Android et modèles à la demande ; plus de 50 langues | SDK et modèles soumis aux conditions Google, attribution et métriques à déclarer | Option compacte ; EN/DE/ES/IT/FR, **serbe absent** ; ajout non réalisé et collecte du SDK à décider explicitement |
| [TranslateGemma 4B IT](https://huggingface.co/google/translategemma-4b-it), [annonce Google](https://blog.google/innovation-and-ai/technology/developers-tools/translategemma/) | Famille Gemma 3 de traduction ; tailles 4B, 12B et 27B, 55 langues annoncées | Conditions Gemma applicables à ce modèle ; accès aux poids à accepter | 4B à comparer ; runtime et conversion mobile exacts nécessaires, ne pas confondre avec Gemini Nano ou ML Kit |
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
sont à appliquer. Ajouter ce SDK nécessite une décision distincte sur ces
conditions et cette collecte.

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

## Comment une future installation locale devra fonctionner

Le parcours souhaité reste entièrement utilisable sur téléphone. Pour le rendre
réel, une prochaine étape de développement devra fournir les éléments suivants,
avant de demander à l'utilisateur d'importer un gros modèle :

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

Ces points sont un cahier de travail, pas des commandes présentes. D'ici là,
les options concrètes de cette étape sont les adaptateurs réseau configurables
et la conservation locale de leurs résultats complets.

## Dépannage et choix raisonnable pour démarrer

| Symptôme | Vérification à faire |
| --- | --- |
| Clé refusée | Compte API, clé révoquée, endpoint du plan, région Azure ; ne pas publier la clé |
| Quota atteint | Tableau de bord du fournisseur et plafond configuré ; décider avant toute nouvelle tentative |
| Langue refusée | Code propre au fournisseur, disponibilité de la paire ; pour LibreTranslate, modèles réellement installés |
| Adresse LibreTranslate refusée | HTTPS, certificat, absence de mot de passe dans l'URL, bonne API et absence de redirection |
| Préparation impossible | Piste textuelle chargée et index complet ; des pixels ne deviennent pas du texte sans OCR |
| Français prêt mais lecture refusée | Voix française prête dans « Voix et confort » |
| Changement de vidéo ou de piste | Préparer une nouvelle traduction correspondant à la nouvelle sélection |
| Fichier Hugging Face téléchargé mais inutilisable | Aucun import local n'existe encore ; vérifier le futur couple runtime/modèle avant de télécharger autre chose |

Pour commencer sans achat, **Azure F0 est un premier choix en ligne cohérent si
l'ouverture de la ressource convient**. DeepL peut servir de comparaison avec
l'accès réellement disponible sur ton compte. Un serveur LibreTranslate contrôlé
est pertinent quand tu en as déjà un ou souhaites administrer cette solution.
Pour le local, Index-Translate-2B mérite un prototype mesuré ; ML Kit est une
autre piste compacte avec des limites et métriques à accepter explicitement.

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
les quatre adaptateurs intégrés.

La case **« Mémoriser cette clé, chiffrée avec Android Keystore sur ce téléphone »**
est facultative. Le bouton **« Effacer la clé mémorisée pour ce moteur »** retire
la copie enregistrée pour le fournisseur sélectionné. Si une piste complète
correspondante est déjà en cache, le champ clé peut rester vide pour tenter sa
réutilisation ; si elle a été évincée ou corrompue, une nouvelle traduction exige
les accès requis et peut consommer le quota de nouveau.

Le transport de traduction utilise HTTPS, la validation des certificats du
système, des délais réseau et des réponses bornées. Il refuse les redirections.
Seuls les refus explicites de débit DeepL **429/529** autorisent les nouvelles
tentatives bornées décrites plus haut ; les autres erreurs ne sont pas relancées. Les messages destinés
à l'utilisateur ne recopient ni le corps d'erreur du prestataire ni ses secrets.
Le cache de traduction contient toutefois du texte privé de sous-titres : un
stockage privé d'application n'est pas synonyme de chiffrement de ce cache.

La confirmation montre le fournisseur et le volume estimé avant l'envoi. Elle
ne peut pas garantir le solde restant chez le prestataire ni modifier son
contrat : contrôle le tableau de bord du compte et ses plafonds. Une annulation
arrête le travail restant sans annuler une requête déjà traitée. Avant de
relancer une piste interrompue, prends en compte cette consommation possible.

Référence sur le mécanisme de clés :
[Android Keystore](https://developer.android.com/privacy-and-security/keystore).
