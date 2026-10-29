# Modèles GGUF et profils de traduction — 0.5.11

[Accueil](../README.md) · [Documentation](README.md) · [Qualité et contexte](TRADUCTION_QUALITE_0510.md) · [Notes 0.5.11](releases/0.5.11-models.md) · [Validation](../TESTING.md)

La candidate **0.5.11-models / code 18** élargit la reconnaissance des profils de traduction. Elle ajoute **Index-Translate** et un parcours utilisant le **format de conversation reconnu dans le gabarit intégré au GGUF** pour d’autres modèles instruct nativement compatibles. TranslateGemma et Hy-MT conservent leurs profils spécialisés.

Cette évolution traite une erreur de profil non reconnu ; elle ne prouve pas qu’un modèle de neuf milliards de paramètres en Q6 tient en mémoire, fonctionne sur GPU ou traduit mieux sur le téléphone. Les poids et la quantification du fichier choisi ne sont pas modifiés. L’application ne télécharge aucun modèle automatiquement.

## Distinguer les niveaux de compatibilité

| Niveau | Ce qui doit convenir | Ce que cela ne prouve pas |
| --- | --- | --- |
| Fichier GGUF | Fichier complet, lisible et reconnu par le lecteur de métadonnées | Poids valides et calcul natif possible |
| Architecture et quantification | Opérateurs, tenseurs et type de poids pris en charge par le moteur compilé | Mémoire suffisante ou backend GPU adapté |
| Profil de traduction | Instructions et encadrement de conversation correspondant à la famille ou à un format reconnu | Fidélité de la réponse |
| Ressources et calcul | Chargement, contexte, buffers et exécution réellement observés | Performance soutenue pendant un film |
| Résultat | Réponse terminée, occurrence et minutage conservés | Absence de contresens, d’omission ou d’ajout |

Changer de profil ne convertit pas une architecture inconnue et ne libère pas la mémoire nécessaire aux poids. L’architecture native seule ne suffit pas à identifier la bonne consigne de traduction.

## Profils disponibles

| Choix | Comportement |
| --- | --- |
| Reconnaître automatiquement le modèle | Privilégie les profils spécialisés reconnus, notamment TranslateGemma, Hy-MT et Index-Translate ; pour les autres modèles, tente le format intégré si le moteur peut le reconnaître et charger les poids |
| TranslateGemma — profil de traduction | Conserve le format spécialisé TranslateGemma, distinct d’une consigne instruct générique |
| Hy-MT / Hy-MT2 — profil de traduction | Conserve les consignes et contrôles propres à cette famille ; une architecture Hunyuan seule n’identifie pas tous ses modèles comme traducteurs |
| Index-Translate — profil de traduction | Utilise la consigne spécialisée de traduction et l’encadrement de conversation prévu pour Index, avec raisonnement désactivé dans ce format |
| Gabarit intégré au GGUF — formats reconnus | Déduit un format de conversation natif connu du gabarit du modèle instruct, puis encadre une demande de traduction dans ce format |

Le mode automatique ne constitue pas une recherche du modèle le plus fidèle et ne choisit pas une autre quantification. Le choix explicite d’une famille doit correspondre au fichier sélectionné. Les anciens réglages restent mémorisés ; vérifier le profil lors d’un changement de modèle.

## Index-Translate

