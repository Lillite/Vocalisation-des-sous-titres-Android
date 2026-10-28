# Traduction GGUF locale — candidate 0.5.4

[Accueil](../README.md) · [Notes de version](releases/0.5.4-local.md) · [Validation](../TESTING.md) · [Diagnostic préparatoire](DIAGNOSTIC_LOCAL_053.md)

La candidate **0.5.4-local / code 11** ajoute un moteur GGUF au parcours de traduction. Le texte et le modèle restent sur le téléphone. L’application charge le fichier directement avec llama.cpp sur le **CPU**, sans passer par PocketPal et sans appeler un serveur. Les poids ne sont ni inclus dans l’APK ni téléchargés automatiquement.

Cette fonction traite les **sous-titres textuels**. L’OCR des textes incrustés ou des pistes image reste une opération distincte, non intégrée par cette version. Traduire ne demande aucun OCR préalable lorsque le SRT existe déjà.

## Préparer le modèle

Le fichier `.gguf` doit être accessible au sélecteur de fichiers Android. Un modèle visible dans PocketPal peut se trouver dans son espace privé et ne pas apparaître dans ce sélecteur. Le fichier doit alors être fourni dans un dossier accessible, sans supprimer l’original ni les données de PocketPal.

Dans **Traduire les sous-titres en français**, choisir le moteur GGUF local :

1. Appuyer sur **Choisir un modèle GGUF local pour traduire** et sélectionner le fichier.
2. Attendre la fin de la copie et de son identification. L’import ne démarre pas une traduction.
3. Si le modèle a déjà été copié par le diagnostic, utiliser **Préparer le modèle GGUF déjà importé dans le diagnostic**. Cette préparation permet son identification pour la traduction.
4. Pour interrompre la préparation, utiliser **Annuler l’import ou la préparation du modèle GGUF**. L’original reste conservé.

Une copie privée occupe l’espace correspondant au fichier. L’identité du contenu et les paramètres de traduction distinguent les résultats réutilisables ; remplacer le modèle ne doit pas réutiliser la traduction d’un autre modèle. Une importation incomplète ou une empreinte manquante ne constitue pas un modèle prêt.

Les requêtes sont adaptées aux familles **TranslateGemma** et **Hunyuan dense** prises en charge par cette intégration. Un fichier GGUF n’est pas une garantie de compatibilité : l’architecture, les métadonnées et la mémoire nécessaire doivent être acceptées. Un refus est affiché ; l’application ne remplace pas le moteur par un service en ligne.

## Traduire un SRT sans ouvrir de vidéo

Le parcours **Traduire un fichier SRT hors ligne**, accessible depuis la bibliothèque et les réglages de traduction, permet de choisir un SRT, préparer la traduction et exporter le résultat sans lancer une vidéo ni une vocalisation.

1. Utiliser **Choisir le fichier SRT à traduire hors ligne**, ou **Charger l’exemple SRT anglais intégré** pour essayer les huit occurrences synthétiques sans fichier supplémentaire. Vérifier la langue de ses textes.
2. Choisir le modèle local prêt et les paramètres proposés.
3. Appuyer sur **Traduire le SRT hors ligne / reprendre**, puis confirmer **Traduire localement avec le GGUF**.
4. Consulter la progression. Une annulation ou une erreur conserve les occurrences déjà validées pour une reprise avec la même source, le même modèle et les mêmes paramètres.
5. Une fois la traduction complète, utiliser **Exporter le SRT traduit en SRT** ou **Exporter le SRT traduit en VTT**. Choisir un nom distinct pour conserver la source.

**Fermer le panneau et laisser la traduction continuer** conserve la tâche SRT active. En revanche, la fermeture annule un import ou une identification de modèle encore en cours. Les threads et le contexte local confirmés sont conservés pour retrouver les mêmes paramètres à la reprise. Charger l’exemple ne lance ni traduction, ni lecture, ni vocalisation.

