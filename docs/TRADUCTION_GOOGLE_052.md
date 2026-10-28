# Google en ligne, Google local et lecture pendant la traduction

[Accueil](../README.md) · [Documentation](README.md) · [Moteurs de traduction](GUIDE_MOTEURS_TRADUCTION.md) · [Services gratuits](TRADUCTION_GRATUITE_ET_LIMITES.md) · [Notes 0.5.2](releases/0.5.2.md)

Ce guide décrit la **0.5.2**, Google et la traduction pendant la lecture. Le [téléchargement et les notes de version](releases/0.5.2.md), [TESTING.md](../TESTING.md) et [GitHub Actions](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/workflows/android.yml) donnent accès à la distribution et aux preuves de validation. Il ne présente ni un service Google comme infaillible, ni une qualité linguistique comme déjà mesurée sur le téléphone.

## Choisir le moteur

| Choix | Fonctionnement | Préparation nécessaire | Limites principales |
| --- | --- | --- | --- |
| **Google en ligne, sans clé** | Le texte est envoyé directement à un accès web Google non officiel | Connexion Internet ; langue source explicite ; confirmation de la traduction | Disponibilité non garantie, limitations temporaires possibles, aucun quota illimité promis |
| **Google local — ML Kit** | Le SDK Google traduit sur le téléphone avec ses modèles | Téléchargement initial des modèles nécessaires ; Wi-Fi par défaut | Langues prises en charge par ML Kit ; qualité différente de l’accès en ligne ; modèles à conserver sur l’appareil |
| **Azure, DeepL, LibreTranslate** | Adaptateurs déjà présents, conservés | Accès ou serveur correspondant au moteur choisi | Quotas, coûts et langues propres au fournisseur |

Un seul moteur est sélectionné pour une tâche. Une erreur Google n’active pas automatiquement DeepL, Azure ou ML Kit. Les résultats conservés restent liés au moteur et aux paramètres qui les ont produits.

La narration continue d’utiliser une **voix Android distincte du traducteur**. Pour prononcer les traductions françaises, choisir une voix française prête ; installer un modèle ML Kit n’installe pas une voix.

## Parcours sur le téléphone

1. Ouvrir la vidéo et choisir sa piste de sous-titres textuels, ou importer un SRT, VTT, ASS/SSA.
2. Ouvrir **Traduire les sous-titres en français**, puis sélectionner Google en ligne ou Google local et la langue des sous-titres.
3. Pour regarder pendant le travail, activer **Traduction progressive** et choisir l’avance souhaitée. Pour Google en ligne, conserver d’abord l’intervalle conseillé ; pour ML Kit, vérifier la connexion nécessaire au téléchargement des modèles.
4. Utiliser **Indexer la piste et estimer la traduction**. Cette phase identifie les occurrences et ne traduit pas encore les textes.
5. Lire la destination, les paramètres et le volume estimé, puis confirmer la traduction. Le plafond local de nouveaux caractères demeure un contrôle de la tâche, pas une mesure du quota Google.
6. Dès que le tampon annoncé est prêt, appuyer sur **Lire**. La traduction continue sur son thread de travail pendant le visionnage.
7. Consulter l’état en cas d’attente. Une Pause volontaire reste prioritaire ; l’arrivée d’un lot ne doit jamais l’annuler.

Les pistes bitmap et les textes incrustés dans les pixels nécessitent un OCR, absent de cette version. Ni Google en ligne ni ML Kit ne transforment ici une image de sous-titres en texte.

## Lire pendant la traduction sans perdre de réplique

Le mode progressif publie uniquement un **préfixe continu validé** de la piste. Chaque texte traduit reste associé à son occurrence et à son minutage d’origine. Une piste partielle n’est jamais annoncée comme complète.

L’avance de traduction est de **30 secondes par défaut**, réglable de **10 à 120 secondes**. Elle représente une couverture temporelle disponible avant la prochaine occurrence manquante, pas une garantie que chaque seconde contient un dialogue. Une piste terminée peut devenir prête sans attendre artificiellement ce délai.

