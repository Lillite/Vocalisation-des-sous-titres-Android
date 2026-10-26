# Sous-titres en voix

Lecteur vidéo Android destiné d’abord à **vocaliser intégralement les occurrences textuelles de la piste choisie** : dialogues, panneaux, chevauchements et répétitions réelles. Les modes Confort et Adaptatif donnent à la voix le temps de terminer ; une traduction manquante provoque une attente perceptible.

**Version 0.5.0 — Confort intégral : validation et publication en cours.** Cette page décrit les sources de cette version. Le téléchargement publié reste pour l’instant [l’APK 0.4.0](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/download/v0.4.0/SousTitresVoix-0.4.0-traduction.apk), dont les [notes historiques](docs/releases/0.4.0.md) décrivent les fonctions propres à cette ancienne version. Les nouveaux parcours ci-dessous nécessitent la 0.5.0.

[Guide complet 0.5.0](docs/CONFORT_INTEGRAL_050.md) · [Tous les guides](docs/README.md) · [Parcours de tests sur téléphone](docs/TESTS_TELEPHONE.md) · [Signaler un problème](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/issues/new/choose)

| Repère | Version 0.5.0 |
| --- | --- |
| Édition | **Confort intégral**, code 7 |
| Android minimum | **Android 8.0 — API 26** |
| Identité Android | **fr.soustitresvoix.player.translation**, conservée depuis la 0.4.0 |
| Narration | Lecture intégrale, Confort, Adaptatif, suivi par occurrence et relecture explicite |
| Traduction | Azure Translator, DeepL API Free/payant, serveur LibreTranslate HTTPS ; préparation complète ou progressive sur choix explicite |
| Préparation | Anticipation vocale, cache réglable et conservation durable par œuvre |
| Validation | Résultats du commit distribué à établir dans [TESTING.md](TESTING.md) ; aucun résultat ancien n’est attribué à la 0.5.0 |

**Ce projet est réalisé intégralement par intelligence artificielle : la conception de l’application, l’écriture du code, les corrections, les tests automatisés et la documentation sont produits par IA à partir des demandes, des indications et des retours de son utilisateur.** Cette mention concerne le travail propre au projet. Android, Kotlin, Media3, les moteurs vocaux, les services de traduction et les autres composants tiers restent les créations de leurs auteurs respectifs.

## Installer et commencer sur Android

La 0.5.0 conserve l’identité de la 0.4.0. Sa distribution doit employer le même certificat durable pour permettre une mise à jour Android et conserver les données de cette édition. **Ne pas désinstaller la 0.4.0 pour préparer cette mise à jour.** La 0.3.2 reste une autre application, avec sa propre identité et ses propres données.

Une fois la release 0.5.0 publiée, son fichier installable sera nommé **SousTitresVoix-0.5.0-confort-integral.apk**. Les archives GitHub « Source code » ne sont pas des APK. Les liens et empreintes définitifs seront ajoutés après vérification de la publication.

1. Ouvrir **Réglages de la voix et du confort**.
2. Dans **Voix**, choisir le moteur vocal de ce lecteur et une voix installée dans la langue à prononcer. Écouter un essai ; le choix ne modifie pas le moteur de TalkBack.
3. Dans **Synchronisation**, utiliser **Préréglage : lecture intégrale** ou **Préréglage : compréhension confortable**.
4. Lancer **Essai intégré : sous-titres et mire de cadrage** pour vérifier voix, commandes et image avec un contenu synthétique.
5. Ouvrir une vidéo locale et sélectionner sa piste textuelle, ou ajouter un fichier SRT, VTT, ASS/SSA. Une piste française peut être vocalisée directement ; une traduction constitue une opération supplémentaire.
6. Appuyer explicitement sur **Lire**. Une restauration, une préparation ou une fin de traduction ne doit pas démarrer seule la vidéo.

La lecture dépend des codecs disponibles et d’une voix Android réellement prête. Les sous-titres PGS/VobSub ou uniquement incrustés dans les pixels demandent un OCR qui reste absent de cette version.

## Trouver et modifier un réglage

Les commandes sont réparties entre **Voix, Synchronisation, Traduction, Sous-titres, Image, Son, Stockage et Diagnostic**. Le sélecteur de rubrique et la recherche se trouvent au début du panneau défilant ; les boutons Fermer et Annuler modifications gardent leur zone dédiée, même en grande police.

