# Confort intégral — guide de la version 0.5.0

[Accueil](../README.md) · [Documentation](README.md) · [Tests sur téléphone](TESTS_TELEPHONE.md) · [Notes de version](releases/0.5.0.md) · [Couverture des demandes](COUVERTURE_DEMANDES_050.md)

**État : sources 0.5.0 en cours de validation et de publication.** Les résultats de l’APK effectivement distribué seront reliés dans [TESTING.md](../TESTING.md). Ce guide n’attribue aucun résultat d’un APK antérieur à cette version.

## Objectif et vocabulaire

La fonction principale est la vocalisation des sous-titres de la piste choisie. En **Lecture intégrale**, dialogues, inscriptions, indications textuelles et répétitions réelles extraits de cette piste doivent conserver leur place dans la narration. Confort/Adaptatif peuvent arrêter l’image afin que la voix termine. Aucun résumé, suppression de mots pour tenir un délai ou remplacement silencieux d’une traduction manquante ne constitue un moyen de rattrapage.

| Terme | Sens dans le lecteur |
| --- | --- |
| Occurrence | Événement de la piste source : identité, texte et intervalle temporel. Deux événements identiques restent distincts. |
| Segment vocal | Morceau d’une occurrence synthétisé séparément, selon la ponctuation ou une frontière de mot. Il ne remplace pas l’identité de la réplique entière. |
| Index | Liste temporelle extraite de la piste. Un index incomplet ne peut pas certifier la couverture totale. |
| Cache | Donnée réutilisable mais évictable. Une préparation parcourue ne prouve pas que tout son début est encore en cache. |
| Conservation durable | Donnée maintenue dans l’espace privé de l’application jusqu’à suppression explicite ou suppression des données/désinstallation. |
| Prêt hors ligne | Résultat d’un contrôle de disponibilité et d’intégrité pour la source, la voix et les paramètres concernés. |
| Progressif | Utilisation d’un préfixe traduit validé pendant que la préparation des lots suivants continue. |

L’intégrité structurelle des occurrences et la fidélité linguistique sont deux vérifications distinctes. Un traducteur peut conserver le nombre d’entrées et produire un contresens. L’OCR reste absent : le lecteur ne peut pas prononcer un texte qu’il n’extrait pas de la source prise en charge.

## Installation et mise à jour

La 0.5.0 garde l’identité **fr.soustitresvoix.player.translation** et doit être publiée avec le certificat durable de la 0.4.0. Android peut alors effectuer une mise à jour de cette édition. Ne pas désinstaller la 0.4.0 avant la mise à jour : une désinstallation peut retirer ses données privées. La stable 0.3.2 conserve une identité différente et peut rester installée à côté.

Le téléchargement se fait depuis la release GitHub publiée, avec le fichier **SousTitresVoix-0.5.0-confort-integral.apk**. L’archive « Source code » ne s’installe pas sur Android. Une installation initiale d’un moteur vocal ou de ses voix peut demander Internet et se fait dans ce moteur.

Les valeurs précédemment enregistrées sont conservées. Les nouvelles valeurs de départ ne remplacent pas automatiquement un choix personnalisé.

## Réglages : huit rubriques et une recherche

Ouvrir **Réglages de la voix et du confort**. Le panneau met la séance en pause.

| Rubrique | Principales commandes |
| --- | --- |
| Voix | Activation, moteur du lecteur, voix prête, vitesse, volume et essai vocal. |
| Synchronisation | Lecture intégrale, modes Confort/Adaptatif/Continu, profils de compréhension, reprise, décalages et dérive. |
| Traduction | Fournisseur, préparation complète/progressive, budget, langues, affichage, correction et exports. |
| Sous-titres | Piste choisie, fichier externe, affichage, taille et exclusions facultatives. |
| Image | Accès au cadrage, zoom, marges, contraste, luminosité et filtres existants. |
| Son | Volumes du film, fondus, atténuation et traitements experts. |
| Stockage | Cache, anticipation, profils de préparation, conservation et suppressions. |
| Diagnostic | Bilan, mesures, essais et copie volontaire du diagnostic. |

