# Sous-titres en voix

Lecteur Android conçu pour **vocaliser chaque occurrence textuelle distincte de la piste choisie** : dialogues, panneaux, chevauchements et répétitions réelles. Les modes Confort et Adaptatif laissent à la voix le temps de terminer. Une traduction indisponible provoque une attente explicite.

**Version 1.1.1 — code 25.** Les [notes de version](docs/releases/1.1.1.md) décrivent les changements et les critères de validation ; le corps de la [release GitHub v1.1.1](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v1.1.1) est la référence pour les commits, exécutions, empreintes et fichiers de cette distribution. Le numéro stable ne constitue pas une certification de chaque modèle, codec, serveur ou accélérateur.

[Guide utilisateur](docs/guide-utilisateur.md) · [Sources locales et réseau](docs/sources-medias.md) · [Modèles de traduction](docs/modeles-traduction.md) · [Documentation complète](docs/README.md) · [Développer](CONTRIBUTING.md) · [Validation](TESTING.md)

## Installer et commencer sur Android

L’APK et son fichier `SHA256SUMS` se recherchent dans les assets de la [release v1.1.1](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v1.1.1). Vérifier les preuves et le certificat de la distribution avant installation. Une archive « Source code » n’est pas une application installable ; un APK absent des assets ou non signé avec le certificat durable n’est pas une distribution vérifiée.

| Repère | Version 1.1.1 |
| --- | --- |
| Android minimum | Android 8.0 — API 26 |
| Identité d’installation | `fr.soustitresvoix.player.translation`, conservée depuis la série Traduction 0.4.0 |
| Mise à jour | Même certificat durable obligatoire ; conserver l’application et ses données |
| Architectures compilées | ARM64 et x86_64 ; accélérateurs facultatifs ARM64 selon le téléphone |
| Navigation | Accueil, Explorer, Traduction, Paramètres ; thème Material 3 sombre |
| Lecture | Fichiers locaux, stockage partagé/SD accessible, sources directes et serveurs réseau pris en charge |
| Sous-titres | Pistes textuelles et fichiers SRT, VTT, ASS/SSA. OCR local avec modèle importé et validation du texte |
| Traduction | GGUF local, Google local ML Kit, Google en ligne, Azure, DeepL et LibreTranslate HTTPS |

1. Dans **Paramètres → Réglages de la voix et du confort → Voix**, sélectionner un moteur Android et une voix installée dans la langue à prononcer. Écouter l’essai.
2. Dans **Synchronisation**, choisir **Préréglage : lecture intégrale** ou **Préréglage : compréhension confortable**.
3. Dans **Accueil**, lancer **Essai intégré : sous-titres et mire de cadrage**, puis vérifier voix, commandes et image sur ce contenu synthétique.
4. Ouvrir une vidéo avec le sélecteur Android ou **Explorer**, choisir une piste textuelle ou ajouter un fichier de sous-titres. Une piste française peut être vocalisée directement.
5. Appuyer sur **Lire**. Une restauration, un changement d’onglet ou une préparation ne lance pas seuls la lecture.

**Ne pas désinstaller l’édition Traduction pour préparer une mise à jour.** L’ancienne 0.3.2 utilise une autre identité (`fr.soustitresvoix.player.stable`) et ses données sont distinctes. La signature de chaque APK distribué doit être contrôlée ; un APK CI à signature éphémère ne remplace pas la release signée durablement.

## Ce que fait le lecteur

- **Lecture intégrale** : index de la piste, attente de l’image et comparaison entre occurrences attendues et terminées. Le bilan donne les horaires des répliques restantes et permet de les lire.
- **Confort et Adaptatif** : attente lorsque la phrase ne tient pas dans la vidéo ; Pause utilisateur, déplacement et changement de média invalident les reprises devenues périmées.
- **Réglages précis** : voix, synchronisation, sous-titres, image et son ; champs bornés, pas, présélections et boutons Minimum/Maximum pour les réglages concernés.
- **Préparation** : cache vocal évictable, bibliothèque explicitement conservée hors ligne, séances et réparation d’un accès perdu, bilan de vocalisation sans texte des sous-titres.
- **Traduction complète ou progressive** : publication de résultats validés, attente avant le premier texte manquant, reprise du travail et export SRT/VTT distinct de l’original. Le SRT autonome fonctionne sans vidéo.
- **Sources réseau** : HTTP/HTTPS, HLS, DASH, RTSP, FTP, FTPS explicite, SFTP, WebDAV et SMB2/3 selon le parcours. Lecture d’un flux et indexation complète de ses sous-titres sont deux capacités distinctes ; voir la [matrice des sources](docs/sources-medias.md).

Sur un fichier réseau progressif, **Lire** peut démarrer avec les occurrences reçues au fil de la lecture. Le bilan indique cette couverture progressive jusqu’à une indexation complète explicitement demandée. Les sous-titres brefs, répétés ou reçus tardivement gardent leurs occurrences et leur vocalisation. Les fichiers locaux, la traduction, une synchronisation corrigée et l’association de deux pistes conservent leur préparation complète.

