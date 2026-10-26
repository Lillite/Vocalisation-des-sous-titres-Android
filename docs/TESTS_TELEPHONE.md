# Vérifier la version 0.5.0 sur le téléphone

[Documentation](README.md) · [Guide Confort intégral](CONFORT_INTEGRAL_050.md) · [Installation](../README.md) · [Fournisseurs](GUIDE_MOTEURS_TRADUCTION.md)

Ce parcours concerne **0.5.0 — Confort intégral**, code **7**, identité **fr.soustitresvoix.player.translation**. La version est en cours de validation et de publication. **Les essais sur téléphone décrits ici sont à consigner séparément : ni compilation ni instrumentation sur émulateur ne les déclarent réussis.**

Pour chaque ligne, relever **réussi**, **échec** ou **non testé**, l’attendu, l’observé et les étapes exactes. L’objectif principal est de vérifier la vocalisation de toutes les occurrences textuelles de la piste prise en charge, avec répétitions réelles conservées.

## Préparer une séance reproductible

| Élément | À relever |
| --- | --- |
| Application | Nom affiché, version 0.5.0, code 7, date |
| Téléphone | Modèle exact, Android, orientation, taille des polices |
| Voix | Moteur, voix, langue, données installées, vitesse |
| Narration | Profil intégral, mode, plafond, reprise après Pause |
| Préparation | Cache, segments, horizon et état de conservation |
| Traduction | Fournisseur, langues, complet/progressif, options ; jamais la clé |
| Accessibilité | TalkBack/version, grossissement et méthode de navigation |
| Résultat | Attendu, observé, état et instant du média |

Commencer par les contenus synthétiques du dépôt, à volume confortable. Ne pas téléverser un film personnel, une clé API ou les fichiers de signature pour rendre un test reproductible.

## Installer la bonne version

1. Vérifier que la release 0.5.0 a été publiée sur [la page du projet](../README.md).
2. Télécharger **SousTitresVoix-0.5.0-confort-integral.apk** ; les archives « Source code » ne sont pas installables.
3. Ouvrir le fichier et autoriser l’installation depuis l’application de téléchargement si Android le demande.
4. Avec la 0.4.0 déjà installée, vérifier qu’Android propose sa mise à jour et conserve les réglages/données. Ne pas désinstaller pour forcer un échec de signature.
5. Vérifier version/code dans l’application et les repères de la release.

La 0.5.0 conserve l’identité de la 0.4.0 ; la 0.3.2 reste une autre application. Les empreintes, taille et résultats définitifs seront disponibles dans [TESTING.md](../TESTING.md) et les notes de la publication.

## Parcourir les huit rubriques et les champs numériques

| Essai | Résultat à vérifier |
| --- | --- |
| Ouvrir les réglages | Séance en pause ; recherche et sélecteur de rubrique accessibles |
| Parcourir Voix, Synchronisation, Traduction, Sous-titres, Image, Son, Stockage, Diagnostic | Commandes classées sans perte d’accès aux anciennes fonctions |
| Chercher « vitesse », « voix », « bandes noires », « traduction » | Contrôles pertinents visibles ; recherche avec/sans accents équivalente |
| Effacer la recherche ou changer de rubrique | Liste de la rubrique choisie restaurée |
| Saisir une vitesse précise puis Valider | Valeur appliquée et relue avec l’unité |
| Utiliser +/− et une présélection | Même validation et annonce du paramètre modifié |
| Saisir vide, lettres ou valeur hors bornes | Erreur accessible ; réglage précédent conservé |
| Changer le profil de préparation | Cache, segments et secondes actualisés sans rouvrir |
| Modifier puis Fermer et rouvrir | Valeurs conservées |
| Modifier puis Annuler modifications | Paramètres du début du panneau restaurés ; vidéo toujours en pause |
| Refaire portrait/paysage, grandes polices, clavier ouvert et TalkBack | Champs, erreurs et boutons utilisables ; focus retrouvé |

L’annulation des réglages ne remet pas un fichier supprimé et ne retire pas un appel API déjà confirmé. Ne pas utiliser une suppression personnelle pour essayer le retour aux valeurs.

## Vérifier les phrases complètes et le bilan

