# Sous-titres en voix

Lecteur vidéo Android qui vocalise les sous-titres et prépare leur traduction en français, avec des commandes accessibles, trois modes d’écoute et des réglages détaillés de l’image et du son.

**[Télécharger l’APK 0.4.0 — édition Traduction](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/download/v0.4.0/SousTitresVoix-0.4.0-traduction.apk)** · [Notes de version](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.4.0) · [Tous les guides](docs/README.md) · [Signaler un problème](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/issues/new/choose)

> **Installation distincte de la 0.3.2.** L’édition Traduction s’installe à côté de l’ancienne stable, avec sa propre signature et ses propres réglages. Sélectionner de nouveau la voix et les fichiers ; aucune désinstallation de la 0.3.2 n’est nécessaire.

| Repère | Version disponible |
| --- | --- |
| Application | **0.4.0 — édition Traduction**, code 6 |
| Android minimum | **Android 8.0 — API 26** |
| Contenu | Les **six étapes précédentes**, réunies avec la **traduction de l’étape 7** |
| Traduction intégrée | Azure Translator, deux points d’accès DeepL, serveur LibreTranslate HTTPS |
| Hors ligne | Lecture locale avec une voix Android hors ligne prête ; réutilisation d’une traduction complète encore en cache |
| Validation de l’APK publié | **612 exécutions JVM/Android réussies** et 39 tests Python d’outils ; lint sans erreur, avec 51 avertissements par variante |
| Sources de l’APK | [`32864ba`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/tree/32864ba732d834e71d87b887ae63f0d7bc09eb8c), [validation de `main`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40053284969) |

**Ce projet est réalisé intégralement par intelligence artificielle : la conception de l’application, l’écriture du code, les corrections, les tests automatisés et la documentation sont produits par IA à partir des demandes, des indications et des retours de son utilisateur.** Cette mention concerne le travail propre au projet. Android, Kotlin, Media3, les moteurs vocaux, les services de traduction et les autres composants tiers restent les créations de leurs auteurs respectifs.

## Sommaire

