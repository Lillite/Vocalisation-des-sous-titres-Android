# Traduction locale — qualité et fiabilité 0.5.10

[Accueil](../README.md) · [Documentation](README.md) · [Comparatif CPU/GPU](COMPARATIF_LOCAL_058.md) · [Notes 0.5.10](releases/0.5.10-quality.md) · [Corpus de fidélité](CORPUS_FIDELITE_0510.md) · [Validation](../TESTING.md)

La candidate **0.5.10-quality / code 17** donne la priorité à la fidélité et à la conservation des résultats. Elle expose plusieurs politiques de contexte, améliore la reprise et les contrôles de nombres, et retire des travaux répétitifs qui n’apportaient pas de qualité. Elle conserve le modèle choisi, ses poids, sa précision, les noyaux de calcul, le contexte natif de 2 048 tokens et la borne de génération de 512 tokens.

Une occurrence techniquement terminée n’est pas une traduction dont le sens a été certifié. Les exemples connus ne sont pas corrigés en dur. Le meilleur profil doit être établi sur des textes nouveaux et dans l’usage réel ; la version ne choisit pas automatiquement le profil le plus rapide ni un modèle réputé « meilleur ».

## Choisir une stratégie de contexte

Le réglage est distinct de la famille du modèle et du choix CPU/GPU. Le même texte peut être traduit différemment lorsque son contexte change.

| Stratégie | Historique fourni | Intérêt et limite |
| --- | --- | --- |
| Deux échanges récents — défaut conservé | Au plus deux paires source/traduction terminées | Continuité locale ; la disparition de la plus ancienne paire modifie le début du prompt et peut limiter la réutilisation du cache |
| Occurrence isolée | Aucune occurrence précédente | Référence sans historique ; moins de risque de contamination par une ancienne erreur, mais moins de contexte pour les ellipses et pronoms |
| Blocs de six occurrences | Les échanges terminés du même bloc, au plus cinq avant la sixième occurrence | Préfixe pouvant croître dans le bloc ; remise à zéro explicite au bloc suivant ; davantage de contexte ne garantit pas une meilleure traduction |

Les blocs suivent l’ordre des occurrences, pas une détection de scènes ou de locuteurs. Les occurrences 1 à 6 constituent le premier bloc, 7 à 12 le deuxième, etc. Une frontière de bloc ne supprime aucune occurrence. Après reprise, le contexte est reconstruit à partir des résultats validés du même bloc.

Le mode isolé retire l’historique des autres occurrences. Si un sous-titre très long doit être subdivisé, le fragment gauche complet et sa traduction peuvent encore servir de contexte au fragment droit : ils appartiennent à la même occurrence. Il ne faut donc pas décrire toutes les requêtes fragmentées de ce mode comme une demande strictement sans aucun historique. Les courtes phrases du comparatif n’exercent pas ce découpage.

Le sous-titre courant est toujours conservé intégralement. En cas de dépassement de capacité, les paires historiques sont retirées entières avant une éventuelle subdivision du texte courant ; les fragments conservés doivent tous obtenir une réponse complète. La stratégie de bloc reste donc soumise au budget du modèle et ne promet pas de garder tout l’historique d’un bloc composé de textes longs.