Saisir **vitesse**, **voix**, **bandes noires**, **cache**, **traduction** ou d’autres mots de la commande. La recherche normalise les accents, les majuscules et certaines variantes typographiques. Elle filtre les commandes de toutes les rubriques ; elle ne recherche pas dans les dialogues personnels.

### Effet immédiat et annulation

Les réglages sont enregistrés dès leur validation. **Fermer** les conserve. **Annuler modifications** restaure les paramètres enregistrés à l’ouverture de ce panneau et réapplique les réglages compatibles en gardant la séance en pause.

Cette restauration ne retire pas les effets d’une opération déjà lancée : suppression de données, export écrit ou appel fournisseur confirmé. Les clés de traduction sont gérées séparément et ne font pas partie de l’instantané des réglages généraux.

### Entrer une valeur précise

1. Saisir un entier dans le champ associé au paramètre.
2. Appuyer sur **Valider**, ou valider au clavier.
3. Contrôler la valeur affichée et l’unité.
4. Utiliser au besoin les boutons +/−, le curseur ou une présélection.

Les commandes partagent la même validation. Une valeur hors bornes reste signalée et n’est pas appliquée. Les boutons +/− annoncent le nom du paramètre et sa valeur actuelle ; une actualisation d’affichage ne doit pas produire elle-même une mutation.

## Choisir une écoute qui conserve les phrases

### Lecture intégrale

Le préréglage active la narration, choisit Confort, remet le débit de base à 100 % et retire les exclusions. L’option intégrale neutralise les filtres de descriptions, étiquettes, chansons et notes. Le mode Adaptatif conserve aussi les phrases en ralentissant le déroulement de la vidéo lorsque nécessaire.

Le mode **Continu** demande une confirmation distincte : il peut interrompre une phrase pour suivre l’image et désactive la lecture intégrale. Il ne correspond pas au parcours recommandé pour entendre tous les sous-titres.

### Compréhension confortable et adaptation

Les trois préréglages utilisent Adaptatif et une base de **100 %**, avec une tolérance de **600 ms**. **Compréhension confortable** plafonne à **140 %** et ajoute **350 ms** de pause après une phrase ; **Compréhension équilibrée** plafonne à **180 %** sans pause ajoutée ; **Pauses rares** plafonne à **220 %** sans pause ajoutée. Tous conservent les phrases entières et mettent la vidéo en pause si nécessaire. La pause de compréhension se règle séparément de 0 à 3 000 ms.

L’algorithme utilise les durées audio mesurées lorsqu’elles sont disponibles pour la configuration courante ; il conserve une estimation calibrée en leur absence. La segmentation tient compte de la langue, de la ponctuation et d’abréviations usuelles. Les limites d’un moteur vocal ou ses prononciations restent à évaluer à l’écoute.

Un budget de préparation plus élevé peut réduire les attentes, mais demande davantage de travail, d’espace et d’énergie. Il ne doit pas évincer la synthèse courante ni servir à masquer une erreur de piste.

## Suivi des occurrences et bilan

Le journal associe un identifiant haché à chaque occurrence connue. Les états comprennent détection, préparation, mise en file, commande vocale commencée, fin, interruption, erreur et exclusion explicitement demandée.

Le bilan indique :

- Les occurrences distinctes terminées au moins une fois.
- Les occurrences en attente et celles interrompues.
- Les erreurs et les exclusions.
- Les limites de capacité ou de couverture lorsqu’elles s’appliquent.

Une répétition manuelle ne gonfle pas le nombre d’occurrences distinctes. Un même panneau persistant et les dialogues qui lui sont superposés possèdent des identités séparées. Les répétitions légitimes à d’autres instants restent prononcées.

**Commencée** correspond à l’émission de la commande vocale ; l’indicateur n’est pas une mesure acoustique du premier son. Les mesures de latence sont consultables séparément. Le journal ordinaire ne contient ni texte source ni traduction.

La relecture d’une interruption cherche le texte dans la source courante à partir de son identité. Si la source ou la piste ne correspond plus, le lecteur demande une vérification. La relecture laisse l’image en pause ; **Reprendre** demeure une commande explicite.

## Séances mémorisées et réparation d’accès

La sauvegarde par vidéo rassemble position, pistes/langues, sous-titre associé, moteur/voix/mode, paramètres, image, correction temporelle et métadonnées de la traduction. Le journal permet de retrouver les dernières occurrences concernées.