L’export est un fichier texte UTF-8 utilisable avec une vidéo ou un outil de multiplexage MKV compatible. L’application ne remuxe pas la vidéo : elle fournit le fichier de sous-titres.

## Traduire la piste du lecteur

Ouvrir une vidéo et choisir une piste textuelle, ou lui associer le SRT externe. Dans **Traduire les sous-titres en français**, choisir le moteur GGUF local, la langue source explicite et le modèle prêt. Utiliser ensuite l’estimation et la confirmation locale.

La traduction conserve les identifiants, les minutages, les chevauchements et les répétitions réelles de chaque occurrence. Le modèle produit le texte traduit ; il ne décide pas des numéros ou des temps du SRT. Une sortie tronquée, vide, annulée ou en erreur n’est pas activée comme une traduction complète.

Le mode complet attend toute la piste. Le mode progressif permet de regarder avec les premiers résultats validés. Si le lecteur rattrape la préparation, il attend avant l’occurrence manquante. Une reprise automatique demande une attente créée par la traduction et une intention **Lire** toujours valide ; **Pause**, déplacement, changement de média ou fermeture empêchent une reprise tardive. La préparation initiale ne démarre jamais la lecture.

L’affichage original, français, bilingue ou masqué et le choix vocal restent indépendants. Une piste complète peut être corrigée, conservée et exportée par les commandes existantes. Les résultats prêts restent utilisables sans le modèle pour leur seule consultation ou export ; une nouvelle traduction demande le modèle compatible.

## Contexte, mémoire et vitesse

Le réglage **GGUF local : threads de calcul — 1 à 8, défaut 4** règle le nombre demandé au CPU. Un nombre plus grand ne garantit pas une génération plus rapide et doit laisser des ressources au lecteur et à la voix.

Le champ **GGUF local : contexte de l’œuvre ou de la scène, facultatif — reste sur le téléphone** permet d’ajouter une indication courte. Le moteur utilise aussi un contexte récent borné pour guider la cohérence entre répliques. Il ne charge pas tout le film dans une seule requête. La segmentation et les limites de génération restent explicites : aucun morceau non validé n’est présenté comme terminé.

Cette étape emploie un contexte de calcul borné à **2 048 tokens**. La RAM totale d’exécution comprend les poids, le contexte et les tampons ; la taille du GGUF sur disque ne suffit pas à établir qu’il tiendra en mémoire. La RAM disponible dans le diagnostic est un relevé ponctuel distinct.

Rester dans l’application pendant la préparation. Android peut arrêter un processus ; la reprise s’appuie alors sur les occurrences validées et sauvegardées. Le traitement continu avec l’écran éteint ou après fermeture de l’application n’est pas garanti par cette étape. Les générations interrompues ne sont pas supposées achevées.

Le CPU est le seul backend exécuté ici. **GPU et NPU ne sont pas activés.** Le choix du meilleur modèle, sa quantification, les performances prolongées, la chauffe et l’autonomie restent à évaluer sur le téléphone, avec les vrais usages. Un court essai synthétique réussi ne mesure pas la fidélité d’une traduction.

## Confidentialité et validation

Le choix du moteur GGUF ne demande ni clé API, ni compte, ni envoi du SRT. Les moteurs en ligne restent des choix séparés et conservent leurs confirmations propres. Le nom d’un fichier, son chemin privé, son contenu et les sous-titres ne doivent pas être imprimés dans les journaux de diagnostic.

Les régressions utilisent des SRT et des modèles miniatures synthétiques. Une vraie exécution JNI vérifie le chargement et les générations ; elle ne démontre pas la qualité linguistique d’un modèle de production. Les résultats de compilation, JVM et instrumentation Android sont attribués au commit exact dans [TESTING.md](../TESTING.md). TalkBack réel, la qualité de la voix et les traductions longues restent des vérifications séparées sur téléphone.

llama.cpp reste attribué à ses auteurs et utilise la révision épinglée dans les sources. Les modèles de production restent soumis à leurs propres conditions ; aucune redistribution de leurs poids n’est ajoutée à l’application.
