# Sources locales et réseau — 1.0.0

[Index](README.md) · [Guide utilisateur](guide-utilisateur.md) · [Architecture](ARCHITECTURE.md) · [Validation](../TESTING.md)

## Choisir un accès local

Le **sélecteur Android** (Storage Access Framework, SAF) reste utilisable sans autorisation générale de stockage. Il accorde l’accès au document choisi ; la conservation de l’autorisation dépend du fournisseur Android et du document. Déplacer le fichier, retirer la carte SD ou révoquer l’accès peut imposer une nouvelle sélection.

L’onglet **Explorer** ajoute une navigation dans le stockage partagé et les volumes SD montés et accessibles. L’accès général est **facultatif** : son refus ne supprime pas le sélecteur Android.

| Android | Autorisation d’Explorer |
| --- | --- |
| 8 à 10 — API 26 à 29 | Lecture des fichiers `READ_EXTERNAL_STORAGE`, déclarée jusqu’à l’API 29 ; compatibilité stockage historique demandée pour Android 10 |
| 11 et suivants — API 30+ | Autorisation spéciale **Accès à tous les fichiers**, accordée par l’utilisateur dans les réglages Android ; ce n’est pas une boîte de permission ordinaire |
| Tous | Les dossiers privés d’autres applications restent protégés ; `Android/data` et `Android/obb` sont exclus de l’explorateur |

Explorer liste **un niveau à la fois**, hors du thread d’interface. Dossiers d’abord, tri par nom, pages de 100 éléments par défaut. Un dossier dépassant **10 000 entrées brutes** est refusé explicitement : aucun résultat partiel n’est présenté comme exhaustif. Choisir un sous-dossier ou passer par le sélecteur Android. Les liens sortant de la racine choisie et les dossiers privés exclus ne permettent pas de contourner cette limite.

## Configurer un serveur ou un lien

Renseigner un nom de connexion, l’hôte sans préfixe de protocole, le port, les identifiants et le dossier de départ. Le nom SMB du partage et, si nécessaire, le domaine doivent être saisis séparément. L’application ne découvre pas automatiquement les serveurs ou partages sur le réseau.

| Source | Accès implémenté | Points de vigilance |
| --- | --- | --- |
| **HTTP / HTTPS direct** | URL saisie explicitement ; fichiers progressifs et modules HLS/DASH | Authentification Basic seulement ; pas d’intégration cookies, OAuth, Digest ou SSO |
| **RTSP direct** | Module RTSP Media3 | Lecture dépendant du serveur et des codecs ; pas d’index complet garanti |
| **FTP**, port initial 21 | Liste MLSD puis LIST si la commande n’est pas supportée ; mode passif, binaire, reprise par position | Transport non chiffré ; le serveur doit accepter les commandes nécessaires |
| **FTPS**, port initial 21 | **TLS explicite**, canal de données protégé (`PROT P`) | Pas de FTPS implicite ; CA système et nom de serveur vérifiés ; certificat de données lié au certificat de contrôle |
| **SFTP**, port initial 22 | Mot de passe, accès fichier repositionnable | Empreinte SHA-256 de clé d’hôte explicitement vérifiée et enregistrée avant authentification |
| **WebDAV**, port initial 443 | Liste `PROPFIND Depth: 1`, lecture HTTP(S), Basic | HTTP possible sur choix explicite ; le dossier final doit être donné, sans redirection automatique de PROPFIND |
| **SMB2 / SMB3**, port initial 445 | Partage fourni explicitement ; SMB 2.0.2/2.1/3.0/3.0.2/3.1.1 ; signature obligatoire | Pas de SMB1 ni de découverte réseau ; une signature n’est pas une affirmation de chiffrement SMB obligatoire |

Pour SFTP, relever l’empreinte proposée puis la comparer par un canal fiable avec celle du serveur. Le relevé interrompt volontairement le handshake avant authentification : **voir une empreinte n’établit pas sa fiabilité**. La connexion authentifiée exige l’empreinte enregistrée et refuse un changement. Aucune acceptation silencieuse de toutes les clés n’est utilisée. L’authentification par clé privée n’est pas proposée par ce parcours.

Les connexions enregistrées sont limitées à **64**, les liens directs à **256** ; supprimer une entrée devenue inutile permet d’en ajouter une autre. Les manifestes HLS/DASH directs sont reconnus automatiquement par le suffixe du chemin **`.m3u8` / `.mpd`** ; un endpoint d’API sans suffixe n’est pas reconnu automatiquement comme manifeste.

Le lien direct est une URL média, pas une page de lecteur Web. L’application n’intègre pas d’API UltraDebrid ou d’extraction de liens d’un compte tiers. Un lien temporaire fourni par l’utilisateur reste soumis à sa validité, ses droits et ses restrictions réseau.