- **Tampon suffisant** : la lecture peut commencer sur demande et la traduction continue.
- **Tampon rattrapé** : la vidéo attend à la frontière de traduction avant de perdre une occurrence indisponible.
- **Tampon reconstitué** : la reprise automatique est possible uniquement si cette attente provenait de la traduction et si l’intention de lecture reste valide.
- **Pause utilisateur ou changement de contexte** : aucune reprise tardive. Un déplacement, une autre vidéo/piste, la fermeture ou une erreur invalident l’autorisation précédente.

La traduction est une étape distincte du temps nécessaire à la voix. Les modes Confort et Adaptatif peuvent également faire attendre la vidéo pour terminer la narration ; une progression de traduction ne doit pas annuler ces protections.

Un tampon plus grand donne davantage de marge, mais retarde le premier départ. Un tampon plus petit peut permettre de commencer plus tôt et provoquer davantage d’attentes si le fournisseur ralentit. Aucun réglage ne garantit une lecture ininterrompue avec toutes les densités de sous-titres et tous les réseaux.

## Google en ligne : rythme, reprise et économie d’appels

### Paramètres et protections

| Mécanisme | Comportement de la 0.5.2 |
| --- | --- |
| Intervalle minimal entre appels | **1 000 ms par défaut**, réglable de **500 à 10 000 ms** |
| Concurrence | Requêtes sérialisées par le travail de traduction ; pas de rafale parallèle de phrases |
| Reprises automatiques | Au plus **trois nouvelles tentatives** après les réponses temporaires retenues, dont HTTP 429 et les erreurs serveur prises en charge |
| Attente maximale de reprise | **120 secondes cumulées par requête**, hors durée réseau ; attente annulable |
| `Retry-After` | Respect du délai valide demandé par le serveur ; arrêt lorsque ce délai dépasserait le budget d’attente, sans renvoi anticipé |
| Refus ou réponse invalide | Arrêt perceptible, sans présenter le résultat comme une traduction complète |
| Protection du texte | Une occurrence par unité de travail, réponses validées et progression conservée |

Commencer à **1 000 ms**. Augmenter l’intervalle réduit la fréquence des requêtes, au prix d’une préparation plus longue. La valeur minimale de 500 ms ne constitue pas un débit garanti par Google. Un accès non officiel peut changer ou refuser une adresse même avec un rythme modéré.

Les erreurs d’accès, redirections ou réponses illisibles ne donnent pas lieu à une recherche de route de contournement. Le lecteur ne change pas d’IP, ne fait pas tourner des serveurs intermédiaires et ne multiplie pas les points d’accès après un blocage.

### Réutilisation sans suppression d’occurrences

Le lecteur réutilise d’abord une traduction complète compatible ou une progression validée. Le moteur Google en ligne peut aussi réutiliser **en mémoire, pendant le travail**, le résultat d’un texte strictement identique, pour éviter un appel redondant.

Cela économise une traduction, **pas une vocalisation**. Si « Bonjour » apparaît à trois instants distincts, les trois occurrences restent présentes, avec leurs identifiants et leurs horaires ; elles doivent toutes être vocalisées. Une traduction réutilisée peut en revanche manquer une nuance de contexte : cette économie ne constitue pas une amélioration linguistique démontrée.

Le cache complet, la progression partielle et la conservation durable sont distincts. Le cache complet évictable reste limité à **32 Mio** ; conserver ou exporter une piste complète permet de la réutiliser selon le parcours existant. Une fermeture de processus n’implique pas une reprise réseau automatique : il faut rouvrir et confirmer la tâche appropriée.

## Google local : ML Kit

L’adaptateur utilise **`com.google.mlkit:translate:17.0.3`**, avec téléchargement des modèles nécessaires et contrôle de la paire de langues avant traduction. Le lecteur refuse une langue non prise en charge au lieu de lui substituer une langue voisine. La liste est celle du SDK ; les variantes de codes d’un autre fournisseur ne doivent pas être recopiées arbitrairement.