Une indication historique « traduction prête » ne suffit pas à la réouverture : le lecteur vérifie de nouveau l’index et le résultat local correspondant. Une réponse ancienne ne doit jamais activer une traduction sur une autre vidéo.

À la réouverture :

1. Attendre le chargement et la vérification des pistes.
2. Vérifier la position et le contexte restaurés.
3. Choisir **Reprendre** ou **Relire la dernière réplique avant de reprendre**.
4. Après relecture, appuyer explicitement sur **Reprendre**.

Si l’accès a expiré ou que la vidéo a été déplacée, le parcours **Retrouver le fichier de cette séance** propose le sélecteur Android. Choisir la même vidéo et la même édition. Des métadonnées changées peuvent neutraliser la restauration afin d’éviter d’appliquer les minutages d’un autre montage.

**Oublier uniquement cette séance mémorisée** retire sa position et ses choix ; ce n’est pas une suppression du fichier vidéo ou des préparations conservées.

## Synchronisation : décalage, dérive et portions

Ouvrir **Décalage, dérive et calibration des sous-titres** dans Synchronisation. L’index complet de la piste est nécessaire ; son obtention ne lance pas la vidéo.

| Réglage | Sens |
| --- | --- |
| Décalage global | Valeur positive : texte et narration de la piste sont retardés. |
| Décalage propre à la narration | Ajuste la voix indépendamment de l’affichage ; bornes −60 000 à +60 000 ms. |
| Deux repères | Pour chaque point, indiquer l’instant source du sous-titre et l’instant correspondant dans la vidéo. |
| Repères supplémentaires | Interpolation continue entre des repères dont les deux colonnes augmentent strictement. |
| Portions pour scènes ajoutées/supprimées | Début inclus, fin exclue dans la piste originale et décalage signé propre à cette portion ; jusqu’à 64 portions ordonnées sans chevauchement source. |
| Prévisualiser | Applique temporairement la carte temporelle avec la vidéo en pause. |
| Annuler | Rétablit la carte présente à l’ouverture de l’éditeur. |

Les champs sont en millisecondes : **1 000 ms = 1 seconde**. Des boutons ±50 ms et des présélections complètent la saisie. Les identités des occurrences ne changent pas avec la correction.

La correction à deux points traite une dérive régulière. Pour un changement brusque de montage, **Ajouter une portion avec décalage** applique une correction constante à l’intervalle original indiqué, après la dérive et le décalage global. Par exemple, `600000` à `1200000`, décalage `−2500`, avance de 2,5 secondes les répliques commençant dans cette portion. Les champs et boutons ±50 ms restent modifiables avant aperçu/validation ; chaque portion peut être retirée.

Une réplique traversant une limite conserve le décalage de son début sur toute sa durée. Une correction peut créer des chevauchements dans la vidéo : les occurrences restent toutes présentes, et la narration peut arrêter l’image. En cas d’ambiguïté, la conversion d’une position vidéo vers la piste choisit le premier temps source compatible ; le lecteur recherche les occurrences dans leurs intervalles corrigés pour préserver celles qui se chevauchent. Une scène absente du montage ne fait jamais disparaître ses textes automatiquement et demande une vérification à l’écoute. Les corrections ne réécrivent pas le fichier d’origine.

## Anticipation et cache vocal

| Paramètre | Nouvelle valeur de départ | Bornes |
| --- | --- | --- |
| Capacité du cache | 512 Mio | 16 à 8 192 Mio |
| Segments anticipés | 256 | 1 à 4 096 |
| Audio anticipé estimé | 120 s | 5 à 7 200 s |

Les trois limites s’appliquent ensemble. Une capacité de plusieurs gigaoctets ne signifie pas une allocation équivalente de RAM : elle borne le cache sur disque. Les voix conservées par œuvre suivent un autre parcours et ne sont pas supprimées par l’éviction normale du cache.

Les profils donnent des points de départ :

| Profil | Capacité | Audio estimé | Segments |
| --- | --- | --- | --- |
| Économie | 64 Mio | 15 s | 32 |
| Équilibré | 512 Mio | 120 s | 256 |
| Intensif | 2 048 Mio | 300 s | 1 024 |