## Secrets et identité d’une source

Les connexions et URL complètes sont chiffrées en **AES-GCM avec Android Keystore**. Les médias/séances enregistrent des références opaques (`remote-media` ou `network-media`) ; les mots de passe ne sont pas incorporés aux URI de lecture ni aux diagnostics affichés. Une modification de connexion change sa révision et invalide l’identité précédente des caches de sous-titres/séances concernés. Si le stockage chiffré n’est plus lisible, recréer la connexion ; cette opération ne supprime aucun fichier du serveur.

Cette protection locale ne chiffre pas un transport FTP ou HTTP. Ne pas publier URL personnelle, jeton de requête, hôte privé ou mot de passe dans un rapport. La sauvegarde Android des données privées est désactivée dans le manifeste. Les règles HTTPS des fournisseurs de traduction restent distinctes des transports médias explicitement autorisés en clair.

Pour HTTP, le transport suit au plus **cinq redirections**, refuse une dégradation HTTPS → HTTP et retire l’authentification Basic si l’origine change. Les requêtes de position utilisent `Range`. Une réponse 206 commençant à une autre position est refusée. Si un serveur ignore Range et répond 200, le client doit parcourir les octets précédents : l’opération reste correcte mais peut être très lente et consommer du réseau à chaque déplacement.

## Lecture, indexation et vocalisation

| Opération | Garantie et limite |
| --- | --- |
| Décoder un média | Dépend du conteneur, des codecs Media3/Android et du serveur ; l’ouverture d’une vidéo n’établit pas la présence d’une piste textuelle |
| Indexer un conteneur progressif local ou distant | `FullSubtitleIndexer` utilise la même factory de données et les mêmes parseurs textuels ; toutes les occurrences extraites doivent garder identité et minutage |
| Lire HLS/DASH | Modules Media3 présents ; la lecture d’un manifeste n’établit pas un index complet de tous ses segments et pistes |
| Lire RTSP ou un direct | Lecture possible selon le serveur ; pas de piste complète finie dont tous les événements futurs seraient déjà connus |
| Préparer intégralement/traduire | Nécessite un index complet réellement établi ; manifeste, direct ou source non indexable doivent produire une limite explicite |
| Ajouter un sous-titre distant | Import borné via la même factory ; maximum **8 Mio**, puis parseurs habituels |

**La vocalisation des occurrences reçues reste exigée sur une source réseau.** Un transport différent ne doit pas remplacer le pipeline de narration par un parcours qui ignore dialogues, panneaux, chevauchements ou répétitions. Le bilan ne peut toutefois compter les événements jamais extraits. Une indexation interrompue ne doit pas être présentée comme complète ; une préparation ou une traduction indisponible ne doit pas être masquée par un saut silencieux.

Les pistes PGS/VobSub et les sous-titres inscrits dans les pixels ne deviennent pas textuels parce que la vidéo provient d’un serveur. L’OCR reste absent. Le lecteur ne déchiffre pas les DRM d’un service tiers.

## Bornes, annulation et erreurs

| Ressource | Borne du code |
| --- | --- |
| Dossier distant | **5 000 entrées** ; dépassement = erreur et sous-dossier demandé, sans pagination annoncée |
| FTP brut | 16 Kio par ligne de listing, 8 Mio de caractères de listing ; liens symboliques exclus |
| WebDAV XML | **4 Mio**, profondeur XML limitée à 32 ; DTD/entités rejetées ; réponses hors origine ou hors dossier ignorées |
| FTP/FTPS, SFTP, SMB | Connexion et principales lectures bornées à **15 s** par opération ; ce n’est pas une durée totale de film |
| WebDAV PROPFIND | Connexion 15 s, lecture 20 s, appel 45 s |
| HTTP média | Connexion **15 s**, lecture **20 s** ; la durée d’une lecture continue n’est pas bornée à la durée d’une requête courte |

Les délais peuvent se cumuler entre phases et dépendent aussi de la résolution réseau ; ils ne constituent pas une promesse de retour à la milliseconde. Annuler ferme les ressources enregistrées et empêche la publication d’un résultat devenu périmé. Une perte de réseau, un refus d’authentification, une empreinte différente, un certificat invalide ou un fichier interrompu restent des erreurs perceptibles.

Les tests sur serveurs réels de chaque protocole et la qualité d’une liaison longue doivent être constatés séparément. La disponibilité d’une bibliothèque cliente ou la compilation d’un module ne suffisent pas ; voir l’[état de validation 1.0.0](releases/1.0.0.md#validation-et-publication).
