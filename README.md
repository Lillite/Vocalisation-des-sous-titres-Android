# Sous-titres en voix

Lecteur vidéo Android destiné d’abord à **vocaliser intégralement les occurrences textuelles de la piste choisie** : dialogues, panneaux, chevauchements et répétitions réelles. Les modes Confort et Adaptatif donnent à la voix le temps de terminer ; une traduction manquante provoque une attente perceptible.

**Version publiée recommandée : 0.5.2 — Google et traduction pendant la lecture.** [Notes 0.5.2](docs/releases/0.5.2.md) · [Guide Google](docs/TRADUCTION_GOOGLE_052.md) · [Validation GitHub Actions](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/workflows/android.yml).

**Candidate en préparation : 0.5.4-local / code 11.** Traduction des pistes textuelles avec un GGUF choisi sur le téléphone, progression conservée et export SRT/VTT. [Guide GGUF local](docs/TRADUCTION_GGUF_054.md) · [Notes et limites](docs/releases/0.5.4-local.md). La distribution exige la validation et la signature durable. La [0.5.3-diagnostic publiée](docs/releases/0.5.3-diagnostic.md) reste disponible pour les mesures locales.

**[Télécharger SousTitresVoix-0.5.2-google.apk](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/download/v0.5.2/SousTitresVoix-0.5.2-google.apk)** · [Release et sommes de contrôle](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.5.2). Les archives « Source code » ne sont pas des APK.