1. Dans Voix, choisir le moteur du lecteur et une voix française hors ligne prête.
2. Écouter l’essai, puis choisir **Préréglage : lecture intégrale** dans Synchronisation.
3. Lancer **Essai intégré : sous-titres et mire de cadrage**.
4. Refaire les scénarios avec Adaptatif et compréhension confortable.

| Essai | Résultat à vérifier |
| --- | --- |
| Première occurrence | Une seule lecture de cet événement |
| Phrase trop longue pour sa durée affichée | La vidéo attend ; tous les mots sont prononcés |
| Pause au milieu d’une phrase | Vidéo et voix s’arrêtent ; aucune reprise spontanée |
| Reprise selon la politique choisie | Réplique entière, segment ou commande explicite conformément au réglage |
| Répéter la dernière phrase | Relecture explicite ; vidéo reste en pause à la fin |
| Recul/avance pendant une narration | Ancienne commande ne continue pas au nouvel instant |
| Fin du fichier pendant la dernière phrase | La phrase peut se terminer dans le parcours intégral |
| Bilan avant/après répétition | Nombre d’occurrences distinctes terminées inchangé par la seule répétition |
| Relecture d’une interruption | Occurrence correspondante retrouvée ; reprise vidéo explicite |
| Demander Continu | Confirmation indiquant les coupures ; annuler conserve le parcours intégral |
| Appliquer le profil intégral après des exclusions | Exclusions neutralisées et narration complète rétablie |

**Commencée** est une commande vocale, pas une mesure du premier son. Le bilan doit signaler une couverture ou une capacité incomplète au lieu de prétendre que tout le fichier a été lu.

## Panneaux, chevauchements et répétitions

Utiliser la piste synthétique [essai-traduction-en.srt](../examples/essai-traduction-en.srt), d’une durée de 24 secondes, dans une vidéo suffisamment longue. Elle contient :

| Occurrence | Temps source | Contrôle |
| --- | --- | --- |
| STATION ENTRANCE | 00:00–00:12 | Panneau continu lu une fois, malgré les dialogues superposés |
| Welcome. Please follow me. | 00:02–00:05 | Dialogue conservé avec le panneau |
| Wait here. | 00:06–00:08 | Première occurrence conservée |
| Wait here. | 00:09–00:11 | Deuxième occurrence également conservée |
| Longue annonce du train | 00:13–00:24 | Tous les mots prononcés avec attente si nécessaire |

Répéter avec les fixtures ASS intégrées/externes du projet et les variantes de styles : panneau persistant, inscriptions superposées, événements simultanés identiques, nouvelle apparition, déplacements avant/arrière et piste traduite.

Comparer les occurrences attendues, celles terminées et les interruptions à reprendre. Une ressemblance de texte ne doit pas effacer un événement réel ; un changement de style du même événement ne doit pas créer des dizaines de narrations.

## Restaurer la séance et réparer l’accès

| Essai | Résultat à vérifier |
| --- | --- |
| Choisir pistes, voix, mode, sous-titre externe et réglages propres à une vidéo | Les choix sont relus après fermeture/réouverture |
| Alterner deux vidéos synthétiques | Chaque vidéo retrouve son contexte et sa position |
| Fermer au milieu d’une réplique | L’interruption est retrouvable ; ouverture en pause |
| Choisir Reprendre | Lecture explicite à la position proposée |
| Choisir Relire la dernière réplique avant de reprendre | Réplique retrouvée puis vidéo reste en pause |
| Traduction conservée encore présente | Restauration locale après vérification, sans nouvel envoi |
| Traduction manquante ou piste différente | Attente/erreur perceptible, aucune ancienne piste appliquée au mauvais média |
| Avec une copie synthétique déplacée | Parcours guidé pour choisir de nouveau la même édition |
| Annuler le sélecteur de réparation | Séance conservée, pas de lecture involontaire |
| Oublier seulement une séance synthétique | Fichier vidéo et préparations inchangés |

La vérification des métadonnées ne prouve pas l’identité octet par octet d’une édition. Tester une vidéo modifiée sous la même URI doit produire une remise en question des anciens minutages et choix.

## Vérifier les corrections de synchronisation