La recherche filtre les commandes réelles dans toutes les rubriques, sans tenir compte des accents : **vitesse**, **voix**, **bandes noires** ou **traduction** donnent accès aux contrôles correspondants. Effacer la recherche ou choisir une rubrique revient à sa liste de commandes.

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

Dans **Synchronisation**, ouvrir **Décalage, dérive et calibration des sous-titres** :

- **Décalage global**, en millisecondes : positif pour retarder les sous-titres.
- **Décalage de narration** : correction propre à la voix, de −60 000 à +60 000 ms.
- **Deux repères source/vidéo** : correction d’une dérive régulière.
- **Repères supplémentaires** : interpolation continue, avec temps source et vidéo strictement croissants.
- **Portions pour scènes ajoutées/supprimées** : début, fin et décalage signé par portion, sans suppression des textes ; ajout et retrait explicites.
- **Aperçu**, puis validation ou **Annuler** : une annulation restaure la correction d’ouverture.

Les corrections conservent les identités des occurrences et sont associées à la vidéo/piste. Une réplique traversant une limite de portion garde toute sa durée et le décalage de son début. Les chevauchements corrigés conservent tous leurs textes. Une différence de montage contenant des dialogues absents de l’image exige une vérification manuelle.

## Traduire en français, entièrement ou progressivement

La traduction complète reste le parcours par défaut. L’option **Traduction progressive** permet de commencer avec au moins une minute de tampon validé avant la prochaine occurrence non traduite. La traduction continue ensuite pendant le visionnage ; si le lecteur rattrape le travail, il s’arrête à une réplique indisponible et attend une commande **Lire** après progression. Aucune phrase manquante n’est remplacée silencieusement par l’original.

1. Choisir une piste textuelle, puis ouvrir **Traduire les sous-titres en français**.
2. Choisir fournisseur, langue source et accès de son compte ; la langue est celle des sous-titres, pas nécessairement celle de l’audio.
3. Choisir éventuellement le mode progressif et un **plafond de nouveaux caractères UTF-16 pour cette tâche**.
4. Utiliser **Indexer la piste et estimer la traduction** : cette phase n’envoie pas le texte.
5. Examiner le volume restant après réutilisation du cache/progression, la destination et les limites ; confirmer l’envoi.
6. Choisir une voix française prête, puis **Lire** lorsque la piste complète ou le tampon progressif est disponible.

Les lots validés sont enregistrés pour reprise avec la même source et les mêmes paramètres. Une requête interrompue dont le résultat est inconnu peut déjà avoir été facturée : sa reprise demande une confirmation et peut consommer de nouveau du quota. Aucun redémarrage après fermeture n’est garanti sans réouverture et nouvelle confirmation.

### Fournisseurs et options

| Fournisseur | Configuration et fonctions |
| --- | --- |
| **Azure Translator** | Ressource Translator, clé et région selon le compte ; langue source explicite. |
| **DeepL API Free ou payant** | Point d’accès correspondant au contrat ; contexte facultatif, identifiant/révision d’un glossaire déjà créé, modèle et registre lorsque pris en charge ; consultation réelle du quota. |
| **LibreTranslate HTTPS** | Serveur choisi, paire de langues installée et clé éventuelle ; traitement sur ce serveur. |

Le [guide des moteurs](docs/GUIDE_MOTEURS_TRADUCTION.md) détaille les comptes et points d’accès. Les offres et droits dépendent du fournisseur ; le lecteur ne crée ni compte ni abonnement. Le plafond local de caractères n’est pas un prix garanti, une limite contractuelle ou une réservation de quota. Pour Azure/LibreTranslate, consulter le tableau de bord ou l’administrateur pour les quotas.

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
| Traduction | Services en ligne choisis ; français dans le parcours utilisateur ; qualité sémantique à évaluer. |
| Hors ligne | Lecture locale avec voix prête ; traductions et voix préparées réutilisables après contrôle de disponibilité. |
| Fonctions absentes | OCR PGS/VobSub/incrustations, traduction locale, import Hugging Face, audiodescription, accès aux DRM d’autres applications et lecture durable en arrière-plan. |
| Matériel | Aucune preuve d’usage neuronal GPU/NPU ou de performance sur OnePlus 15 déduite des profils de cache ou de tests sur émulateur. |