- [Installer et faire un premier essai](#installer-et-faire-un-premier-essai)
- [Traduire une piste en français](#traduire-une-piste-en-français)
- [Choisir le service de traduction](#choisir-le-service-de-traduction)
- [Comprendre les moteurs et modèles locaux](#comprendre-les-moteurs-et-modèles-locaux)
- [Régler la voix et le confort d’écoute](#régler-la-voix-et-le-confort-découte)
- [Régler l’image et le cadrage](#régler-limage-et-le-cadrage)
- [Régler le son du film](#régler-le-son-du-film)
- [Retrouver les sept étapes réunies](#retrouver-les-sept-étapes-réunies)
- [Compatibilité et limites](#compatibilité-et-limites)
- [Confidentialité et données](#confidentialité-et-données)
- [Vérifications et identité du téléchargement](#vérifications-et-identité-du-téléchargement)
- [Documentation et développement](#documentation-et-développement)

## Installer et faire un premier essai

1. Depuis le téléphone, [télécharger `SousTitresVoix-0.4.0-traduction.apk`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/download/v0.4.0/SousTitresVoix-0.4.0-traduction.apk). Ouvrir le fichier et autoriser son installation dans l’application utilisée pour l’ouvrir si Android le demande. Les archives **Source code** de GitHub ne sont pas des APK installables.
2. Ouvrir **Sous-titres en voix — Traduction 0.4.0**, puis **Réglages de la voix et du confort**.
3. Dans **Choisir le moteur vocal de CE lecteur**, sélectionner un moteur installé, puis **Choisir une voix hors ligne** et **Écouter un essai de la voix**. Une voix française prête est nécessaire pour vocaliser la traduction française. L’installation initiale d’un moteur ou d’une voix peut nécessiter Internet dans l’application de son fournisseur.
4. Lancer **Essai intégré : sous-titres et mire de cadrage**. Cet exemple synthétique permet d’essayer la voix et de vérifier l’affichage avant d’ouvrir un fichier personnel.
5. Ouvrir une vidéo locale, sélectionner sa piste de sous-titres textuels ou ajouter un fichier SRT, VTT, ASS/SSA. Choisir **Confort** ou **Adaptatif** pour préserver les phrases complètes. La traduction est une opération supplémentaire, décrite ci-dessous.

L’identité de cette édition est `fr.soustitresvoix.player.translation`. La clé privée de signature de la 0.3.2 n’étant plus accessible, l’édition Traduction possède une nouvelle signature durable sauvegardée hors du dépôt. Ses données et ses autorisations de fichiers sont distinctes de celles de la 0.3.2. Les vidéos d’origine ne sont pas modifiées.

Le [parcours de tests sur téléphone](docs/TESTS_TELEPHONE.md) accompagne le premier essai. L’[historique des versions et des branches](docs/HISTORIQUE_BRANCHES.md) permet de retrouver les éditions antérieures.

## Traduire une piste en français

La 0.4.0 traduit **toute la piste textuelle avant la lecture**, puis utilise le résultat pour la narration. **Les sous-titres affichés sur la vidéo restent dans la langue originale.** La cible du parcours intégré est le français.

1. Ouvrir la vidéo et choisir sa piste textuelle, intégrée ou ajoutée en fichier externe.
2. Ouvrir **Traduire les sous-titres en français**, depuis la bibliothèque ou les réglages accessibles en mode cinéma.
3. Choisir le fournisseur, indiquer la **langue des sous-titres** et renseigner les accès de son compte. Cette langue peut différer de celle de la piste audio.
4. Appuyer sur **Indexer la piste et estimer la traduction**. La lecture est mise en pause ; la piste et son volume sont contrôlés **sans envoi du texte au fournisseur**.
5. Vérifier le fournisseur, le nombre d’occurrences et le volume estimé. Confirmer **Traduire / réutiliser le cache** pour autoriser un nouvel appel si aucun cache complet correspondant n’est disponible. L’estimation n’est ni un prix garanti ni le solde du quota.
6. Attendre l’annonce de la piste française prête. Si la vocalisation est activée, choisir une voix française prête ; appuyer ensuite sur **Lire**. La fin de traduction ne reprend jamais automatiquement la vidéo.

| Situation | Comportement prévu |
| --- | --- |
| Traduction complète en cache | Réutilisation sans appel API et sans clé si la source, les langues et le moteur/serveur correspondent. |
| Réponse incomplète ou invalide | Refus de la piste entière ; aucun résultat partiel n’est activé. L’original ne remplace pas silencieusement une traduction absente. |
| Annulation ou changement de média/piste | Une réponse ancienne ne peut pas activer sa piste sur le nouveau média. Un appel déjà traité peut avoir consommé du quota. |
| Retour à la narration originale | Utiliser la commande explicite **Revenir explicitement à la piste originale**. |
| Dialogue, panneau ou répétition réelle | Chaque occurrence conserve son identité et ses minutages ; les textes identiques à des instants distincts restent distincts. |

Les lots sont bornés à **20 occurrences et 4 000 unités UTF-16**. Le cache privé conserve uniquement les pistes complètes, dans une limite de **32 Mio** ; il peut être évincé et ne constitue pas un export SRT. Il n’existe pas encore de reprise des lots partiels après interruption. La validation structurelle ne prouve pas la fidélité linguistique du résultat.

Le [guide fonctionnel](docs/TRADUCTION_ETAPE_7.md) détaille l’intégrité, l’annulation, le cache et les clés. Le [guide des moteurs](docs/GUIDE_MOTEURS_TRADUCTION.md) explique les comptes, les champs et le dépannage.

## Choisir le service de traduction

Le menu comprend **quatre choix pour trois services**. Ils utilisent des serveurs ; aucun poids de modèle n’est à installer dans cette version du lecteur. Les offres peuvent évoluer : vérifier les conditions du fournisseur avant de confirmer un service payant.

| Choix du lecteur | Fonction et prérequis | Configuration complète |
| --- | --- | --- |
| **Microsoft Azure Translator** | Traduction sur les serveurs Microsoft par l’API Translator. Préparer une ressource Translator, une clé et la région si elle est requise ; vérifier palier, quota et plafonds. Un déploiement Azure OpenAI n’est pas nécessaire. | [Ressource, clé, région et langue](docs/GUIDE_MOTEURS_TRADUCTION.md#microsoft-azure-translator--configuration-complète) |
| **DeepL — point d’accès API Free** | Utilise `api-free.deepl.com`. Préparer une clé **API** autorisée pour cet hôte. Le nom de l’endpoint ne garantit pas une offre gratuite pour un nouveau compte ; un abonnement au traducteur Web n’est pas automatiquement un accès API. | [Compte, endpoint et facturation](docs/GUIDE_MOTEURS_TRADUCTION.md#deepl--compte-point-daccès-et-facturation) |
| **DeepL — point d’accès API payant** | Utilise `api.deepl.com` avec la clé et le contrat correspondants. Contrôler les coûts dans le compte ; le lecteur n’achète pas d’abonnement. | [Choisir l’accès DeepL adapté](docs/GUIDE_MOTEURS_TRADUCTION.md#deepl--compte-point-daccès-et-facturation) |
| **LibreTranslate — serveur personnel HTTPS** | Traduction sur le serveur choisi, fondé sur Argos Translate. Fournir une adresse HTTPS à certificat valide, une paire de langues installée et une clé si l’instance l’exige. Hébergement et quotas sont distincts du logiciel libre. | [Serveur, adresse, clé et limites](docs/GUIDE_MOTEURS_TRADUCTION.md#libretranslate--utiliser-un-serveur-choisi) |

Chaque choix affiche ses prérequis et ses liens dans l’application. Le guide fournit les procédures depuis le navigateur Android, les codes de langue et les vérifications en cas de refus. Ne jamais publier une clé dans une issue ou une capture. Sa mémorisation locale chiffrée reste facultative.

Le lecteur ne propose pas encore de glossaire, de choix de modèle DeepL, de contexte additionnel, de comparaison automatique des fournisseurs ou d’estimation de prix contractuelle. Ces possibilités demandent une étape distincte.

## Comprendre les moteurs et modèles locaux

**Aucun moteur local de traduction, import Hugging Face ou backend neuronal GPU/NPU n’est intégré à la 0.4.0.** Télécharger un modèle ne l’active pas dans cette application. Réutiliser hors ligne une traduction distante mise en cache ne transforme pas son fournisseur en moteur local.

| Élément | Rôle |
| --- | --- |
| **Modèle** | Poids entraînés qui réalisent la tâche linguistique, avec leurs langues et leur licence. |
| **Runtime** | Logiciel qui charge et calcule l’architecture exacte du modèle sur Android. |
| **Format / quantification** | Organisation et précision des poids ; GGUF, Safetensors ou ONNX ne suffisent pas à prouver la compatibilité mobile. |
| **Catalogue** | Site qui présente et distribue des fichiers, comme Hugging Face, ModelScope ou le catalogue Argos. Ce n’est pas automatiquement un runtime. |

Le [guide des modèles locaux](docs/GUIDE_MOTEURS_TRADUCTION.md#modèles-et-solutions-locales-envisageables) décrit **Index-Translate-2B, ML Kit Translation, TranslateGemma, NLLB-200, MADLAD-400, M2M100, OPUS-MT/Marian et Argos Translate** : architecture, langues, licence, distribution et travail nécessaire sur Android. Il explique [comment examiner les fichiers sur Hugging Face](docs/GUIDE_MOTEURS_TRADUCTION.md#hugging-face-et-les-autres-catalogues--ce-quon-y-trouve), sans présenter leur consultation comme une installation fonctionnelle du lecteur.

L’[étude de traduction locale](docs/ETUDE_TRADUCTION_LOCALE.md) définit ce qu’il faudra vérifier avant de recommander un téléchargement : fichier et révision exacts, runtime compatible, intégrité, mémoire, qualité, durée et chauffe sur l’appareil cible. Les candidats n’ont pas été installés ni mesurés sur le OnePlus 15 dans ce projet. Le runtime, le téléchargement guidé et le catalogue de modèles vérifiés restent à décider ; ML Kit demande aussi une décision sur ses conditions et métriques.

## Régler la voix et le confort d’écoute

Le moteur vocal Android transforme le texte en audio ; le traducteur transforme une langue en une autre. Choisir une voix ne modifie pas le fournisseur de traduction.

Dans **Réglages de la voix et du confort**, choisir explicitement moteur, voix prête, vitesse et volume, puis écouter l’essai. Le [catalogue vocal](docs/VOIX_CATALOGUE_2026.md) présente des moteurs et ressources externes ; disponibilité, permissions et voix dépendent de leurs fournisseurs.

| Mode | Comportement |
| --- | --- |
| **Confort** | La vidéo attend lorsque la voix a besoin de temps pour terminer la phrase. |
| **Adaptatif** | Conserve le débit de base quand le budget le permet, accélère dans la limite réglée et attend si nécessaire. |
| **Continu** | La vidéo continue sans attendre ; une phrase trop longue peut être interrompue. Ce mode ne convient pas à l’objectif de vocalisation intégrale. |

Pour un premier essai Adaptatif, les réglages proposés sont **100 %** de vitesse de base, un plafond de **180 %** et **600 ms** de tolérance. Le confort dépend du moteur et du contenu. Une phrase préparée utilise sa durée WAV mesurée ; sans cache, le lecteur travaille avec une estimation.

La préparation des cinq prochaines minutes ou de toute la vidéo est explicite et annulable. Elle met la lecture en pause et utilise un cache audio borné, distinct du cache de traduction : préparer une œuvre entière ne garantit pas de conserver simultanément tous ses fichiers audio. Répéter la dernière phrase et reprendre restent des actions explicites. Une pause de l’utilisateur ne doit pas être annulée par une fin de synthèse ou une réponse ancienne.

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

## Retrouver les sept étapes réunies

La 0.4.0 réunit les travaux précédents. Les guides d’étape conservent leurs sources et résultats historiques ; leurs anciens effectifs ne remplacent pas la validation de l’APK publié.

| Étape | Travail conservé dans la 0.4.0 | Détails |
| --- | --- | --- |
| **1 — Modularisation** | Composants distincts pour lecture, vidéo, audio, indexation et narration autour de Media3 et de TTS Android. | [Modularisation](docs/MODULARISATION_ETAPE_1.md) |
| **2 — Capacités et observations** | Diagnostic distinguant demande, configuration et observation ; génération de média et confidentialité des informations copiées. | [Capacités et diagnostic](docs/CAPACITES_ETAPE_2.md) |
| **3 — Commandes d’image** | Contrat typé, valeurs bornées, refus explicites, générations et protections HDR. | [Commandes d’image](docs/COMMANDES_IMAGE_ETAPE_3.md) |
| **4 — Interface d’image** | Descriptions, saisie précise, corrections, cadrage, marges, zoom, Lanczos et luminosité de l’écran. | [Interface d’image](docs/INTERFACE_IMAGE_ETAPE_4.md) |
| **5 — Commandes du film** | Validation commune des volumes, fondus et atténuation spectrale, en préservant narration et pauses. | [Commandes audio](docs/COMMANDES_AUDIO_ETAPE_5.md) |
| **6 — Audio expert** | Saisie numérique, préampli, égaliseur, balance, mono, limiteur et diagnostic sur PCM16 compatible. | [Audio expert](docs/AUDIO_EXPERT_ETAPE_6.md) |
| **7 — Traduction** | Piste française complète, trois services/quatre choix, cache privé et clés chiffrées facultatives. | [Traduction](docs/TRADUCTION_ETAPE_7.md) |

Les [notes 0.4.0](docs/releases/0.4.0.md) et le [journal des changements](CHANGELOG.md) détaillent les ajouts et corrections. L’[historique des branches](docs/HISTORIQUE_BRANCHES.md) conserve les sauvegardes et références d’archives.

## Compatibilité et limites

| Domaine | Prise en charge et limites |
| --- | --- |
| **Vidéos locales** | Notamment MKV, MP4 et WebM ; codecs réellement lisibles selon Media3 et les décodeurs du téléphone. |
| **Sous-titres textuels** | Pistes intégrées et fichiers SRT, VTT, ASS/SSA. Fichiers externes limités à 8 Mio ; choix d’encodage pour les fichiers anciens. Panneaux et répétitions réelles sont préservés lorsqu’ils figurent dans la piste choisie. |
| **Sous-titres en images** | Pas d’OCR pour PGS/VobSub ou texte uniquement incrusté dans les pixels. |
| **Traduction** | Préparation avant lecture, cible française et affichage original. La fidélité, les noms et le registre doivent être évalués sur les langues et contenus voulus. |
| **Voix** | Moteur Android externe ; la voix hors ligne doit être installée et réellement prête. Aucun modèle vocal neuronal propre au projet n’est embarqué. |
| **Fonctions absentes** | Pas d’audiodescription des scènes, d’accès aux contenus DRM d’autres applications, de lecture durable en arrière-plan, d’export des traductions ou de runtime local de traduction. |
| **Validation matérielle** | Pas de résultat établi sur OnePlus 15 pour autonomie, chauffe ou accélération GPU/NPU. TalkBack réel, voix commerciales, HDR physique, Bluetooth et séances prolongées exigent des essais distincts. |

Moteur local avec installation guidée, contexte/glossaires, reprise de lots partiels, export, profils audio et comparaison A/B restent des propositions soumises à décision. Ils ne sont pas présentés comme disponibles.

## Confidentialité et données

- **Fichiers :** ouverture par le sélecteur Android, sans permission microphone, caméra ou accès général au stockage. Vidéos et audio ne sont pas téléversés par la traduction.
- **Traduction :** permission Internet pour les demandes explicitement confirmées. Le fournisseur reçoit le texte et les paramètres nécessaires selon ses conditions ; l’indexation et l’estimation seules ne l’envoient pas.
- **Transport :** HTTPS, certificats système, délais et tailles bornés, redirections refusées, aucune relance automatique facturable. Les erreurs affichées ne recopient ni clé, ni texte privé, ni corps de réponse du fournisseur.
- **Clés API :** mémorisation facultative en AES-GCM avec Android Keystore, sans repli en clair ; clés LibreTranslate séparées par serveur. Sauvegarde Android de l’application désactivée. Un appareil compromis reste une limite de protection.
- **Caches :** stockage privé, caches de traduction et de voix distincts. Le cache de traduction contient du texte de sous-titres et n’est pas annoncé comme chiffré ; il est évictable et ne constitue pas une sauvegarde durable.
- **Composants externes :** vérifier les permissions et la voix hors ligne du moteur vocal. Les liens documentaires s’ouvrent sur demande. Les essais du projet utilisent des contenus synthétiques et des réponses API contrôlées.

Annuler ne rembourse pas les caractères déjà traités. Avant une nouvelle tentative, contrôler quota et plafonds du compte. Les clés privées de signature restent hors du dépôt et des releases.

## Vérifications et identité du téléchargement

Le [run `40053284969`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40053284969) a réussi sur le commit source exact [`32864ba`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/32864ba732d834e71d87b887ae63f0d7bc09eb8c), avant publication. [TESTING.md](TESTING.md) conserve les résultats, les erreurs intermédiaires corrigées et les limites de preuve.

| Vérification exécutée | Résultat |
| --- | --- |
| JVM debug | 87 tests du noyau + 137 tests de l’application réussis |
| JVM release | Nouvelle exécution des mêmes 87 + 137 tests, réussie |
| Android 36 release | Deux passages de 82 tests instrumentés réussis sur la même paire d’APK |
| Total JVM/Android | **306 méthodes distinctes, 612 exécutions**, sans échec, erreur, méthode ignorée, manquante ou dupliquée |
| Outils Python | **19** tests de publication + **13** de synchronisation des releases + **7** de rangement documentaire réussis |
| Lint debug et release | **0 erreur, 51 avertissements par variante** ; les avertissements restent visibles |
| Construction et publication | APK et APK de tests compilés ; manifeste, identité, signatures, alignement et empreintes contrôlés ; contenu hors signatures comparé à l’APK testé, puis téléchargement revérifié avant publication |

L’instrumentation utilise Android, Media3 et un service TTS de fixture contrôlé. Les tests API n’utilisent pas de compte payant réel. Ils ne prouvent pas une traduction parfaite, la qualité acoustique d’une voix externe, les droits d’un compte personnel ou l’absence universelle de bug.

| Identité de l’APK 0.4.0 | Valeur |
| --- | --- |
| Fichier | `SousTitresVoix-0.4.0-traduction.apk` |
| Taille | **5 066 480 octets** |
| Application | `fr.soustitresvoix.player.translation` |
| SHA-256 | `30d1126392a9fd995867f599b12436b1e2d9e8e1d41bc60d20dca76c9b88f298` |
| Certificat SHA-256 | `304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d` |
| Fichier de contrôle | [`SHA256SUMS`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/download/v0.4.0/SHA256SUMS), sur la [release 0.4.0](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.4.0) |

## Documentation et développement

| Besoin | Point d’entrée |
| --- | --- |
| Parcourir les guides | [Index de la documentation](docs/README.md) |
| Configurer un traducteur ou comprendre les modèles | [Moteurs, comptes, modèles et dépannage](docs/GUIDE_MOTEURS_TRADUCTION.md) |
| Comprendre piste française, cache et clés | [Fonctionnement de la traduction](docs/TRADUCTION_ETAPE_7.md) |
| Étudier une future traduction locale | [Étude locale et protocole d’évaluation](docs/ETUDE_TRADUCTION_LOCALE.md) |
| Essayer la version sur téléphone | [Parcours manuel](docs/TESTS_TELEPHONE.md) |
| Régler image et son | [Image et cadrage](docs/INTERFACE_IMAGE_ETAPE_4.md), [mixage](docs/COMMANDES_AUDIO_ETAPE_5.md), [audio expert](docs/AUDIO_EXPERT_ETAPE_6.md) |
| Choisir un moteur vocal | [Catalogue vocal daté](docs/VOIX_CATALOGUE_2026.md) |
| Tester, compiler et signer | [Procédure de vérification](TESTING.md) |
| Comprendre le code | [Architecture](docs/ARCHITECTURE.md) |
| Retrouver versions et sources | [Changelog](CHANGELOG.md), [historique des branches et APK](docs/HISTORIQUE_BRANCHES.md) |
| Contribuer | [Procédure de contribution](CONTRIBUTING.md), [consignes pour les agents](AGENTS.md) |

Le dépôt contient `app/` pour l’application et les tests Android, `core/` pour le moteur indépendant d’Android, `docs/` pour les guides, `examples/` pour les sous-titres synthétiques et `tools/` pour les contrôles. La compilation utilise Java 17 ; Robolectric Android 36 utilise Java 21. Les versions et commandes figurent dans [TESTING.md](TESTING.md). FFmpeg génère l’exemple lors de la compilation ; il n’est pas à installer sur le téléphone.

**Tout travail terminé et vérifié est intégré à `main`.** Les propositions passent par une demande de fusion et leurs contrôles ; le commit fusionné est ensuite vérifié sur `main`. Le tag `v0.4.0` conserve les sources exactes de l’APK publié, tandis que `main` peut recevoir des améliorations documentaires. Sauvegardes et archives permettent de retrouver les étapes sans confondre une ancienne branche de travail avec la version à installer.