1. Choisir une piste connue et ouvrir **Décalage, dérive et calibration des sous-titres**.
2. Attendre l’index complet.
3. Tester le décalage global par ±50 ms, par champ et par présélection.
4. Tester séparément le décalage de narration.
5. Ajouter deux repères de dérive, puis des repères supplémentaires sur un contenu synthétique.
6. Ajouter une portion avec début/fin source et décalage signé pour reproduire un changement brusque de montage ; prévisualiser, annuler, puis valider et rouvrir.

| Essai | Résultat à vérifier |
| --- | --- |
| Décalage positif | Apparition/voix retardées selon le contrôle choisi |
| Correction de narration seule | Affichage conservé à son propre temps |
| Prévisualiser puis Annuler | Carte temporelle d’ouverture restaurée |
| Valider et rouvrir la vidéo/piste | Correction conservée pour son contexte |
| Repères inversés/dupliqués ou saisie invalide | Refus visible, aucun résultat partiel |
| Sous-titre très bref entre deux rafraîchissements | Occurrence vocale conservée |
| Deux occurrences identiques après correction | Deux événements distincts, aucun écrasement |
| Portion avancée créant un chevauchement dans la vidéo | Toutes les occurrences vocalisées, pause de l’image si nécessaire |
| Réplique traversant une limite de portion | Décalage du début conservé pour toute sa durée ; aucun texte coupé |
| Portions source superposées ou désordonnées | Validation refusée ; retirer ou corriger la portion concernée |
| Changer de piste pendant un éditeur ouvert | Ancienne correction refusée pour le nouveau contexte |
| Déplacer la lecture pendant une pause de compréhension | Aucun callback ancien ne redémarre la vidéo |

## Tester la traduction complète

Pour un essai réseau réel, employer une piste synthétique et une configuration autorisée. Consulter les limites du compte. Les scénarios d’échec payants sont couverts avec des réponses contrôlées dans les tests automatisés ; ne pas multiplier les appels réels pour provoquer une panne.

1. Ouvrir **Traduire les sous-titres en français**.
2. Choisir fournisseur, langue source et accès du compte ; laisser Progressif désactivé.
3. Indexer et estimer ; vérifier les cinq occurrences synthétiques sans envoi.
4. Examiner la confirmation, le volume restant, le budget et les destinations.
5. Confirmer une seule fois.
6. Attendre la piste complète, choisir une voix française et appuyer explicitement sur Lire.

Vérifier la conservation des cinq occurrences et la distinction entre leur identité, leur traduction et leur minutage. La qualité sémantique est jugée séparément : nombres, négations, noms, idiomes et registre.

## Tester la reprise, le budget et le mode progressif

| Essai | Résultat à vérifier |
| --- | --- |
| Reprendre une piste ayant des lots validés | Préfixe réutilisé, estimation de nouveaux caractères réduite |
| Requête interrompue au résultat inconnu | Avertissement de facturation incertaine avant reprise |
| Budget trop petit pour les nouveaux lots | Refus sans envoi au-delà du plafond |
| Budget zéro avec résultat complet local compatible | Réutilisation sans nouvel appel |
| Modifier contexte/glossaire/révision/modèle | Résultat d’une ancienne configuration non réutilisé comme identique |
| Annuler ou changer vidéo/piste | Ancienne réponse ne remplace pas la piste courante |
| Terminer un lot | Aucun démarrage automatique de la vidéo |

Le petit exemple de 24 secondes ne suffit pas à observer un tampon initial d’une minute avant la fin. Pour le progressif, employer un contenu synthétique plus long ou le scénario contrôlé de la suite automatisée.

1. Cocher **Traduction progressive**.
2. Confirmer la préparation de la piste synthétique.
3. Essayer Lire avant le tampon : refus/attente perceptible.
4. Quand le tampon validé est suffisant, appuyer sur Lire.
5. Observer la progression sans répétition ni interruption d’une phrase déjà en cours.
6. Si la lecture rattrape le travail, vérifier l’arrêt sur l’occurrence indisponible.
7. Attendre puis appuyer de nouveau sur Lire ; aucune substitution originale ni reprise spontanée.
8. Essayer un déplacement vers une portion encore indisponible : attente contrôlée.

Un résultat partiel n’est pas exportable comme une traduction entière et ne peut pas rendre l’œuvre Prêt hors ligne.

## Affichage, correction, export et fournisseurs