- Ouverture par le sélecteur Android, sans permission microphone/caméra ni accès général au stockage.
- La traduction confirmée envoie le texte et ses paramètres au fournisseur choisi ; elle n’envoie ni vidéo ni piste audio.
- HTTPS, certificats système, tailles/délais bornés et redirections refusées ; erreurs sans clé ni corps privé.
- Clés mémorisées facultativement par Android Keystore/AES-GCM, avec portée distincte par serveur LibreTranslate ; sauvegarde Android désactivée.
- Sous-titres importés, traductions conservées et textes de préparation sont des données privées de l’application, sans chiffrement additionnel annoncé. Les journaux ordinaires de narration n’enregistrent pas les textes.
- Les suppressions de cache, d’index ou de préparation ne modifient pas les vidéos originales. Les exports restent dans l’emplacement choisi.
- Les permissions et traitements d’un moteur vocal externe dépendent de son fournisseur.

Le [guide 0.5.0](docs/CONFORT_INTEGRAL_050.md) détaille les contraintes de reprise, les limites et la conservation. Le [catalogue vocal](docs/VOIX_CATALOGUE_2026.md), l’[étude de traduction locale](docs/ETUDE_TRADUCTION_LOCALE.md) et l’[étude d’accélération](docs/ETUDE_ACCELERATION.md) séparent les ressources disponibles des pistes de recherche.

## Validation et identité du téléchargement

**Validation 0.5.0 en cours.** Les résultats définitifs, le commit source, les liens Actions, la taille, le certificat et le SHA-256 de l’APK seront ajoutés après les contrôles et la publication. Les résultats de la 0.4.0 restent historiques et ne valident pas ces nouvelles sources.

[TESTING.md](TESTING.md) distingue compilation, tests JVM, instrumentation Android, contrôles de publication et essais manuels. Les tests automatisés utilisent des contenus synthétiques, des réponses API contrôlées et une fixture TTS. La fidélité des fournisseurs, les droits/quota d’un compte, les voix commerciales, TalkBack réel, Bluetooth, HDR physique, autonomie et chauffe demandent leurs essais distincts.

## Documentation et développement

| Besoin | Point d’entrée |
| --- | --- |
| Utiliser les nouvelles fonctions | [Guide Confort intégral 0.5.0](docs/CONFORT_INTEGRAL_050.md) |
| Lire les changements de cette version | [Notes 0.5.0](docs/releases/0.5.0.md), [changelog](CHANGELOG.md) |
| Parcourir tous les guides | [Index documentaire](docs/README.md) |
| Configurer les comptes de traduction | [Moteurs et dépannage](docs/GUIDE_MOTEURS_TRADUCTION.md) |
| Tester sur téléphone | [Parcours manuel](docs/TESTS_TELEPHONE.md) |
| Régler image et son | [Image](docs/INTERFACE_IMAGE_ETAPE_4.md), [mixage](docs/COMMANDES_AUDIO_ETAPE_5.md), [audio expert](docs/AUDIO_EXPERT_ETAPE_6.md) |
| Compiler et contrôler les livraisons | [Procédure et résultats](TESTING.md) |
| Comprendre les composants | [Architecture](docs/ARCHITECTURE.md), [modularisation](docs/MODULARISATION_ETAPE_1.md) |
| Contribuer | [Contribution](CONTRIBUTING.md), [consignes agents](AGENTS.md) |
| Retrouver les anciennes étapes | [Versions et branches](docs/HISTORIQUE_BRANCHES.md), [notes 0.4.0](docs/releases/0.4.0.md) |

Le dépôt contient l’application Android dans **app/**, le moteur indépendant d’Android dans **core/**, les guides dans **docs/**, les démonstrations synthétiques dans **examples/** et les contrôles dans **tools/**. La compilation utilise JDK 17 ; les tests Robolectric Android 36 utilisent JDK 21. FFmpeg génère le média de démonstration à la compilation ; il n’est pas nécessaire sur le téléphone.

**Tout travail terminé et vérifié est intégré à main avant d’être annoncé livré.** Une proposition de fusion et ses contrôles précèdent cette intégration, puis le commit de main est vérifié. Les tags publiés conservent les sources exactes des APK antérieurs. Les guides des étapes 1 à 7 décrivent leur version de référence ; le guide 0.5.0 prévaut pour les fonctions ajoutées ici.