Le changement actualise les valeurs dans le panneau courant. Les profils n’activent pas de runtime GPU/NPU. Android peut signaler un état thermique sévère : le lecteur arrête alors la préparation et le signale.

## Conserver pour une écoute hors ligne

La conservation durable se prépare dans **Stockage → Préparation conservée et stockage hors ligne**.

1. Ouvrir la vidéo, choisir la piste et terminer la traduction si l’écoute française intégrale est souhaitée.
2. Examiner l’estimation de place et l’espace disponible. L’estimation reste approximative avant synthèse.
3. Confirmer **Conserver pour une écoute hors ligne**.
4. Laisser la préparation progresser ; elle utilise les segments déjà compatibles et donne priorité à la narration courante.
5. Vérifier le nombre de segments prêts et le temps couvert.
6. Attendre le contrôle final avant de se fier à **Prêt hors ligne**.

La disponibilité finale dépend de l’index complet, du texte choisi, des paramètres de voix, des fichiers audio et de leur intégrité. Une source/traduction partielle, un fichier manquant ou un contrôle incomplet empêche la certification. Le lecteur vérifie les WAV et leurs empreintes ; un compteur de synthèses ne suffit pas.

L’œuvre vidéo doit rester accessible. La commande conserve les données de narration ; elle ne copie pas automatiquement une vidéo placée sur un support ou service devenu indisponible.

En cas d’arrêt, les fichiers déjà produits peuvent être repris avec les mêmes paramètres. Android peut interrompre le processus ; il faut rouvrir le lecteur et relancer la préparation. Une lecture en arrière-plan durable et un service de calcul persistant ne sont pas ajoutés ici.

### Supprimer les préparations

Le gestionnaire par œuvre distingue :

- **Voix préparées** : audios de cette préparation.
- **Textes conservés** : contenu utilisé par le plan.
- **Index conservé** : identités et minutages nécessaires au plan.

Une suppression peut rendre l’œuvre incomplète hors ligne. Les vidéos d’origine et les exports ne sont pas modifiés. La conservation de traduction du panneau Traduction est distincte de cette préparation vocale.

## Traduction complète, reprise et mode progressif

### Préparation sans envoi

Choisir fournisseur et langue source, puis **Indexer la piste et estimer la traduction**. L’indexation doit produire une piste complète exploitable et un décompte d’occurrences. Cette phase ne crée pas de client réseau pour envoyer les sous-titres.

La confirmation indique le volume de la piste et les nouveaux caractères restant après reprise/cache. Les lots sont bornés à **20 occurrences et 4 000 unités UTF-16**. Un plafond vide n’ajoute pas de limite locale ; un plafond zéro autorise seulement la réutilisation compatible sans nouveaux caractères à envoyer.

### Reprise des lots

Chaque lot validé est enregistré dans un journal de progression lié à la source, aux langues, au fournisseur/serveur et aux options. Une reprise réutilise le préfixe validé et travaille sur la suite. Modifier le contexte ou la révision d’un glossaire change l’identité des résultats.

Avant un appel, une requête est marquée comme en cours. Si la réponse est perdue, la facturation peut être incertaine. La confirmation d’une reprise le signale ; elle ne promet pas un remboursement ou une absence de nouveau coût.

### Regarder avant la fin

Le mode progressif est **facultatif et désactivé par défaut**. Il expose uniquement des préfixes contigus validés. Le démarrage exige au moins **une minute avant la prochaine occurrence non traduite**, ou la piste entière terminée.

Un nouveau lot ne doit pas couper ni réinitialiser une phrase déjà en cours. À la limite du préfixe disponible, le lecteur arrête la vidéo et demande d’attendre puis de choisir **Lire**. Un déplacement loin dans le film peut nécessiter d’attendre que les lots précédents soient traités.

Aucune fin de lot ne reprend la lecture automatiquement. Le mode Confort/Adaptatif continue de préserver les phrases ; la disponibilité de traduction ne supprime pas les règles vocales.

### Langues et options

La langue source concerne les sous-titres, pas l’audio. Une liste de langues usuelles et une proposition heuristique locale aident à la saisie. La proposition doit être confirmée ; une piste mélangée ou une écriture ambiguë peut exiger une décision manuelle.