| Essai | Résultat à vérifier |
| --- | --- |
| Choisir original/français/bilingue/masqué | Un seul parcours visuel actif ; narration inchangée |
| Masquer le texte | La voix continue selon le mode choisi |
| Corriger une occurrence de la piste complète | Lecture en pause ; nouvelle formulation prononcée après préparation |
| Consulter les alertes | Indices expliqués ; aucune certitude de fidélité annoncée |
| Conserver durablement puis fermer/réouvrir | Résultat local compatible retrouvé sans requête |
| Exporter SRT et VTT via Android | Texte/minutages conservés, aucun secret |
| Annuler le choix de destination | Pas de fichier écrit ni reprise |
| Réimporter les exports | Piste française autonome, occurrences et répétitions conservées |
| Proposer une langue après indexation | Proposition à confirmer, pas de changement automatique |
| Quota DeepL | Valeurs réelles du fournisseur ou erreur claire ; aucun sous-titre envoyé par cette consultation |
| Comparatif choisi | Destinations/volume confirmés ; résultats numérotés et aucun classement linguistique automatique |
| Changer de média avant confirmation d’échantillon | Confirmation périmée refusée |

Les disponibilités d’options DeepL dépendent du contrat et de la paire de langues. Un quota affiché n’est pas une réservation et le plafond UTF-16 local n’est pas un prix en euros.

## Cache vocal et conservation hors ligne

| Essai | Résultat à vérifier |
| --- | --- |
| Réglages de cache/segments/secondes | Saisie bornée et valeurs immédiatement actualisées |
| Préparer cinq minutes puis annuler | Données déjà calculées conservées ; aucune reprise spontanée |
| Préparer toute une œuvre dans le cache ordinaire | Limite/éviction annoncées ; pas de faux statut intégral |
| Estimer la conservation durable | Espace et volume estimés, aucune synthèse ni texte durable avant confirmation |
| Conserver puis interrompre/reprendre | Segments compatibles déjà produits réutilisés |
| Conserver pendant lecture | Narration courante prioritaire, progression sans coupure |
| Fin de préparation | Tous les segments requis et l’intégrité contrôlés avant Prêt hors ligne |
| Source/traduction partielle | Statut Prêt hors ligne impossible |
| Changer voix, débit ou configuration | Anciens audios incompatibles non utilisés comme à jour |
| Vider le cache ordinaire | Voix explicitement conservées non supprimées par cette seule action |
| Suppression voix/textes/index d’une œuvre synthétique | Seule catégorie choisie supprimée ; disponibilité recalculée |
| Redémarrer sans réseau avec œuvre vérifiée | Lecture locale possible si vidéo et ressources restent accessibles |

Relever séparément « segments prêts » et « minutes vérifiées ». Une estimation de fin ou un nombre de fichiers ne remplace pas la vérification finale.

## Vérifier l’image et le plein écran

1. Dans la démonstration, activer **Plein écran cinéma**, puis **Étirement intégral**. Les quatre coins rouge, vert, bleu et jaune doivent rester visibles. Si l’écran et la vidéo ont des proportions différentes, le cercle central devient une ellipse.
2. Ouvrir les réglages d’image et comparer **Image entière, proportions originales**, **Tout l’écran — image entière étirée** et **Tout l’écran, image recadrée**. Le recadrage volontaire doit demander confirmation lorsque la protection est active.
3. Après un zoom ou recadrage, revenir à un mode « image entière » : vérifier zoom à 100 %, déplacements annulés et marges retirées annulées.
4. Consulter **Diagnostic du cadrage et des bandes noires** et comparer taille vidéo, surface disponible et mode actif.
5. Sur un extrait adapté, essayer **Analyser les marges encodées — aperçu obligatoire**. Une proposition doit précéder l’application. Vérifier **Annuler toute suppression de marges**, puis ouvrir une autre vidéo : elle ne doit pas hériter des anciennes marges.
6. Essayer une saisie précise du contraste ou de la luminosité, puis son rétablissement. Comparer avec l’original et lire le message lorsqu’un traitement n’est pas disponible dans le contexte courant.

L’analyse échantillonnée ne certifie pas l’absence de texte dans toutes les marges d’une œuvre. Les coins arrondis et l’emplacement physique de la caméra restent des contraintes de l’écran. Les [réglages d’image](INTERFACE_IMAGE_ETAPE_4.md) détaillent les valeurs et limites.