Les références du producteur sont le [modèle Index-Translate-9B](https://huggingface.co/IndexTeam/Index-Translate-9B) et sa [conversion officielle GGUF](https://huggingface.co/IndexTeam/Index-Translate-9B-GGUF). Son format de demande, sa famille native et les identités de fichiers reconnues sont traités séparément des profils TranslateGemma et Hy-MT.

Le profil spécialisé vise une sortie de traduction, avec encadrement de conversation et raisonnement désactivé selon le protocole Index. Il n’ajoute pas un moteur de raisonnement ou un second modèle. Une consigne demandant une traduction seule ne garantit cependant pas que les poids ne produisent jamais d’explication ou de contresens.

L’encadrement spécialisé a été comparé au rendu réellement exécuté du gabarit officiel dans un outil de référence hôte, avec correspondance exacte. Ce contrôle de format ne charge pas les poids Index et ne mesure pas leur qualité ; ses [preuves et limites](../TESTING.md) restent séparées de la validation Android.

Une variante Q6 conserve sa taille et ses poids d’origine. Il faut aussi prévoir les états du contexte, les buffers de calcul, Android, le lecteur et la voix. Le mode Automatique conserve sa réserve mémoire et peut choisir le CPU ; le profil de traduction n’oblige pas le GPU. Lire le moteur réellement utilisé dans les mesures. Aucun essai synthétique miniature ne valide le chargement du 9B Q6 sur le OnePlus.

L’intégration concerne le texte. Les projecteurs multimodaux `mmproj` proposés par le producteur ne sont pas chargés par ce parcours ; ils ne rendent pas l’OCR ou la traduction d’images disponibles dans cette application. L’OCR et la traduction restent indépendants.

## Ce que signifie le gabarit intégré

Le GGUF peut contenir un gabarit de conversation dans ses métadonnées. La 0.5.11 transmet ce gabarit à **`llama_chat_apply_template`** pour reconnaître un des formats pris en charge par le moteur, puis utilise son renderer natif avec **un message utilisateur** et un début de réponse assistant.

**La logique Jinja arbitraire du fichier n’est pas exécutée.** Le parcours n’interprète pas les extensions, filtres ou conditions personnalisés de tout gabarit possible. Un format connu peut convenir à plusieurs modèles instruct — notamment des formats courants utilisés par des familles ChatML, Llama, Gemma ou Mistral — mais cela ne garantit pas toutes leurs variantes ni leurs instructions particulières.

Un nouveau modèle réutilisant une architecture et un format déjà pris en charge peut ainsi être essayé sans créer nécessairement un nouveau profil dans l’application. Une nouvelle architecture, un format inconnu ou un protocole de traduction particulier peut encore nécessiter une adaptation et une nouvelle version.

Le renderer natif borne le gabarit à **64 Kio**, le message utilisateur à **128 Kio** et le rendu à **256 Kio**. Le transport de production Android conserve sa propre limite de **64 Kio** pour le message utilisateur et le calcul reste soumis au contexte du modèle ; ces plafonds ne constituent pas une promesse d’accepter une entrée de 128 Kio dans le parcours de traduction. Une absence de format exploitable, un gabarit inconnu ou une erreur de rendu produit une **erreur de gabarit perceptible**. Ce refus n’est pas transformé en envoi opaque du texte brut. Le gabarit n’ouvre ni fichier ni connexion réseau. Le parcours protège également les marqueurs spéciaux propres au vocabulaire chargé, de types `CONTROL`, `USER_DEFINED` et `UNKNOWN`, même lorsque leur orthographe est personnalisée ; un contenu refusé reste une erreur perceptible et n’est pas silencieusement effacé.

Les options propres à certains renderers Jinja externes ne sont pas automatiquement reproduites par cette API. En particulier, ne pas attribuer au profil générique la désactivation du raisonnement spécifique du profil Index. Le comportement d’un modèle instruct général reste à évaluer avant son usage pour un film.

## Contexte, caches et résultats conservés

Les stratégies publiées en 0.5.10 restent disponibles : deux échanges récents par défaut, occurrence isolée et blocs de six. Pour le parcours générique, la demande de traduction et son contexte sont transmis dans le message utilisateur ; cela n’annonce pas une conversation native multi-tours identique aux formats spécialisés.

Le texte courant reste intégral. Les limites de 2 048 tokens de contexte et de 512 tokens de génération sont conservées ; une sortie arrêtée par sa limite n’est pas une occurrence complète. Les subdivisions éventuelles doivent conserver tous les fragments. Le renderer de conversation et le contrôle de longueur sont des étapes distinctes : un format reconnu peut encore dépasser le budget d’une requête.

Les profils spécialisés existants conservent leurs identités et leurs résultats. Les réglages d’un nouveau profil distinguent ses nouvelles traductions. Le cache de requêtes exactes inclut la configuration, les langues, le texte et l’historique effectif ; il ne justifie pas de supprimer les occurrences identiques. La reprise durable, les budgets de cache et l’export restent décrits dans le [guide 0.5.10](TRADUCTION_QUALITE_0510.md).

## Parcours sur le téléphone

1. Installer la publication vérifiée par-dessus l’édition Traduction actuelle, sans désinstaller.
2. Dans le parcours vidéo ou SRT, vérifier le modèle et le profil. Si le fichier est déjà dans le diagnostic, **Préparer le modèle GGUF déjà importé dans le diagnostic** permet de préparer sa description et son empreinte pour la traduction.
3. Pour Index-Translate, essayer d’abord **Reconnaître automatiquement le modèle**, ou sélectionner explicitement **Index-Translate — profil de traduction** si nécessaire. Ne pas forcer TranslateGemma ou Hy-MT sur ce fichier.
4. Pour un autre modèle instruct, laisser Automatique ou choisir **Gabarit intégré au GGUF — formats reconnus**. Une absence de gabarit ou un format inconnu doit rester visible ; un fichier incomplet, une architecture refusée ou un manque de mémoire demande un diagnostic différent.
5. Commencer par le court exemple synthétique ou le [corpus de fidélité](CORPUS_FIDELITE_0510.md), puis vérifier sources, traductions, moteur observé et messages. Utiliser l’export TXT intégral du comparatif si le rapport dépasse la capacité du presse-papiers.
6. Évaluer ensuite un extrait inédit avec lecture et voix. Conserver chaque occurrence et vérifier Pause, déplacement et reprise ; une réussite dans le diagnostic en pause ne valide pas cet usage simultané.

Les langues doivent être acceptées par le parcours de l’application et par le modèle choisi. La couverture annoncée par le producteur d’un modèle ne devient pas automatiquement celle de cette intégration. Aucun meilleur modèle général, qualité parfaite, gain de vitesse, capacité RAM ou fonctionnement GPU n’est déduit de la reconnaissance du profil. **Le NPU et l’OCR de film ne sont pas intégrés.**