Le téléchargement initial exige le **Wi-Fi par défaut**. Une option explicite permet d’utiliser le réseau mobile ; son volume relève alors du forfait de l’utilisateur. Si les modèles requis sont déjà disponibles, la traduction peut s’exécuter hors connexion. Le téléchargement d’un modèle peut prendre du temps ; une attente ne doit pas être présentée comme une piste traduite. Le moteur réutilise son traducteur et la préparation des modèles pendant le travail, sans téléchargement par occurrence. Annuler interrompt l’attente et l’activation des résultats côté lecteur ; le SDK peut toutefois terminer un téléchargement déjà lancé.

ML Kit utilise les modèles du mode hors connexion de Google Traduction. Entre deux langues autres que l’anglais, Google indique un pivot par l’anglais. La fidélité des dialogues, négations, noms et idiomes doit donc être appréciée sur la paire exacte ; le fonctionnement local ne garantit pas le même résultat que le site Google.

Les traductions sont attribuées à **Google** dans l’interface, avec un badge visuel adjacent et un libellé accessible. Le texte des sous-titres est traité sur l’appareil avec ce moteur. L’application demande une confirmation avant l’initialisation du SDK et le téléchargement des modèles.

**Confidentialité ML Kit.** Google déclare la collecte de données techniques : modèle et système du téléphone, nom/version de l’application, identifiants d’installation, performances, tailles des entrées/sorties, langues configurées, événements et erreurs. Le SDK utilise également Firebase pour sa configuration et ses diagnostics. Cette télémétrie et les téléchargements ne sont pas un envoi du texte des sous-titres pour traduction, mais « traduction locale » ne signifie pas « aucun contact réseau ». La [déclaration officielle de collecte](https://developers.google.com/ml-kit/android-data-disclosure) détaille les catégories et leur usage.

Sources Google : [SDK Android](https://developers.google.com/ml-kit/language/translation/android), [langues prises en charge](https://developers.google.com/ml-kit/language/translation/translation-language-support), [modèles et limites](https://developers.google.com/ml-kit/language/translation), [attribution](https://developers.google.com/ml-kit/language/translation/translation-terms), [conditions et confidentialité](https://developers.google.com/ml-kit/terms).

### Provenance du badge Google

Le fichier `app/src/main/res/drawable-nodpi/google_translate_badge.png` est une copie sans modification du PNG couleur **528 × 48 pixels** (`color-regular@3x.png` selon sa provenance publiée). Les [exigences Google](https://docs.cloud.google.com/translate/attribution) imposent un badge adjacent aux résultats, sans modification du graphisme.

- Source originale déclarée : [archive officielle Google](https://docs.cloud.google.com/static/translate/images/google-translate-attribution.zip).
- Copie téléchargée : [PNG WhisperNote au commit 39bd581840708dead9e25d1a0ca7d0e01c02d662](https://github.com/nazmos-sakib/WhisperNote/blob/39bd581840708dead9e25d1a0ca7d0e01c02d662/app/src/main/res/drawable-nodpi/powered_by_google_translate.png). La [note de provenance du même commit](https://github.com/nazmos-sakib/WhisperNote/blob/39bd581840708dead9e25d1a0ca7d0e01c02d662/verification/on-demand-translation.txt) cite l’archive et le nom du PNG. Aucun code de cette application n’est repris.
- SHA-256 du fichier intégré : `44aab849963bc32493c1b2ff4ed9d47aea8d16cdfa12d3aaebef22feb2ba2ca9` ; **12 770 octets**.

Le téléchargement direct de l’archive officielle a renvoyé une page « SiteUnavailable » depuis l’environnement de travail. L’identité binaire avec la copie du dépôt est vérifiée ; une comparaison indépendante avec le ZIP Google n’a donc pas pu être effectuée. La marque demeure attribuée à Google et soumise à ses règles d’utilisation.

## Projets étudiés et choix d’intégration

| Projet primaire | Élément examiné | Choix dans ce lecteur |
| --- | --- | --- |
| [Subtitle Edit](https://github.com/SubtitleEdit/subtitleedit/blob/main/docs/features/auto-translate.md), [client Google](https://github.com/SubtitleEdit/subtitleedit/blob/main/src/libuilogic/AutoTranslate/GoogleTranslateV1.cs) | Traduction de sous-titres, réponse JSON, reprises bornées et incidents d’accès | Référence principale pour une implémentation Kotlin propre ; conservation des règles de cette application sur les occurrences et la pause |
| [Googletrans](https://github.com/ssut/py-googletrans) | Client Python de l’interface web et avertissements sur sa stabilité | Comparaison des limites et des réponses ; aucun runtime Python embarqué |
| [deep-translator](https://github.com/nidhaloff/deep-translator/blob/master/deep_translator/google.py) | Extraction HTML de la version mobile Google | Pas de dépendance à un sélecteur HTML de page web dans le moteur retenu |
| [Translate You](https://github.com/you-apps/TranslateYou), [adaptateur Lingva](https://github.com/you-apps/TranslateYou/blob/master/translation-engines/src/main/java/net/youapps/translation_engines/lv/LingvaTranslate.kt) | Intégration Android d’un serveur Lingva intermédiaire | Pas de serveur Lingva imposé, ni de copie de ce code GPL dans l’implémentation |

Ces références ne sont pas quatre modèles à combiner. Elles montrent plusieurs façons d’accéder à un service. Le code du lecteur conserve une seule implémentation par moteur ; les autres projets servent à examiner les cas utiles et les limites. Les notices de tiers nécessaires restent distinctes de la mention du travail propre au projet réalisé par IA.

Le [ticket Subtitle Edit #14015](https://github.com/SubtitleEdit/subtitleedit/issues/14015) documente une panne réelle et son traitement. Ce précédent justifie la gestion de l’échec ; il ne prouve pas une disponibilité permanente sur le réseau d’un autre utilisateur.

**Google Cloud Translation et Google Apps Script ne sont pas intégrés.** Le premier demande une configuration Google Cloud et de facturation ; le second un déploiement personnel et ses propres quotas. Leur étude figure dans le [guide des services gratuits](TRADUCTION_GRATUITE_ET_LIMITES.md).

## Audio avancé : correction associée

Dans **Réglages de la voix et du confort → Son**, le bouton des traitements avancés utilisait auparavant une colonne temporaire comme propriétaire. Cette colonne était détachée après le rangement des commandes dans les rubriques : la protection des fenêtres empêchait donc l’ouverture du DSP.

La 0.5.2 rattache ce dialogue au véritable panneau de paramètres. Les fenêtres de bande et de saisie gardent leur fermeture en cascade lorsque leur parent disparaît. Les commandes audio restent séparées de la narration ; leur ouverture ne doit ni démarrer une voix ni reprendre le film. Les chemins **Volumes rapides** et **Son** disposent désormais de régressions distinctes.

## Validation et limites de preuve

Les vérifications doivent porter sur l’intégrité et l’ordre des occurrences, les répétitions réelles, les erreurs et reprises, l’annulation, le téléchargement des modèles, le tampon progressif et la priorité d’une Pause utilisateur. Des réponses contrôlées permettent de tester les refus et délais sans consommer de quotas personnels.

Un essai réel borné utilise des textes synthétiques ; aucune vidéo personnelle n’est envoyée. Une réponse réelle correcte prouve seulement l’essai réalisé à cet instant. Elle ne valide ni tous les films, ni toutes les langues, ni une absence future de blocage.

Le workflow demandé reste obligatoire : **construire et tester l’APK dans GitHub Actions avant chaque fusion**, puis reconstruire après fusion sans répéter les suites, avec vérification de l’arbre, des parents, du contenu de l’APK et de la signature durable avant publication. Les résultats et effectifs sont consignés seulement après leur exécution dans [TESTING.md](../TESTING.md).