Le [format officiel TranslateGemma](https://huggingface.co/google/translategemma-4b-it) reste la référence. Le contexte personnel facultatif demeure une adaptation manuelle à évaluer. Les politiques sont également explicites avec Hy-MT, dont le rendu de consigne est distinct. Aucune extension arbitraire du contexte du modèle ni modification de sa quantification n’est utilisée pour accélérer ces essais.

## Comparer sur le GPU du téléphone

Le diagnostic propose **Comparer les trois stratégies de contexte sur GPU — 32 sous-titres**, sur les mêmes occurrences synthétiques de référence, et **Comparer les stratégies sur des phrases inédites — GPU**, sur le [corpus de fidélité de 18 occurrences](CORPUS_FIDELITE_0510.md). Les identifiants, minutages, panneaux, répétitions et chevauchements sont conservés. Les trois sessions utilisent le même modèle et les mêmes paramètres de génération, dans l’ordre fixe deux échanges récents, occurrence isolée, blocs de six. Elles imposent GPU OpenCL strict et une libération entre les sessions ; les poids ne sont jamais chargés simultanément. Les options d’ordre CPU/GPU et de réouverture du comparatif initial ne s’appliquent pas à ces essais de stratégies.

Les textes traduits peuvent différer et modifier les contextes suivants. Comparer les nombres de tokens et les temps sans relire les sources et sorties ne suffit donc pas à classer les profils. L’ordre, le cache système, la mémoire, la chauffe et les autres applications peuvent influencer les mesures. Le rapport n’attribue ni pourcentage de fidélité ni note linguistique automatique. Le corpus inédit et ses critères de relecture sont séparés : les critères ne sont jamais fournis au modèle. Les 18 phrases ne forment pas une conversation continue ; compléter par des dialogues cohérents avant de retenir une stratégie générale.

La comparaison CPU/GPU existante reste disponible. La nouvelle comparaison permet d’examiner le contexte sans imposer de refaire la longue série CPU. Aucun bouton de diagnostic ne lance la vidéo ou la voix.

## Identité des configurations et anciens résultats

Les nouvelles tâches utilisent l’identité **v4**. Elle inclut notamment la stratégie de contexte et l’exécution demandée. Une sortie préparée avec une ancienne identité ne devient pas une nouvelle traduction simplement parce que l’application a été mise à jour.

Les anciens résultats conservés restent accessibles sous leur identité d’origine. Changer de stratégie crée une configuration distincte, avec son propre résultat et sa propre progression. Chaque occurrence garde son identité et son minutage, même lorsque sa phrase est identique à une autre.

## Cache de requêtes locales exactes

La 0.5.10 ajoute une mémoire de résultats GGUF complète au niveau de la requête. Elle est distincte du cache de pistes et du cache natif de tokens. Sa clé comprend l’identité v4 du moteur — donc notamment les réglages et la stratégie —, les langues, le texte courant et l’historique effectif complet, sources et traductions. Un texte identique dans un contexte différent n’est pas réutilisé aveuglément.

La lecture du cache est réservée aux moteurs locaux qui y consentent explicitement et aux tâches sans consommation de quota distant. Une réponse retrouvée est revalidée structurellement et ajoutée au journal de progression ; chaque occurrence reçoit toujours son identité et son minutage propres. La mesure indique une réutilisation de résultat et ne prétend pas qu’un nouveau calcul CPU/GPU a eu lieu.

Chaque parcours — vidéo et SRT indépendant — possède un cache de pistes de **32 Mio** et un cache de requêtes de **8 Mio supplémentaires**. Si les deux parcours sont utilisés, leurs caches de requêtes séparés peuvent donc occuper 16 Mio au total. Ils restent évictables ; leurs textes sont privés et conservés sur le téléphone. Effacer le cache d’un parcours retire les deux catégories, sans retirer les traductions conservées durablement. Les journaux de reprise sont distincts et limités à **32 Mio par plan**.

Une modification du minutage peut permettre de réutiliser une requête linguistique identique ; elle ne supprime jamais une répétition réelle. Le nombre de textes distincts d’un corpus ne permet pas de déduire combien d’appels seront évités : le contexte compte aussi. La perte ou l’éviction de ce cache facultatif ne doit pas être l’unique raison de perdre une traduction dont la conservation durable a été confirmée.

## Reprise durable et travail sur les films longs

Le journal de reprise ajoute les nouveaux résultats validés au lieu de réécrire systématiquement tous les textes précédents. Les enregistrements complets sont contrôlés et synchronisés avant leur publication comme progression sauvegardée. Une fin interrompue ne doit pas devenir une occurrence terminée. La lecture des journaux de l’ancien format reste prise en charge avec migration contrôlée. Si les enregistrements accumulés atteignent le plafond de 32 Mio, une consolidation atomique du préfixe complet permet de supprimer le surcoût des enregistrements, sans éliminer une occurrence validée. Un suffixe interrompu ou endommagé permet de récupérer le préfixe vérifié avec un avertissement ; le reste n’est pas présenté comme terminé. L’état incertain d’un éventuel envoi distant ne devient pas une certitude d’absence de facturation.

Cette optimisation vise surtout les pistes longues. Le comparatif synthétique direct n’utilise pas ce journal ; il ne peut donc pas mesurer ses gains. Les sauvegardes ne sont pas simplement espacées pour présenter un meilleur débit. Une erreur de conservation reste perceptible ; le résultat en mémoire ne doit pas être annoncé comme durable à tort.

Le mode complet évite également de fabriquer des copies de préfixes dont il n’a pas besoin. Les identités, la progression validée, les résultats conservés et l’export restent distincts.

## Alertes de nombres et relecture

La comparaison des nombres tient compte des langues source et cible lorsque celles-ci sont connues. Pour les formes anglaises et françaises reconnues, elle rapproche les nombres en lettres et en chiffres, les séparateurs de milliers et de décimales, les heures, signes et unités compatibles. Une interprétation ambiguë reste signalée ; aucun texte n’est réécrit automatiquement.

Ces alertes sont consultables dans le parcours vidéo et dans le parcours SRT, après calcul hors interface. Le rapport de consultation est borné à 50 indices et 96 000 caractères, avec extraits de 240 points de code ; cette présentation abrégée ne tronque ni la traduction conservée ni son export. Une sélection remplacée invalide la remise d’un ancien rapport. Elles complètent les indices existants sur les noms, la longueur et les textes identiques. Elles ne vérifient pas complètement les pronoms, négations, conditions, omissions ni le sens d’une phrase. Une traduction fluide et sans alerte peut encore contenir un contresens.

## Calcul natif et interface

La sélection native conserve le même greedy déterministe et réutilise son buffer de candidats entre les tokens. Les tests doivent comparer ses choix à l’ancien parcours, y compris les fins de génération et les cas d’égalité. Cela n’active ni nouvelle précision numérique, ni échantillonnage expérimental sur GPU, ni NPU.

Le modèle reste chargé une seule fois par tâche et libéré avec accusé sur le worker. Il n’est pas gardé indéfiniment entre des tâches indépendantes. Le cache des programmes OpenCL compilés, les noyaux Adreno et le CPU KleidiAI étaient déjà présents ; cette version ne les présente pas comme de nouvelles optimisations.

Le suivi du SRT privilégie un statut court et stable ; les mesures techniques restent consultables séparément. Les travaux d’export volumineux du parcours vidéo sont préparés hors du thread d’interface, avec contrôle de la sélection encore active. Le comportement réel avec TalkBack et la vocalisation des sous-titres demande un essai sur téléphone.

## Essais après installation vérifiée

1. Installer par-dessus l’édition Traduction actuelle, sans désinstaller. Garder d’abord le même GGUF et quatre threads pour comparer les politiques sans changer les poids.
2. Dans le diagnostic local, lancer **Comparer les stratégies sur des phrases inédites — GPU**. Copier le rapport complet et comparer les sources aux trois sorties avec les [critères de relecture](CORPUS_FIDELITE_0510.md), notamment les pronoms, nombres, négations, conditions et panneaux. La comparaison des 32 occurrences de référence reste disponible.
3. Essayer un nouveau SRT court avec la stratégie choisie ; le bouton **Charger le SRT de contrôle de fidélité — phrases inédites** charge aussi les 18 sources sans vidéo. Vérifier toutes les occurrences, les répétitions, la reprise et l’export. Un corpus inédit évite de confondre la mémorisation des exemples connus avec une amélioration générale.
4. Pour l’usage simultané, lancer une lecture effective avec voix pendant la traduction progressive. Vérifier Pause, déplacement, changement de piste et annulation, puis contrôler les occurrences vocalisées. Une vidéo en pause ne valide pas cet usage.

Les preuves automatisées, les essais avec modèles miniatures et les mesures synthétiques ne démontrent ni qualité parfaite, accélération garantie, autonomie, chauffe prolongée ou compatibilité de tous les GGUF. **Le NPU et l’OCR de film ne sont pas intégrés.** L’OCR et la traduction restent des opérations indépendantes dans le projet.