[Installation](#installer-et-commencer-sur-android) · [Tous les guides](docs/README.md) · [Nouveautés 0.5.2](docs/releases/0.5.2.md) · [Tests et preuves](TESTING.md) · [Signaler un problème](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/issues/new/choose)

| Repère | Version publiée 0.5.2 |
| --- | --- |
| Édition | **Google et traduction pendant la lecture**, code 9 ; fonctions précédentes conservées |
| Android minimum | **Android 8.0 — API 26** |
| Identité Android | **fr.soustitresvoix.player.translation**, conservée depuis la 0.4.0 |
| Narration | Lecture intégrale, Confort, Adaptatif, suivi par occurrence et relecture explicite |
| Traduction | Google en ligne sans clé, Google local ML Kit, Azure Translator, DeepL API Free/payant et LibreTranslate HTTPS ; préparation complète ou progressive sur choix explicite |
| Préparation | Anticipation vocale, cache réglable et conservation durable par œuvre |
| Validation | APK de test et suites avant fusion ; [preuves, résultats et limites](TESTING.md), [exécutions Actions](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/workflows/android.yml) |

**Ce projet est réalisé intégralement par intelligence artificielle : la conception de l’application, l’écriture du code, les corrections, les tests automatisés et la documentation sont produits par IA à partir des demandes, des indications et des retours de son utilisateur.** Cette mention concerne le travail propre au projet. Android, Kotlin, Media3, les moteurs vocaux, les services de traduction et les autres composants tiers restent les créations de leurs auteurs respectifs.

## Candidate 0.5.4 — traduction GGUF locale

Le moteur GGUF local s’intègre au parcours **Traduire les sous-titres en français**. Le modèle est choisi explicitement, copié dans l’espace privé et identifié par son contenu. Il est chargé directement par llama.cpp sur le CPU ; PocketPal, une clé API et un serveur ne sont pas nécessaires. Les poids ne sont pas inclus ni téléchargés automatiquement.

La préparation conserve les identifiants et minutages de chaque occurrence. Les résultats validés sont enregistrés pour reprise ; une sortie interrompue ou tronquée ne devient pas une traduction complète. L’export produit un nouveau SRT ou VTT sans remplacer l’original. Le mode progressif utilise les mêmes protections d’attente, de Pause et de déplacement que les moteurs existants.

Pour un premier essai sans vidéo, ouvrir **Traduire un fichier SRT hors ligne**, puis **Charger l’exemple SRT anglais intégré**. Cet exemple contient huit occurrences synthétiques et ne lance ni lecture ni traduction avant confirmation.

Le choix définitif du meilleur modèle reste ouvert. Le [guide GGUF](docs/TRADUCTION_GGUF_054.md) précise les familles prises en charge, le contexte borné et les limites CPU. L’OCR, le GPU et le NPU ne sont pas intégrés par cette étape. Les tests synthétiques ne mesurent pas la qualité linguistique ni le débit soutenu du téléphone.

## Préversion publiée 0.5.3 — diagnostic local

La [préversion 0.5.3 publiée](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.5.3-diagnostic) propose **Réglages de la voix et du confort → Diagnostic → Diagnostic local : appareil, vidéo et modèle** : RAM et codecs déclarés, décodage d’au plus 8 images sur 10 secondes, puis import consenti et essai synthétique d’un modèle GGUF avec llama.cpp sur CPU dans un processus distinct. Le rapport est copiable ; aucun modèle ni film n’est téléversé, aucun poids n’est téléchargé automatiquement et aucun nouveau service Internet n’est ajouté.

Le GPU/NPU n’est pas exécuté. Le diagnostic ne produit ni OCR ni traduction de film ; l’intégration de traduction GGUF de la candidate 0.5.4 est une étape distincte. Le [guide](docs/DIAGNOSTIC_LOCAL_053.md) distingue les essais de la qualité d’une traduction ; les [notes](docs/releases/0.5.3-diagnostic.md) conservent les preuves de cette publication.

## Changements de la 0.5.2

- **Google en ligne sans clé** : adaptateur natif avec appels modérés, intervalle configurable, reprises temporaires bornées et résultats conservés. L’accès est non officiel ; aucun quota illimité ni fonctionnement permanent n’est garanti.
- **Google local — ML Kit** : modèles téléchargés sur demande, Wi-Fi par défaut, option réseau mobile et langues vérifiées. La traduction s’exécute sur le téléphone lorsque les modèles nécessaires sont disponibles.
- **Visionnage pendant la traduction** : tampon de 30 secondes par défaut, réglable de 10 à 120 secondes. Le lecteur attend avant un texte manquant et ne reprend automatiquement que sa propre attente de traduction, avec une intention Lire toujours valide.
- **Audio avancé** : le bouton des traitements DSP de la rubrique Son ouvre maintenant une fenêtre rattachée au véritable panneau. L’égaliseur et ses éditeurs restent fermés avec leur parent.
- **Vocalisation intégrale** : chaque occurrence, panneau, chevauchement et répétition réelle reste conservé. Réutiliser une traduction identique économise un appel sans supprimer une vocalisation.
- **Livraison** : code, régressions et documentation regroupés ; APK construit et testé avant chaque fusion, puis release reconstruite sans répéter les suites, avec preuve de provenance et de contenu.

Les [notes 0.5.2](docs/releases/0.5.2.md) et le [guide Google](docs/TRADUCTION_GOOGLE_052.md) donnent les limites précises. Les corrections de défilement et de limites DeepL de la [0.5.1](docs/releases/0.5.1.md), ainsi que les fonctions [Confort intégral](docs/CONFORT_INTEGRAL_050.md), restent conservées.

## Installer et commencer sur Android

La 0.5.2 conserve l’identité Android de la série Traduction (0.4.0 et 0.5.x). Sa publication exige la vérification du même certificat durable pour permettre une mise à jour en conservant les données. **Ne pas désinstaller l’édition Traduction pour préparer cette mise à jour.** La 0.3.2 reste une autre application, avec sa propre identité et ses propres données.

Télécharger **[SousTitresVoix-0.5.2-google.apk](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/download/v0.5.2/SousTitresVoix-0.5.2-google.apk)** et comparer son empreinte au fichier [SHA256SUMS](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/download/v0.5.2/SHA256SUMS) de la même release.

1. Ouvrir **Réglages de la voix et du confort**.
2. Dans **Voix**, choisir le moteur vocal de ce lecteur et une voix installée dans la langue à prononcer. Écouter un essai ; le choix ne modifie pas le moteur de TalkBack.
3. Dans **Synchronisation**, utiliser **Préréglage : lecture intégrale** ou **Préréglage : compréhension confortable**.
4. Lancer **Essai intégré : sous-titres et mire de cadrage** pour vérifier voix, commandes et image avec un contenu synthétique.
5. Ouvrir une vidéo locale et sélectionner sa piste textuelle, ou ajouter un fichier SRT, VTT, ASS/SSA. Une piste française peut être vocalisée directement ; une traduction constitue une opération supplémentaire.
6. Appuyer explicitement sur **Lire**. Une restauration ou une préparation ne démarre pas seule la vidéo. En mode progressif, seule une attente causée par la traduction peut reprendre automatiquement si cette intention Lire reste valide ; une Pause volontaire reste prioritaire.

La lecture dépend des codecs disponibles et d’une voix Android réellement prête. Les sous-titres PGS/VobSub ou uniquement incrustés dans les pixels demandent un OCR qui reste absent de cette version.

## Trouver et modifier un réglage

Les commandes sont réparties entre **Voix, Synchronisation, Traduction, Sous-titres, Image, Son, Stockage et Diagnostic**. Le sélecteur de rubrique et la recherche se trouvent au début du panneau défilant ; les boutons Fermer et Annuler modifications gardent leur zone dédiée, même en grande police.

La recherche filtre les commandes réelles dans toutes les rubriques, sans tenir compte des accents : **vitesse**, **voix**, **bandes noires** ou **traduction** donnent accès aux contrôles correspondants. Effacer la recherche ou choisir une rubrique revient à sa liste de commandes.

Depuis la 0.5.1, toucher un curseur, une case ou un bouton libère le focus de saisie resté sur un champ éloigné ; le panneau conserve ainsi sa position. Toucher directement un champ permet toujours de le modifier.

Les réglages prennent effet immédiatement. **Fermer** les conserve ; **Annuler modifications** restaure les paramètres présents à l’ouverture du panneau. Cette annulation ne rétablit pas un fichier supprimé, ne retire pas un export et n’annule pas les opérations ou appels API déjà confirmés.

Les valeurs numériques concernées proposent un champ précis avec **Valider**, des boutons +/−, des présélections et un curseur lorsqu’il est adapté. Une saisie invalide produit un message et ne remplace pas le réglage. Après un changement de profil, les valeurs affichées sont actualisées immédiatement.

## Donner la priorité à la vocalisation intégrale

| Choix | Comportement |
| --- | --- |
| **Lecture intégrale** | Préserve les dialogues, inscriptions et indications textuelles extraites de la piste choisie ; les filtres d’exclusion sont neutralisés. |
| **Confort** | La vidéo attend quand la voix doit terminer. |
| **Adaptatif** | Conserve le débit de base quand il suffit, accélère jusqu’au plafond choisi puis attend si nécessaire. |
| **Compréhension confortable** | Présélection à 100 % de débit de base, plafond adaptatif à 140 % et pause de 350 ms après une phrase, avec conservation des phrases. |
| **Compréhension équilibrée** | Présélection à 100 % de débit de base et plafond adaptatif à 180 %, sans pause ajoutée. |
| **Pauses rares** | Présélection à 100 % de débit de base et plafond adaptatif à 220 %, sans pause ajoutée ; la vidéo attend si nécessaire pour terminer. |
| **Continu** | Autorisation distincte après confirmation : peut couper une phrase pour suivre la vidéo et désactive la lecture intégrale. |

Deux événements portant les mêmes mots à des instants distincts restent deux occurrences. Un panneau persistant ne doit pas être relu à chaque dialogue superposé. Le suivi distingue détection, préparation, attente, commande vocale commencée, fin, interruption, erreur et exclusion explicite.

Le **Bilan de vocalisation** compte les occurrences distinctes terminées au moins une fois et les interruptions à reprendre. Le journal conserve des identifiants hachés, des états et des temps, sans le texte des sous-titres. « Commencée » désigne la commande de synthèse émise, pas une mesure acoustique du premier son. Ce bilan porte sur les occurrences effectivement extraites ; une source non prise en charge ou un index incomplet produit une limite perceptible.

Répéter, relire une interruption et reprendre restent des actions explicites. Une pause demandée par l’utilisateur doit conserver sa priorité sur les réponses tardives d’un moteur ou d’une traduction.

## Préparer la voix et conserver une œuvre hors ligne

Deux fonctions répondent à des usages différents :

| Fonction | Usage |
| --- | --- |
| **Cache vocal** | Anticipation réglable pendant la lecture ; les entrées anciennes peuvent être évincées. |
| **Conserver pour une écoute hors ligne** | Préparation durable par œuvre, avec contrôle des segments nécessaires et de leur intégrité ; suppression explicite par l’utilisateur. |

Pour une nouvelle configuration, les valeurs de départ sont **512 Mio de cache**, **256 segments maximum** et **120 secondes d’audio anticipé estimé**. Les préférences existantes restent conservées lors d’une mise à jour.

| Profil | Cache | Anticipation estimée | Segments maximum |
| --- | --- | --- | --- |
| Économie | 64 Mio | 15 s | 32 |
| Équilibré | 512 Mio | 120 s | 256 |
| Intensif | 2 048 Mio | 300 s | 1 024 |

Les bornes manuelles sont de **16 à 8 192 Mio**, **1 à 4 096 segments** et **5 à 7 200 secondes**. Ces valeurs sont des plafonds de préparation, pas une réservation immédiate de mémoire ni une accélération artificielle du GPU/NPU. La narration courante reste prioritaire ; la préparation peut être arrêtée en cas d’état thermique sévère.

Dans **Stockage**, ouvrir **Préparation conservée et stockage hors ligne**. L’application présente l’estimation d’espace, la place disponible, le nombre de segments prêts et la couverture temporelle vérifiée. L’estimation ne démarre pas une synthèse. Confirmer la conservation, puis consulter l’état jusqu’à la vérification finale.

**Prêt hors ligne** demande un index et une traduction complets lorsqu’elle est utilisée, ainsi que les fichiers vocaux requis effectivement validés. Une source partielle ne peut pas obtenir ce statut. La vidéo doit toujours être accessible ; cette commande ne copie pas son fichier original. Une interruption conserve les éléments déjà produits pour une préparation ultérieure. Le gestionnaire permet de supprimer séparément voix, textes et index d’une œuvre.

## Retrouver une séance et corriger la synchronisation

Les séances conservent la position, les pistes audio/textuelles, le fichier externe associé, la voix, le moteur, le mode d’écoute, les réglages, l’image, la synchronisation et les dernières occurrences terminées ou interrompues. Les ressources de traduction et les pistes sont vérifiées à la réouverture.

La restauration commence en pause et propose **Reprendre** ou **Relire la dernière réplique avant de reprendre**. La relecture laisse ensuite la vidéo en pause. Si le fichier n’est plus accessible, un parcours guidé permet de le sélectionner de nouveau, de réessayer ou d’oublier seulement la séance. Choisir la même édition ; des métadonnées compatibles ne prouvent pas que deux fichiers sont identiques.

Si la file vocale était encore inachevée à la fermeture, **Reprendre** retrouve son premier événement dans la piste exacte et revient au début corrigé de cette occurrence avant de lire. Cela préserve une réplique dont l’affichage avait déjà expiré pendant que la vidéo attendait la voix. Un déplacement volontaire annule ce repère ; une piste ou une traduction indisponible maintient la pause avec un message.

Dans **Synchronisation**, ouvrir **Décalage, dérive et calibration des sous-titres** :

- **Décalage global**, en millisecondes : positif pour retarder les sous-titres.
- **Décalage de narration** : correction propre à la voix, de −60 000 à +60 000 ms.
- **Deux repères source/vidéo** : correction d’une dérive régulière.
- **Repères supplémentaires** : interpolation continue, avec temps source et vidéo strictement croissants.
- **Portions pour scènes ajoutées/supprimées** : début, fin et décalage signé par portion, sans suppression des textes ; ajout et retrait explicites.
- **Aperçu**, puis validation ou **Annuler** : une annulation restaure la correction d’ouverture.

Les corrections conservent les identités des occurrences et sont associées à la vidéo/piste. Une réplique traversant une limite de portion garde toute sa durée et le décalage de son début. Les chevauchements corrigés conservent tous leurs textes. Une différence de montage contenant des dialogues absents de l’image exige une vérification manuelle.

## Traduire en français, entièrement ou progressivement

La traduction complète reste disponible. L’option **Traduction progressive** permet de commencer avec un préfixe validé et **30 secondes d’avance par défaut**, réglables de **10 à 120 secondes**. Le travail continue pendant le visionnage. Si la lecture le rattrape, la vidéo attend avant une occurrence indisponible. Quand le tampon est reconstitué, elle peut reprendre uniquement si cette attente provenait de la traduction et si l’intention Lire reste valide. Une Pause utilisateur, un déplacement ou un changement de contexte empêchent une reprise tardive. Aucune phrase manquante n’est sautée ni remplacée silencieusement par l’original.

1. Choisir une piste textuelle, puis ouvrir **Traduire les sous-titres en français**.
2. Choisir le moteur et la langue source ; fournir les accès seulement si ce moteur les demande. La langue est celle des sous-titres, pas nécessairement celle de l’audio.
3. Choisir éventuellement le mode progressif, son avance et un **plafond de nouveaux caractères UTF-16 pour cette tâche**. Pour Google en ligne, l’intervalle conseillé est 1 000 ms ; pour ML Kit, les modèles se téléchargent par défaut en Wi-Fi.
4. Utiliser **Indexer la piste et estimer la traduction** : cette phase n’envoie pas le texte.
5. Examiner le volume restant après réutilisation du cache/progression, la destination et les limites ; confirmer l’envoi.
6. Choisir une voix française prête, puis **Lire** lorsque la piste complète ou le tampon progressif est disponible.

Les lots validés sont enregistrés pour reprise avec la même source et les mêmes paramètres. Une requête interrompue dont le résultat est inconnu peut déjà avoir été facturée : sa reprise demande une confirmation et peut consommer de nouveau du quota. Aucun redémarrage après fermeture n’est garanti sans réouverture et nouvelle confirmation.

### Fournisseurs et options

| Fournisseur | Configuration et fonctions |
| --- | --- |
| **Google en ligne sans clé** | Accès non officiel ; intervalle de 500 à 10 000 ms, défaut 1 000 ms ; trois nouvelles tentatives temporaires au maximum, avec attente cumulée bornée à 120 s par requête, hors réseau, et respect de `Retry-After`. |
| **Google local — ML Kit** | SDK 17.0.3, modèles téléchargés sur demande, Wi-Fi par défaut ; paire de langues contrôlée et attribution Google. |
| **GGUF local — candidate 0.5.4** | Modèle choisi et copié sur le téléphone, calcul CPU et reprise par occurrence ; [guide GGUF](docs/TRADUCTION_GGUF_054.md). |
| **Azure Translator** | Ressource Translator, clé et région selon le compte ; langue source explicite. |
| **DeepL API Free ou payant** | Point d’accès correspondant au contrat ; contexte facultatif, identifiant/révision d’un glossaire déjà créé, modèle et registre lorsque pris en charge ; consultation réelle du quota. |
| **LibreTranslate HTTPS** | Serveur choisi, paire de langues installée et clé éventuelle ; traitement sur ce serveur. |

Le [guide Google et lecture progressive](docs/TRADUCTION_GOOGLE_052.md) explique les nouveaux moteurs et leurs limites ; le [guide des moteurs](docs/GUIDE_MOTEURS_TRADUCTION.md) détaille les autres comptes et points d’accès. Les offres et droits dépendent du fournisseur ; le lecteur ne crée ni compte ni abonnement. Le plafond local de caractères n’est pas un prix garanti, une limite contractuelle ou une réservation de quota. Pour Azure/LibreTranslate, consulter le tableau de bord ou l’administrateur pour les quotas.

Le contexte DeepL est envoyé avec les lots. Les options et révisions font partie de l’identité des résultats conservés. Une proposition locale de langue doit être confirmée ; elle ne transforme pas automatiquement une langue refusée en une langue voisine.

### Afficher, corriger, conserver et exporter

- Choisir **original**, **français**, **bilingue** ou **masqué**, indépendamment de la narration.
- Consulter les alertes sur les nombres et longueurs inhabituelles ; ce sont des indices à vérifier, pas une mesure de fidélité.
- Corriger une occurrence de la traduction complète ; sa voix dépend ensuite du nouveau texte.
- **Conserver durablement** la traduction complète ou l’exporter en **SRT/VTT** via le sélecteur Android.
- Réimporter l’export avec **Ajouter un fichier de sous-titres** : il devient une piste française autonome.
- Comparer explicitement un échantillon sur plusieurs configurations, avec confirmation des destinations et de chaque consommation possible.

Le cache évictable de traduction reste limité à **32 Mio**. Progression, conservation durable et export sont distincts. Les exports préservent textes et minutages, mais pas l’ensemble des styles avancés ASS.

## Régler l’image et le cadrage

Ouvrir **Plein écran cinéma**, puis **Image / zoom**. Les commandes possèdent des explications, des remises à zéro ciblées et des saisies précises pour les corrections. Le [guide complet de l’image](docs/INTERFACE_IMAGE_ETAPE_4.md) décrit chaque commande, ses unités, sa durée de vie et ses limites.

| Réglage | Effet et utilisation |
| --- | --- |
| **Image entière, proportions originales** | Conserve l’ensemble de l’image ; des bandes restent possibles. Rétablit zoom 1,00×, centrage et absence de marges retirées. |
| **Tout l’écran — image entière étirée** | Remplit la surface avec toute l’image en déformant ses proportions. Le raccourci **Étirement intégral** choisit ce mode. |
| **Tout l’écran, image recadrée** | Remplit la surface en coupant des bords ; confirmation nécessaire sous protection du cadre. |
| **Protection, zoom et déplacements** | La protection empêche les transformations accidentelles. Après désactivation, zoom de 1,00× à 4,00× par pas de 0,25× et déplacements par boutons ; **Recentrer / réinitialiser** revient à 1,00×. |
| **Contraste** | De −0,30 à +0,30, zéro neutre ; boutons par 0,01 et saisie décimale. Un excès peut masquer les détails. |
| **Luminosité de l’image** | De −0,20 à +0,20, zéro neutre ; modifie les pixels, séparément de la luminosité de l’écran. |
| **Comparer à l’original** | Neutralise temporairement corrections, marges retirées et Lanczos en conservant leurs réglages. Cadrage et zoom restent séparés. |
| **Diagnostic et marges** | Analyse locale de sept images avec aperçu et confirmation avant suppression des marges. Android 8.1 minimum pour l’analyse ; scènes sombres ou texte dans les bandes peuvent tromper la proposition. |
| **Lanczos** | Filtre facultatif de redimensionnement, limité par la surface et un grand côté de 3 840 pixels. Comparer netteté et fluidité ; il ne recrée pas les détails absents. |
| **Luminosité de l’écran** | Consigne de fenêtre de 1 à 100 %, par 10 points, et retour au réglage système. Ce pourcentage ne mesure pas les nits. |

La mire intégrée montre quatre coins colorés et un cercle : les coins permettent de constater une coupe ; le cercle devient une ellipse lors d’un étirement. Les traitements concernés restent neutralisés sous protection HDR/Dolby Vision. Le diagnostic distingue les valeurs **demandées**, **transmises** et **observées** ; une commande acceptée ne mesure pas la qualité des pixels.

## Régler le son du film

En 0.5.2, le bouton des traitements audio avancés de **Réglages de la voix et du confort → Son** utilise le véritable panneau comme propriétaire : la fenêtre peut s’ouvrir et ses sous-dialogues se ferment avec elle. Le parcours **Volumes rapides** reste disponible.

Le volume de la voix reste séparé de celui du film. Dans **Volumes rapides**, ouvrir **Traitements audio avancés du film** pour la chaîne experte. Les [commandes audio](docs/COMMANDES_AUDIO_ETAPE_5.md) et le [guide audio expert](docs/AUDIO_EXPERT_ETAPE_6.md) expliquent les unités, les bornes, les transitions et les retours aux défauts.

| Mixage | Utilisation et défaut |
| --- | --- |
| **Volume normal du film** | De 0 à 100 %, défaut 100 %. Zéro coupe le film. |
| **Film pendant la voix** | De 0 à 100 % du niveau normal, défaut 25 %. Exemple : 60 % × 25 % donne une cible de 15 %. |
| **Baisse, remontée et attente** | De 0 à 2 000 ms ; défauts respectifs 160, 500 et 350 ms. Les nouvelles durées servent aux prochaines transitions ; l’anticipation d’une réplique peut prolonger l’attente. |
| **Atténuation spectrale expérimentale** | Désactivée par défaut. Atténue la bande du film autour de 250 à 3 200 Hz pendant la parole ; affecte aussi musique et sons dans cette bande, sans séparer les sources. |

La saisie précise accepte les pourcentages et durées dans leurs champs, avec point ou virgule pour les décimales prévues, erreur visible et annulation. Une remise à zéro individuelle conserve les autres paramètres.

| Traitement avancé du film | Réglages et portée |
| --- | --- |
| **Activation générale** | **Désactivée par défaut**. Les valeurs peuvent être préparées sans appliquer la chaîne ; la voix narrée conserve son parcours séparé. |
| **Préampli** | De −24 à +12 dB, défaut 0 dB. Une baisse réserve de la marge avant les gains d’égalisation. |
| **Égaliseur cinq bandes** | Activation par bande, fréquence de 20 à 20 000 Hz, gain de −12 à +12 dB, Q de 0,1 à 10. Q élevé resserre la correction. Fréquences initiales : 60, 250, 1 000, 4 000 et 12 000 Hz ; gains nuls, Q = 1, bandes désactivées. Une fréquence incompatible avec le flux n’est pas appliquée. |
| **Balance** | De −100 à +100 %, centre 0 %. Atténue le côté opposé sans amplifier le côté favorisé ; sans effet sur une entrée mono. |
| **Mélange mono** | Désactivé par défaut. Moyenne les canaux stéréo ; des signaux en opposition de phase peuvent s’annuler. |
| **Limiteur de crêtes** | Présélectionné, mais inactif tant que la chaîne générale est désactivée. Plafond de −12 à 0 dBFS, défaut −1 dBFS ; relâchement de 10 à 2 000 ms, défaut 100 ms. Le gain est lié entre les canaux. |

La chaîne avancée traite le **PCM16 mono/stéréo effectivement reçu**. Elle n’annonce ni multicanal ou passthrough, ni normalisation du volume perçu, ni limiteur *true-peak*. Une crête numérique ou un compteur de buffers ne mesure pas la qualité acoustique. Réglages et diagnostic ne doivent ni créer une synthèse supplémentaire ni reprendre une vidéo en pause.

## Compatibilité, confidentialité et limites

| Domaine | Prise en charge et limites |
| --- | --- |
| Vidéos | Fichiers locaux, notamment MKV, MP4 et WebM ; codecs selon Media3 et les décodeurs de l’appareil. |
| Sous-titres | Pistes textuelles intégrées et fichiers SRT, VTT, ASS/SSA ; import externe limité à 8 Mio, avec choix d’encodage pour les fichiers anciens. |
| Narration | Moteur TTS Android externe et voix installée ; les modes intégraux attendent si nécessaire. |
| Traduction | Services en ligne choisis, Google ML Kit sur le téléphone ou GGUF local dans la candidate 0.5.4 ; cible française ; langues et fidélité à vérifier pour chaque moteur. |
| Hors ligne | ML Kit après téléchargement ou GGUF local compatible choisi dans la candidate 0.5.4 ; voix Android prête et vidéo accessible pour la lecture ; traductions/voix préparées réutilisables après contrôle. |
| Fonctions absentes | OCR PGS/VobSub/incrustations, catalogue/téléchargement automatique Hugging Face, Google Cloud/Apps Script, audiodescription, accès aux DRM d’autres applications et lecture durable en arrière-plan. |
| Matériel | Aucune preuve d’usage neuronal GPU/NPU ou de performance sur OnePlus 15 déduite des profils de cache ou de tests sur émulateur. |

- Ouverture par le sélecteur Android, sans permission microphone/caméra ni accès général au stockage.
- Un moteur en ligne confirmé envoie le texte et ses paramètres au fournisseur choisi ; il n’envoie ni vidéo ni piste audio. ML Kit traduit le texte sur l’appareil : Google déclare une collecte de télémétrie technique du SDK (appareil, application, performances, tailles, langues et événements), sans envoi du texte des sous-titres pour la traduction. Son initialisation et les téléchargements sont précédés d’une confirmation ; le mode local n’exclut pas toute activité réseau. Voir la [déclaration Google](https://developers.google.com/ml-kit/android-data-disclosure).
- Le moteur GGUF de la candidate 0.5.4 charge la copie locale choisie et ne crée aucun échange réseau. Il reste indépendant de l’initialisation de ML Kit et des moteurs en ligne.
- HTTPS, certificats système, tailles/délais bornés et redirections refusées ; erreurs sans clé ni corps privé.
- Clés mémorisées facultativement par Android Keystore/AES-GCM, avec portée distincte par serveur LibreTranslate ; sauvegarde Android désactivée.
- Sous-titres importés, traductions conservées et textes de préparation sont des données privées de l’application, sans chiffrement additionnel annoncé. Les journaux ordinaires de narration n’enregistrent pas les textes.
- Les suppressions de cache, d’index ou de préparation ne modifient pas les vidéos originales. Les exports restent dans l’emplacement choisi.
- Les permissions et traitements d’un moteur vocal externe dépendent de son fournisseur.

Le [guide 0.5.0](docs/CONFORT_INTEGRAL_050.md) détaille les contraintes de reprise, les limites et la conservation. Le [catalogue vocal](docs/VOIX_CATALOGUE_2026.md), l’[étude de traduction locale](docs/ETUDE_TRADUCTION_LOCALE.md) et l’[étude d’accélération](docs/ETUDE_ACCELERATION.md) séparent les ressources disponibles des pistes de recherche.

## Validation et identité du téléchargement

Les preuves de la **0.5.2** sont rattachées à son propre arbre de sources et à son APK. [TESTING.md](TESTING.md), les [exécutions GitHub Actions](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/workflows/android.yml) et les fichiers de la [release](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.5.2) donnent les résultats, la provenance et les empreintes vérifiables. Les résultats antérieurs restent attachés à leurs versions.

Avant chaque fusion, GitHub Actions doit construire l’APK de test et l’exécuter sur l’émulateur en plus des tests du noyau, des tests JVM et de lint. Après réussite et fusion, la release est reconstruite sans rejouer ces suites ; le workflow contrôle l’arbre, les parents et le contenu applicatif par rapport à l’APK testé. La signature durable et les fichiers publiés sont ensuite vérifiés.

Les **524 tests de la 0.5.1** et son APK restent une [référence historique distincte](docs/releases/0.5.1.md), avec une [preuve structurée](docs/verification/0.5.1.json). Ils ne valident pas les ajouts de la 0.5.2.

Les tests utilisent des contenus synthétiques, des réponses API contrôlées et un service vocal de test. Fidélité des fournisseurs, droits/quota d’un compte, voix commerciales, TalkBack réel, Bluetooth, HDR physique, autonomie et chauffe demandent leurs essais distincts. Une réponse réelle ponctuelle de Google ne prouve pas sa disponibilité permanente.

## Documentation et développement

| Besoin | Point d’entrée |
| --- | --- |
| Utiliser les nouvelles fonctions | [Google et lecture progressive 0.5.2](docs/TRADUCTION_GOOGLE_052.md), [fonctions Confort intégral](docs/CONFORT_INTEGRAL_050.md) |
| Lire les changements de cette version | [Notes 0.5.2](docs/releases/0.5.2.md), [changelog](CHANGELOG.md) |
| Parcourir tous les guides | [Index documentaire](docs/README.md) |
| Configurer les comptes de traduction | [Moteurs et dépannage](docs/GUIDE_MOTEURS_TRADUCTION.md) |
| Tester sur téléphone | [Parcours manuel](docs/TESTS_TELEPHONE.md) |
| Régler image et son | [Image](docs/INTERFACE_IMAGE_ETAPE_4.md), [mixage](docs/COMMANDES_AUDIO_ETAPE_5.md), [audio expert](docs/AUDIO_EXPERT_ETAPE_6.md) |
| Compiler et contrôler les livraisons | [Procédure et résultats](TESTING.md) |
| Comprendre les composants | [Architecture](docs/ARCHITECTURE.md), [modularisation](docs/MODULARISATION_ETAPE_1.md) |
| Contribuer | [Contribution](CONTRIBUTING.md), [consignes agents](AGENTS.md) |
| Retrouver les anciennes étapes | [Versions et branches](docs/HISTORIQUE_BRANCHES.md), [notes 0.4.0](docs/releases/0.4.0.md) |

Le dépôt contient l’application Android dans **app/**, le moteur indépendant d’Android dans **core/**, les guides dans **docs/**, les démonstrations synthétiques dans **examples/** et les contrôles dans **tools/**. La compilation utilise JDK 17 ; les tests Robolectric Android 36 utilisent JDK 21. FFmpeg génère le média de démonstration à la compilation ; il n’est pas nécessaire sur le téléphone.

**Tout travail terminé et vérifié est intégré à main avant d’être annoncé livré.** Une proposition de fusion et ses contrôles précèdent cette intégration, puis le commit de main est vérifié. Les tags publiés conservent les sources exactes des APK antérieurs. Les guides des étapes 1 à 7 décrivent leur version de référence ; le guide Confort intégral décrit les fonctions ajoutées en 0.5.0 et les notes 0.5.1 et 0.5.2 précisent les évolutions suivantes.
