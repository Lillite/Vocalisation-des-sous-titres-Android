# Guide utilisateur — 1.1.1

[Index](README.md) · [Installer](../README.md#installer-et-commencer-sur-android) · [Sources médias](sources-medias.md) · [Modèles](modeles-traduction.md) · [État de validation](releases/1.1.1.md)

Ce guide décrit les commandes des sources 1.1.1. Les notes de version distinguent la validation de l’APK, la couverture des sources et les essais sur téléphone.

## Se repérer

L’interface utilise un thème **Material 3 sombre**, avec couleurs dynamiques sur Android 12 et suivants. La barre du bas comporte quatre onglets dont les libellés restent visibles. Un changement d’onglet montre une autre page sans recréer la session, mettre en pause, reprendre ou relancer une traduction.

| Onglet | Commandes principales |
| --- | --- |
| **Accueil** | Vidéo, Lire/Pause, déplacement, plein écran, ouverture avec le sélecteur Android, démonstration intégrée, pistes audio et textuelles, ajout de sous-titres, répétition et reprise de la voix |
| **Explorer** | Stockage partagé, carte SD accessible, sélecteur Android, connexions serveur et lien réseau direct |
| **Traduction** | Traduire la piste en français, traduire un SRT hors ligne, ouvrir le guide des modèles, comparer le calcul local |
| **Paramètres** | Panneau complet Voix et confort, accès directs à ses huit rubriques, aide |

Sur petit écran ou en grande police, faire défiler le contenu. L’onglet, le défilement et le plein écran sont des choix d’affichage, pas des permissions de relancer une lecture interrompue.

## Première lecture

1. Dans **Paramètres → Voix**, sélectionner le moteur vocal propre au lecteur, puis une voix installée et disponible. Ce choix ne change pas le moteur de TalkBack. Faire l’essai vocal.
2. Dans **Synchronisation**, choisir **Lecture intégrale** et un mode **Confort** ou **Adaptatif**.
3. Dans **Accueil**, lancer la démonstration synthétique : les textes, le son et la mire permettent de vérifier le parcours sans utiliser un film personnel.
4. Ouvrir la vidéo, sélectionner la piste textuelle et vérifier sa langue. Ajouter un SRT, VTT ou ASS/SSA si nécessaire. Une piste de sous-titres audio/graphique n’est pas une piste textuelle.
5. Appuyer sur **Lire**. La restauration d’une séance commence en pause.

La vidéo reste liée à sa source : une séance et une préparation vocale ne copient pas son fichier. Si l’accès disparaît, utiliser le parcours de réparation et sélectionner la même édition. Un titre ou une durée proches ne prouvent pas l’identité d’un montage. Lorsque l’explorateur et le sélecteur Android fournissent des métadonnées non comparables, le lecteur demande **Confirmer la même vidéo** avant de transférer position, pistes, traduction et synchronisation. En cas de doute, conserver la séance actuelle ; la confirmation de l’utilisateur n’est pas une preuve cryptographique d’identité.

## Lecture intégrale, Confort et Adaptatif

| Choix | Effet |
| --- | --- |
| **Lecture intégrale** | Préserve les occurrences extraites : dialogues, panneaux, chansons, indications, chevauchements et répétitions distinctes ; neutralise les filtres d’exclusion concernés |
| **Confort** | La vidéo attend lorsque la voix doit terminer |
| **Adaptatif** | Conserve le débit de base si possible, accélère jusqu’au plafond choisi, puis attend si nécessaire |
| **Compréhension confortable** | Base 100 %, plafond 140 %, pause de 350 ms après une phrase |
| **Compréhension équilibrée** | Base 100 %, plafond 180 %, aucune pause ajoutée |
| **Pauses rares** | Base 100 %, plafond 220 %, aucune pause ajoutée ; attente toujours possible |
| **Continu** | Choix distinct confirmé pouvant couper une phrase pour suivre la vidéo ; désactive la lecture intégrale |

Deux répliques identiques à deux instants différents restent deux occurrences. Un panneau persistant ne doit pas être relu à chaque dialogue superposé. Une traduction déjà calculée peut être réutilisée ; son occurrence doit encore être vocalisée.

**Pause utilisateur est prioritaire.** Un déplacement, un changement de média/piste, une fermeture ou une erreur invalident les retours anciens et les autorisations de reprise correspondantes. Répéter une phrase ou relire une interruption sont des actions explicites. Une fin de traduction ne donne pas une permission générale de lancer la vidéo.

Le **Bilan de vocalisation**, dans Diagnostic, indique si l’inventaire de la piste est complet. Il distingue les occurrences attendues, les fins confirmées par la voix, le passage courant, l’historique et les plages dépassées. Dans **Répliques restantes**, choisir une réplique, les répliques dépassées ou la lecture de toutes les restantes. L’image reste en pause pendant le rattrapage.

Sur un fichier réseau progressif avec une seule piste textuelle, **Lire** démarre avec les occurrences reçues au fil de la lecture. Le bilan indique une couverture progressive tant qu’une indexation complète n’a pas été explicitement demandée et terminée. Chaque occurrence reçue garde son identité, y compris une réplique brève, répétée ou arrivée après son début prévu.

Les fichiers locaux, la traduction, une synchronisation corrigée et l’association de deux pistes conservent leur indexation complète préalable. L’image peut attendre une traduction ou la fin d’une réplique longue. Le journal contient les états et les identifiants hachés. Une fin confirmée par le moteur reste distincte d’un essai d’écoute sur ton périphérique audio.

## Panneaux et OCR

Dans le choix de piste de sous-titres, ouvrir **Panneaux et OCR**. Pour des panneaux dans une seconde piste textuelle, sélectionner **Ajouter une piste de panneaux**. La voix conserve les événements des deux pistes.

Pour une piste PGS ou VobSub, importer un fichier `.traineddata` correspondant à la langue avec **Importer un modèle OCR local**, puis préparer la piste image. Vérifier et corriger les textes proposés avant de les employer en sous-titres. Les modèles sont conservés dans l’espace privé de l’application. Le calcul de reconnaissance est local.

La reconnaissance du texte incrusté peut examiner un instant ou échantillonner un intervalle d’un média local. L’échantillonnage donne une couverture partielle et des horaires à vérifier, notamment pour les textes brefs. La piste textuelle issue de la relecture a son propre inventaire.

## Trouver et modifier un réglage

Le panneau complet conserve huit rubriques : **Voix, Synchronisation, Traduction, Sous-titres, Image, Son, Stockage et Diagnostic**. La recherche ignore les accents et filtre les commandes réelles de toutes les rubriques. Chercher par exemple `vitesse`, `bandes noires`, `préparation` ou `traduction`. Effacer la recherche ou choisir une rubrique revient à sa liste.

Les réglages prennent effet immédiatement. **Fermer** les conserve ; **Annuler modifications** restaure les paramètres présents à l’ouverture du panneau. L’annulation ne retire pas un fichier exporté, ne rétablit pas une préparation supprimée et ne rembourse pas un appel déjà confirmé.

Les réglages dotés de boutons de pas proposent **Minimum** et **Maximum**, avec les mêmes bornes que la saisie précise. Ils ne changent pas de profil, ne lancent pas de calcul et ne dépassent pas une borne existante. Une saisie invalide doit rester signalée sans remplacer le réglage. Les champs libres, tels qu’un chemin serveur ou une clé, ne deviennent pas des curseurs.

Pour les couches GPU, **−1 signifie « toutes »** ; ce choix spécial reste distinct du maximum numérique 1 024. Il ne signifie pas que toutes les opérations sont effectivement accélérées ni que le matériel possède autant de couches disponibles.

## Traduire une piste ou un SRT

Dans **Traduction**, choisir le parcours vidéo ou SRT autonome, le fournisseur, les langues et les options. L’indexation et l’estimation seules n’envoient pas le texte. La confirmation autorise les appels ou le calcul choisi. Pour GGUF, commencer par le [guide des modèles](modeles-traduction.md) ; pour les comptes et options des fournisseurs, consulter le [guide des moteurs](GUIDE_MOTEURS_TRADUCTION.md), daté et soumis aux conditions actuelles du fournisseur.

- **Préparation complète** : attend la traduction validée de toute la piste avant de la déclarer complète.
- **Traduction progressive** : publie seulement les préfixes validés. Le tampon initial est de **30 secondes**, réglable de **10 à 120 secondes**. À la première occurrence indisponible, le lecteur attend.
- **Reprise** : réutilise les résultats validés et identifiés. Un résultat interrompu, incomplet ou tronqué ne devient pas une traduction terminée.
- **Export SRT/VTT** : demande une destination dans le sélecteur Android et crée un fichier UTF-8 distinct ; l’original est conservé.

La reprise automatique après une attente progressive n’est possible que si l’attente appartient encore à cette traduction et si l’intention **Lire** demeure valide. Une Pause volontaire l’annule. L’affichage peut conserver l’original, montrer le français, combiner les deux ou masquer les textes selon le choix proposé ; la narration utilise le résultat validé.

Un fournisseur peut imposer quotas, refus temporaires et facturation. L’application borne ses reprises, respecte `Retry-After` et conserve les résultats déjà validés. Elle ne peut promettre un accès gratuit permanent ni l’absence de blocage. Des caractères déjà traités peuvent être facturés même après annulation. Aucun changement opaque de fournisseur ne doit masquer un refus.

## Anticipation et conservation hors ligne

Le **cache vocal** évictable sert à anticiper ; **Conserver pour une écoute hors ligne** prépare des ressources durables par œuvre, avec vérification et suppression explicites.

| Profil | Cache | Anticipation estimée | Segments maximum |
| --- | --- | --- | --- |
| Économie | 64 Mio | 15 s | 32 |
| Équilibré, nouveau réglage initial | 512 Mio | 120 s | 256 |
| Intensif | 2 048 Mio | 300 s | 1 024 |
| Cache ample | 8 Gio | 900 s | 4 096 |

Les bornes manuelles sont **16 à 8 192 Mio**, **1 à 4 096 segments**, **5 à 7 200 secondes**. Elles plafonnent la préparation ; elles ne réservent pas immédiatement cette mémoire et ne règlent pas le processeur d’un moteur vocal tiers. Les préférences existantes sont conservées à la mise à jour.

Dans **Stockage → Préparation conservée et stockage hors ligne**, examiner espace estimé/disponible, segments et couverture temporelle, puis confirmer. L’estimation ne lance pas de synthèse. Une annulation conserve les éléments déjà produits pour une reprise ultérieure ; la narration courante reste prioritaire et une température sévère peut suspendre la préparation.

**Prêt hors ligne** exige l’index complet, la traduction complète lorsqu’elle est utilisée et tous les fichiers vocaux nécessaires réellement vérifiés. Une source partielle ne peut pas obtenir cet état. La vidéo doit rester accessible. Pour une source réseau, la conservation de la voix ne rend pas automatiquement le film disponible sans réseau. Le gestionnaire permet de supprimer séparément voix, textes et index.

## Séances et synchronisation

Une séance peut conserver position, pistes, fichier externe, voix/moteur, mode, paramètres, image, synchronisation et dernières occurrences terminées/interrompues. À la réouverture, pistes et ressources sont vérifiées ; choisir **Reprendre** ou **Relire la dernière réplique avant de reprendre**. La relecture laisse ensuite la vidéo en pause.

Si la file vocale n’était pas terminée, Reprendre retrouve son premier événement dans la piste exacte et revient au début corrigé de cette occurrence. Un déplacement volontaire annule ce repère. Si la piste ou la traduction manque, la vidéo reste en pause avec explication.

Dans **Synchronisation → Décalage, dérive et calibration des sous-titres** :

| Correction | Utilisation |
| --- | --- |
| Décalage global | Positif pour retarder les sous-titres |
| Décalage de narration | Correction propre à la voix, −60 000 à +60 000 ms |
| Deux repères source/vidéo | Corriger une dérive régulière |
| Repères supplémentaires | Interpolation continue ; temps source et vidéo strictement croissants |
| Portions | Scènes ajoutées/supprimées ; début, fin et décalage signé, sans suppression des textes |

Utiliser l’aperçu puis valider, ou Annuler pour retrouver la correction d’ouverture. Les identités sont conservées. Une réplique traversant une limite de portion garde sa durée et le décalage de son début ; les chevauchements ne suppriment pas leurs textes. Une différence de montage peut néanmoins exiger une vérification manuelle.

## Image et son

Les [commandes d’image](INTERFACE_IMAGE_ETAPE_4.md) distinguent étirement intégral, zoom, déplacement, bandes, contraste et luminosité. L’étirement conserve le cadre avec proportions modifiées. La protection du cadre empêche les transformations accidentelles ; retirer des marges demande aperçu et confirmation. Les protections HDR/Dolby Vision restent appliquées.

Les [commandes audio](COMMANDES_AUDIO_ETAPE_5.md) règlent le film, la voix, les fondus et l’atténuation pendant la narration. L’[audio expert](AUDIO_EXPERT_ETAPE_6.md) ajoute préampli, égaliseur, balance, mono et limiteur sur les formats PCM16 mono/stéréo compatibles. Ces traitements du film restent séparés du TTS. Ils ne démontrent ni passthrough, ni normalisation perceptive, ni niveau acoustique final du casque.

## Dépannage

| Observation | Vérification utile |
| --- | --- |
| Pas de voix | Voix installée, langue, essai vocal, volume et erreurs du moteur |
| Texte visible mais non vocalisé | Piste réellement textuelle, choix de piste, état de l’index et bilan des occurrences |
| Attente de traduction | Préfixe validé, connexion/fournisseur, résultat de la tâche ; ne pas contourner en sautant une occurrence |
| Source réseau lisible mais index absent | [Limites HLS/DASH/RTSP](sources-medias.md#lecture-indexation-et-vocalisation) et capacité du serveur à repositionner |
| Accès au fichier perdu | Autorisation Android, média déplacé, SD démontée ou connexion supprimée ; parcours de réparation |
| Modèle lent ou interrompu | Rapport exact demandé/effectif/observé, mémoire, thermique, phases ; [diagnostic GGUF](modeles-traduction.md#lire-les-mesures-et-les-erreurs) |
| PGS, VobSub ou texte incrusté | OCR absent ; fournir une piste textuelle externe |

Rester dans l’application pendant les traitements : l’arrière-plan durable n’est pas garanti. Les essais sur émulateur ne remplacent pas l’écoute d’une vraie voix, TalkBack, Bluetooth et une longue séance sur l’appareil cible.