Les quatre onglets organisent les commandes existantes. Passer d’un onglet à l’autre conserve la session et les tâches en cours. Les réglages détaillés gardent leurs huit rubriques et leur recherche sans accents.

## Traduction GGUF locale

Le fichier est choisi explicitement, copié dans l’espace privé et identifié par son SHA-256. Les poids ne sont ni embarqués ni téléchargés automatiquement. llama.cpp exécute le modèle sans PocketPal, serveur ni clé API. L’import d’un GGUF ne garantit pas son architecture, son format de conversation, sa mémoire disponible, sa qualité ou sa vitesse.

Le [guide des modèles](docs/modeles-traduction.md), accessible aussi depuis l’onglet **Traduction**, décrit les profils, les réglages, les essais déjà observés et leurs limites. La 1.0.0 ajoute pour **Index-Translate** une fenêtre composée de deux sources précédentes et deux suivantes au maximum, sans propager leurs traductions. Les voisins sont retirés entiers si le budget l’exige ; le texte courant n’est pas tronqué. Les trois stratégies antérieures restent disponibles.

Le contexte natif de **2 048 tokens**, la limite de **512 tokens par génération** et le choix greedy restent conservés. Le cache K/V F16 demeure le réglage initial ; Q8_0 et les réglages de mémoire sont des choix explicites. Aucun refus ne doit être fondé sur la seule taille du GGUF et aucune précision inférieure ne doit être choisie silencieusement.

CPU, OpenCL, Vulkan et Hexagon/HTP sont des modes distincts. Une sélection, un chargement ou quelques nœuds de graphe observés ne prouvent ni l’exécution intégrale sur un accélérateur, ni un gain. Les compteurs distinguent séance du moteur, appels tentés/réponses mesurées, tokens d’entrée/sortie, caches, RSS de processus et petits buffers réutilisables.

## Limites et confidentialité

Les pistes PGS/VobSub peuvent passer par une reconnaissance locale et une relecture de chaque résultat. La couverture des textes incrustés dépend des images effectivement analysées et vérifiées. Pas d’accès aux DRM d’autres applications, d’audiodescription automatique ni de fonctionnement durable en arrière-plan garanti. Les codecs et voix disponibles dépendent du téléphone et du moteur vocal externe ; TalkBack réel, Bluetooth, autonomie et longues séances demandent leurs propres essais.

L’indexation d’une source progressive distante utilise le même transport et les mêmes parseurs que le lecteur. Certaines pistes WebVTT de contenus HLS/DASH finis sont indexables. Les directs et les formats hors périmètre gardent une couverture partielle explicite. Une absence d’index complet doit rester visible ; le lecteur ne doit pas prétendre avoir couvert tous les événements ni sauter un texte pour masquer cette limite.

Les fournisseurs de traduction en ligne reçoivent seulement les textes et options confirmés, jamais le film ou son audio. Leurs quotas, facturation, disponibilité et conditions peuvent évoluer. ML Kit traduit localement après téléchargement ; le SDK conserve ses conditions et sa télémétrie technique. Les connexions réseau et secrets sont chiffrés avec Android Keystore ; les séances enregistrent des références opaques. HTTP et FTP restent des transports non chiffrés choisis explicitement : préférer HTTPS, FTPS ou SFTP sur un réseau non fiable. Les adaptateurs de traduction exigeant HTTPS conservent cette exigence.

Les préparations locales peuvent contenir du texte traduit et de l’audio synthétisé. Les originaux ne sont pas modifiés. Pour signaler un problème, fournir version, Android, appareil, source/protocole, moteur, réglages et étapes reproductibles avec un exemple synthétique ; retirer secrets et liens personnels.

## Documentation et contribution

| Objectif | Référence |
| --- | --- |
| Utiliser voix, lecture, réglages, séances et préparation | [Guide utilisateur](docs/guide-utilisateur.md) |
| Parcourir stockage/SD, configurer un serveur, comprendre les limites d’un flux | [Sources médias](docs/sources-medias.md) |
| Importer un modèle, régler le contexte, interpréter les mesures | [Modèles de traduction](docs/modeles-traduction.md) |
| Modifier le code sans perdre une occurrence ou autoriser une reprise périmée | [Architecture et invariants](docs/ARCHITECTURE.md) |
| Compiler, tester, signer et publier avec preuves | [Contribution](CONTRIBUTING.md), [validation](TESTING.md) |
| Retrouver une version et ses preuves propres | [Journal](CHANGELOG.md), [index des archives](docs/README.md#versions-et-archives) |

**Ce projet est réalisé intégralement par intelligence artificielle : la conception de l’application, l’écriture du code, les corrections, les tests automatisés et la documentation sont produits par IA à partir des demandes, des indications et des retours de son utilisateur.** Cette mention concerne le travail propre au projet. Android, Kotlin, Media3, llama.cpp, les moteurs vocaux, services de traduction et autres composants tiers restent les créations de leurs auteurs respectifs ; voir les [attributions](ATTRIBUTIONS.md).

Le dépôt ne définit actuellement pas de licence générale de redistribution du travail propre au projet. La disponibilité publique du code et les licences des dépendances ne remplacent pas une telle licence.
