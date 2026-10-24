# Traduction des sous-titres — édition 0.4.0

[Guide des moteurs, comptes et modèles](GUIDE_MOTEURS_TRADUCTION.md) · [Architecture](ARCHITECTURE.md) · [Vérifications](../TESTING.md)

## Parcours disponible

1. Ouvrir la vidéo locale et choisir sa piste textuelle, intégrée ou ajoutée en SRT/VTT/ASS/SSA.
2. Ouvrir **Traduire les sous-titres en français**, depuis la bibliothèque ou les réglages accessibles pendant le mode cinéma.
3. Choisir Azure, le point d’accès DeepL correspondant au compte, ou un serveur LibreTranslate HTTPS. Chaque choix affiche sa propre description, ses prérequis et ses liens documentaires.
4. Indiquer explicitement la langue des sous-titres et les paramètres du compte. Une clé peut être laissée vide pour réutiliser une traduction complète en cache. Sa mémorisation chiffrée est facultative.
5. Demander **Indexer la piste et estimer la traduction**. Cette étape lit le fichier mais ne crée aucun client réseau. Un index tronqué, vide ou non textuel est refusé.
6. Vérifier le fournisseur, le nombre d’occurrences et le volume estimé, puis confirmer **Traduire / réutiliser le cache**. Cette confirmation autorise une nouvelle requête si le cache est absent ou invalide. Elle n’est pas une estimation de prix ni une consultation du quota du compte.
7. Attendre la validation complète, choisir une voix française prête et appuyer sur Lire. La fin de traduction ne lance jamais automatiquement le film ou la voix.

La cible de cette première étape est le français. L’affichage vidéo conserve les sous-titres originaux ; la narration reçoit la traduction. Les filtres vocaux facultatifs restent applicables au texte traduit. La fidélité sémantique dépend du fournisseur : les contrôles structurels ne prouvent pas une traduction linguistiquement parfaite.

## Identité, minutage et intégrité

`TranslationPlan` prend une copie immuable des occurrences de la piste sélectionnée. Chaque occurrence garde son identité, son début et sa fin. Deux textes identiques à des instants distincts, ou deux occurrences simultanées portant les mêmes mots, restent distincts. Le texte est nettoyé des styles connus avant traduction, sans supprimer les descriptions par défaut.

Les requêtes sont réparties en lots contigus de **20 occurrences et 4 000 unités UTF-16 au maximum**. Une occurrence trop longue est refusée avant tout envoi, sans découpage ni troncature. Le volume affiché constitue une estimation conservatrice de caractères, pas un nombre de jetons ni un tarif. Le fournisseur peut compter différemment certains caractères Unicode.

Chaque lot doit fournir exactement une réponse textuelle non vide par occurrence, dans l’ordre contractuel de l’API. Une réponse manquante, supplémentaire, invalide en Unicode, nulle ou trop longue arrête le travail avant le lot suivant. Seul un résultat complet devient `TranslatedTrack`. Cette validation préserve la correspondance structurelle ; une API renvoyant du texte sémantiquement faux tout en respectant son contrat peut encore nécessiter une correction humaine.

Pendant la lecture, la session résout l’occurrence originale puis vérifie son identité **et son texte source** avant de fournir la traduction au contrôleur vocal existant. Une divergence provoque une pause perceptible. Le texte original n’est jamais utilisé comme remplacement automatique d’une traduction absente. Le mode reste en attente jusqu’à une nouvelle traduction ou la commande explicite **Revenir à la piste originale**.

La réplique déjà affichée à l’activation ou au retour à l’original est réinjectée dans la file pendant la pause. Un simple déplacement à position identique ne suffisait pas : un test a reproduit cette omission et contrôle maintenant le correctif. Les temps et identités passés au moteur de narration restent ceux des occurrences originales.

## Annulation et changements

Les retours de worker portent l’identité de tâche, de génération de média et de piste. Une annulation, une nouvelle vidéo, une nouvelle ouverture du même URI, un changement de piste ou une fermeture invalide les anciennes réponses. Une tâche annulée ferme le client réseau en cours ; une requête déjà reçue par le fournisseur peut néanmoins avoir été facturée.

Fermer le panneau avant confirmation annule son indexation/aperçu et empêche une fenêtre de confirmation tardive. Une traduction déjà confirmée peut continuer pendant que le panneau est fermé et que l’activité reste vivante. Aucun service de travail durable en arrière-plan ni reprise après arrêt du processus n’est annoncé.

L’activation n’exige une voix française prête que si la vocalisation est activée. La lecture sans narration conserve cette possibilité. La préparation vocale complète refuse un nouvel index divergent dans son ensemble. L’anticipation des traductions utilise une recherche temporelle bornée ; elle ne recopie pas l’intégralité de la piste chaque seconde.

## Cache et clés

Le cache privé est limité à **32 Mio**, distinct du cache audio. Un enregistrement atomique ne conserve que les pistes complètes. Son empreinte lie contenu, identités, minutages, langues et moteur ; celle de LibreTranslate inclut l’adresse canonique du serveur. Un autre serveur ne réutilise pas la traduction précédente. Les noms de fichiers de cache sont des empreintes, sans titre de vidéo.

Une piste complète retrouvée est réutilisée sans appel API et sans clé. Le cache est évictable par Android. Une entrée corrompue est traitée comme absente, uniquement dans le cadre d’une demande explicitement confirmée. Une annulation ne sauvegarde aucun résultat partiel. Si l’écriture échoue après une traduction réussie, la piste complète reste disponible dans la session et un avertissement précise qu’elle n’a pas été sauvegardée.

Les clés mémorisées utilisent **AES-GCM et Android Keystore**, sans repli en clair ; sauvegarde Android de l’application désactivée. Les clés LibreTranslate sont liées au serveur, y compris par les données authentifiées du chiffrement. Changer son adresse vide le champ avant de charger sa propre clé. Une ancienne clé non associée à un serveur n’est pas réutilisée automatiquement.

Le chiffrement local ne protège pas une clé sur un appareil entièrement compromis. L’utilisateur doit conserver le contrôle des quotas et des plafonds de son compte. La clé de signature Android de l’édition Traduction est indépendante de ces clés API et reste hors du dépôt et des releases.

## Réseau, portée et limites

La permission Internet est ajoutée pour les traductions demandées. Aucun OCR distant ni transfert de vidéo/audio n’est introduit. Les API reçoivent le texte des sous-titres et les paramètres nécessaires après confirmation. Le fournisseur choisi applique ses propres règles de traitement des données. La voix Android reste un composant externe.

Les transports imposent HTTPS, la validation des certificats système, des délais et des tailles bornés, sans suivre les redirections et sans relance automatique. Les erreurs affichées sont des messages de l’application : elles ne reproduisent ni corps de réponse, ni clé, ni texte de sous-titre. Le coût réel, le quota restant et la qualité sémantique ne sont pas mesurés par le lecteur.

Les sous-titres bitmap PGS/VobSub et le texte incrusté nécessitent toujours un OCR absent de cette étape. Aucun moteur local de traduction, téléchargement de modèle Hugging Face ou usage du GPU/NPU n’est annoncé comme implémenté. Le [guide détaillé](GUIDE_MOTEURS_TRADUCTION.md) et l’[étude locale](ETUDE_TRADUCTION_LOCALE.md) séparent les options intégrées des candidats étudiés.

Les améliorations contextuelles, glossaires, comparaison de fournisseurs, reprise de lots partiels, export et préparation progressive sont des propositions à décider avec l’utilisateur ; elles ne sont pas activées par cette étape.