## Vérifier le son du film et les traitements avancés

Commencer avec les traitements avancés désactivés et un volume modéré. Ne pas employer un fort gain pour rendre un test plus audible.

| Essai | Résultat à vérifier |
|---|---|
| Régler séparément volume du film et volume de la voix | Les commandes produisent des effets distincts |
| Essayer atténuation et fondus du film pendant une phrase | Changements progressifs conformes aux réglages, sans reprise après Pause |
| Ouvrir **Traitements audio avancés du film** | Traitements optionnels désactivés par défaut, paramètres expliqués |
| Saisir un préampli négatif, puis rétablir | Valeur relue correctement et retour à la valeur prévue |
| Régler une bande de l’égaliseur à faible gain, puis la désactiver | Effet sur le son du film ; la voix du lecteur conserve son parcours séparé |
| Tester balance et mono avec un extrait stéréo adapté | Effet attendu gauche/droite et retour au réglage initial |
| Activer puis désactiver le limiteur à niveau confortable | État et paramètres conservés ; absence de prétention de mesure true-peak |
| Fermer et rouvrir les réglages | Valeurs affichées conformes aux choix conservés |

Ces traitements concernent le **PCM16 mono/stéréo effectivement reçu**. Un test sur ce parcours ne prouve pas un fonctionnement en passthrough, multicanal ou sur tous les codecs. Pour les valeurs, domaines et interactions, consulter les [commandes audio](COMMANDES_AUDIO_ETAPE_5.md) et l’[audio avancé](AUDIO_EXPERT_ETAPE_6.md).

## Accessibilité, interruptions et séance prolongée

Effectuer les parcours complets avec TalkBack : ouvrir vidéo, sélectionner piste, renseigner les paramètres de traduction, confirmer, régler une valeur, annuler, consulter une erreur, relire et sortir du plein écran. Vérifier noms, ordre/restauration du focus, clavier, grossissement, grandes polices et paysage.

Les sous-titres ne doivent pas être lus une seconde fois par TalkBack en plus de la narration. Les états pertinents restent perceptibles sans annonce répétitive du compteur de position. Les actions doivent rester disponibles sans geste obligatoire.

Tester mise en arrière-plan, verrouillage, appel, interruption audio et connexion/déconnexion casque/Bluetooth. La lecture durable en arrière-plan reste absente ; vérifier la pause et l’absence de reprise inattendue.

Effectuer une séance prolongée avec le moteur habituel, puis relever synchronisation, intelligibilité, interruptions, mémoire, chauffe et consommation. Les profils de préparation ne prouvent pas l’emploi du GPU/NPU. Un émulateur ne mesure pas l’autonomie ni la qualité acoustique du téléphone.

Les preuves CI peuvent contenir des échantillons de mémoire, trames et CPU pendant le déploiement et les tests synthétiques mixtes, puis des instantanés finaux incluant la version du paquet et l’état thermique. Le recueil vise trente secondes entre échantillons, au plus quarante échantillons, sans remise à zéro des compteurs de trames. Il aide à examiner le contexte de l’émulateur ; il ne remplace pas ces observations pendant une séance sur le téléphone. Un service indisponible ou un processus arrêté est indiqué par la sortie de la commande et son statut, sans inventer de valeur de mesure. Voir [la portée des instantanés](../TESTING.md#instantanés-techniques-de-lémulateur).

## Signaler un résultat exploitable

Indiquer version, téléphone, Android, moteur/voix, mode, type de sous-titres, source intégrée/externe, paramètres, instant concerné et étapes exactes. Pour la traduction, ajouter fournisseur, langues, complet/progressif et réutilisation du cache, sans clé API.

Distinguer texte absent de la source, occurrence extraite non prononcée, répétition artificielle, contresens, mauvaise prononciation et problème de temps. Le bilan peut aider, avec ses limites de couverture explicites.

Relire le diagnostic avant partage. Privilégier un petit exemple synthétique. Ne pas joindre de film personnel, secret API ou fichier privé de signature. Reporter les résultats matériels sans les confondre avec les suites automatisées décrites dans [TESTING.md](../TESTING.md).