Les options supplémentaires concernent **DeepL** :

- Contexte facultatif de l’œuvre ou de la scène, envoyé avec les lots.
- Identifiant d’un glossaire distant déjà créé dans le compte.
- Révision manuelle à changer après modification du glossaire distant.
- Modèle et registre pris en charge par le compte et la paire de langues.

Le lecteur ne crée pas de glossaire sur le serveur. Les options refusées restent des erreurs perceptibles.

### Budget, quota et comparaison

Le plafond concerne les **nouveaux caractères UTF-16 de la tâche**. Il n’est ni une limite de dépense du compte fournisseur ni une garantie de prix. Les autres usages du même compte peuvent consommer son quota.

**Consulter le quota DeepL** interroge le fournisseur sans envoyer les sous-titres ; les valeurs ne réservent pas de quota. Pour les autres services, utiliser leur tableau de bord ou l’administrateur de l’instance.

Le comparatif accepte jusqu’à **quatre configurations** et un échantillon borné aux **dix premières occurrences**, limité à **4 000 unités UTF-16**. L’utilisateur confirme tous les destinataires. Chaque service peut facturer l’essai ; le lecteur présente résultats et délais sans classement automatique de fidélité.

## Affichage, alertes, correction et export

L’affichage propose **Piste originale**, **Traduction française**, **Original et français** ou **Masquer les sous-titres visibles**. Il ne choisit pas ce qui est prononcé. Masquer le texte n’arrête pas la narration.

Le bilan linguistique relève des indices, notamment certains nombres modifiés ou longueurs inhabituelles. Une alerte n’établit pas un contresens ; l’absence d’alerte ne prouve pas la fidélité.

Pour corriger une réplique :

1. Terminer la traduction.
2. Choisir **Corriger une réplique de la traduction complète**.
3. Saisir le numéro d’occurrence et examiner source/traduction.
4. Modifier puis appliquer ; la séance reste en pause.
5. Conserver ou exporter la piste pour garder cette correction.

Le cache vocal dépend du texte : la nouvelle formulation utilise un nouvel audio, tandis que les autres segments compatibles restent réutilisables.

Les commandes **Exporter la traduction en SRT/VTT** ouvrent le sélecteur Android. L’export contient texte et minutages, sans clé API et sans l’intégralité des styles ASS. Le réimport utilise **Ajouter un fichier de sous-titres**. Il devient une piste française autonome ; une nouvelle traduction français vers français n’est pas nécessaire.

## Données et limites

| Donnée | Conservation |
| --- | --- |
| Vidéos originales | Accès choisi via Android ; non réécrites et non téléversées par la traduction. |
| Sous-titres importés | Copie privée pour leur lecture dans l’application. |
| Journal de narration | Identifiants hachés, états et minutages ; sans texte. |
| Progression/traductions | Texte privé sur le téléphone, sans chiffrement additionnel annoncé ; conservation/export distincts du cache. |
| Voix conservées | Fichiers audio privés, liés à la configuration et validés. |
| Clés API | Mémorisation facultative chiffrée avec Android Keystore ; jamais incluses dans les exports de sous-titres. |

La suppression des données Android ou la désinstallation retire les données privées. Une conservation durable dans l’application n’est pas une sauvegarde indépendante ; exporter les traductions utiles vers l’emplacement choisi.

Cette version n’ajoute ni OCR, ni traduction locale, ni modèle vocal neuronal propre au projet, ni audiodescription, ni accès à des DRM externes, ni lecture durable en arrière-plan. Les commandes d’image/audio existantes et leurs protections restent documentées dans leurs guides spécialisés.

## Vérifier et signaler un résultat

Suivre le [parcours téléphone](TESTS_TELEPHONE.md). Indiquer version, appareil, Android, voix/moteur, type de piste, mode, réglages, instant et étapes de reproduction. Distinguer erreur de traduction, occurrence manquante, répétition artificielle, erreur temporelle et mauvaise prononciation.

Utiliser les contenus synthétiques du dépôt pour une reproduction. Ne pas publier de clé API, fichier de signature ou vidéo personnelle. Relire tout diagnostic avant partage. Les résultats automatisés du commit distribué sont centralisés dans [TESTING.md](../TESTING.md).
