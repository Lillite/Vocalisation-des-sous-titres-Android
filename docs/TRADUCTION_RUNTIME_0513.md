# Mémoire, suivi et reprise du calcul local — 0.5.13

[Accueil](../README.md) · [Documentation](README.md) · [Notes de version](releases/0.5.13-memory.md) · [Validation](../TESTING.md)

La candidate **0.5.13-memory / code 20** prépare des optimisations des allocations natives et du cache exact, un suivi plus précis des générations et un diagnostic conservé pour les préparations SRT interrompues. Le cache d’attention K/V peut être quantifié sur choix expérimental explicite. La **0.5.2 reste la stable recommandée** ; les résultats de cette candidate doivent être établis sur ses propres sources avant distribution.

## Ce qui reste conservé

Le modèle choisi, ses poids et leur quantification ne sont pas remplacés. La capacité native du contexte reste **2 048 tokens** et une génération reste bornée à **512 tokens**. Les stratégies de contexte des sous-titres demeurent des choix distincts : deux échanges précédents, occurrence isolée ou blocs de six.

Chaque occurrence conserve son identité, son texte source complet et ses minutages. Une répétition légitime ou un panneau ne disparaît pas parce qu’une traduction identique peut être réutilisée. Une traduction indisponible provoque une attente explicite ; une Pause volontaire interdit une reprise tardive. La préparation SRT ne commande ni vidéo ni synthèse vocale.

## Réutiliser les buffers sans supprimer du texte

Le moteur conserve ses petits buffers de tokens et de caractères pendant une session et réinitialise son sélecteur greedy avant chaque génération. L’objectif est d’éviter les constructions et agrandissements répétés quand la capacité déjà réservée suffit. Les limites d’entrée, de sortie, d’annulation et de libération restent applicables.

Une invalidation du préfixe efface ses positions logiques plutôt que d’écrire systématiquement des zéros dans tous les buffers physiques du cache. Lors de la fermeture, le contexte est libéré sans effectuer cet effacement juste avant sa destruction. Ces choix exigent une comparaison avec des contextes frais : des données anciennes encore allouées ne doivent jamais devenir un préfixe disponible ou une réponse réutilisable.

Le contrôle conserve les règles de disponibilité du préfixe, notamment après dépassement d’une fenêtre d’attention locale. Un préfixe absent, retiré ou différent est recalculé. Le changement de fenêtre des deux échanges précédents doit être exercé sur plusieurs occurrences successives ; la réussite d’une seule phrase n’établit pas ce comportement.

## Choisir la précision du cache K/V

Dans les réglages de chargement du modèle, la case **Cache d’attention K/V en 8 bits — expérimental ; F16 si désactivé** est décochée par défaut.

| Choix | Comportement et limite |
| --- | --- |
| **F16, défaut conservé** | États K et V en 16 bits ; identité historique de configuration conservée à autres réglages identiques. |
| **Q8_0, choix expérimental** | États K et V en 8 bits ; les poids restent ceux du fichier GGUF, mais la précision des états d’attention change. La configuration possède une identité de cache distincte. |

La compatibilité est contrôlée à l’ouverture. Une configuration non prise en charge doit produire une erreur perceptible, sans prétendre avoir exécuté Q8_0 après un repli opaque vers F16. Les résultats F16 ne deviennent pas des résultats Q8_0 par simple réétiquetage.

Le cache V quantifié exige la politique **Flash Attention activée**. F16 conserve la politique **Automatique** du moteur épinglé. Le rapport distingue les types K/V et la politique demandée : **cette politique n’est pas une mesure de l’exécution effective de Flash Attention**. De même, la présence de Vulkan ou son choix dans un menu ne prouve pas un calcul GPU ; les observations de calcul restent séparées.

La réduction des octets du cache ne correspond pas à la réduction de toute la mémoire du modèle. Les poids, les allocations temporaires, le pilote et le lecteur utilisent aussi la mémoire partagée du téléphone. La compatibilité d’un cache et la fidélité des sorties doivent être comparées séparément avec chaque modèle choisi.

## Lire les dernières étapes et mesures

Le rapport peut suivre les étapes fixes **TOKENIZE**, **CACHE_REUSE**, **PREFILL**, **DECODE** et **COMPLETE**, en plus du chargement. Les événements ne contiennent ni consigne ni texte traduit. Le suivi de génération est borné pour limiter les appels entre le natif et Android.

| Mesure | Interprétation |
| --- | --- |
| PID, étape et temps écoulé | Dernière observation reçue de cette tentative ; l’absence de l’étape suivante ne prouve pas une cause. |
| RSS au début de l’appel et après traitement de l’entrée | Relevés ponctuels du processus ; ils ne couvrent pas nécessairement toutes les allocations GPU ni le pic exact. |
| Raison de réinitialisation du préfixe | Distingue absence de préfixe, changement, fenêtre devenue indisponible, retrait refusé et réutilisation. |
| Réinitialisations et agrandissements cumulés | Compteurs de la session ; ils ne sont pas des durées économisées. |
| Octets des buffers réutilisables | Petits espaces de tokens et caractères suivis par notre wrapper, pas la totalité de llama.cpp, du cache K/V ou du GPU. |

Une étape **COMPLETE** indique la fin d’un appel natif. Une occurrence peut demander plusieurs appels et n’est publiée qu’après validation de sa traduction complète. Une sortie EOS terminée ne certifie pas sa fidélité linguistique. Les débits de sortie restent distingués du chargement et du traitement de l’entrée.

## Retrouver une préparation SRT interrompue

Le parcours **Traduire un fichier SRT hors ligne** conserve un petit journal de diagnostic privé, séparé du journal durable des traductions. Il est borné à **16 Kio**, écrit de façon atomique et ne conserve ni texte de sous-titres, titre, chemin personnel, secret ni description Android libre. Les événements fréquents sont regroupés sur une seconde ; une écriture est forcée après une occurrence validée, avant de passer au calcul suivant.

À la réouverture du même fichier, le rapport peut retrouver la dernière tentative, les compteurs, réglages — y compris le choix F16 ou Q8_0 — et dernières étapes conservées. Une erreur enregistrée reste distincte d’une interruption avant clôture ; les dernières mesures disponibles ne sont pas réétiquetées comme des mesures de l’appel suivant. Une préparation non clôturée est présentée comme interrompue. **Aucun calcul, visionnage, vocalisation ou export complet ne redémarre automatiquement.** Relancer explicitement avec le même fichier, modèle, langue et réglages permet au moteur de reprendre les résultats validés déjà disponibles. Le rapport ne déclare pas complet un préfixe partiel.

Un échec d’écriture du diagnostic ne bloque pas à lui seul la traduction ; un avertissement est ajouté au bilan de la tentative lorsque l’application peut encore le présenter. La conservation des traductions est gérée séparément. Un journal absent, vieux de plus de sept jours, corrompu ou concernant une autre source n’est pas présenté comme une preuve de la tâche courante.

Android peut fournir un motif de disparition du processus du modèle ou de l’application. La corrélation utilise son identité et l’intervalle de la tentative. Le code reconnaît également le marqueur fixe **MemoryLimiter:AnonSwap** lorsqu’il est présent dans la description système, sans enregistrer cette description libre. La [documentation Android 17](https://developer.android.com/about/versions/17/behavior-changes-all#app-memory-limits) distingue ces limites par application du manque de mémoire global. L’absence de motif corrélé conserve une cause indéterminée ; un signal seul ne prouve pas un manque de mémoire.

## Cache exact de requêtes et limites de l’évaluation

Le format des entrées historiques du cache de requêtes reste conservé. La lecture utilise un tableau borné et des vues sur son contenu plutôt qu’une copie intégrale supplémentaire. L’empreinte est calculée en flux à l’écriture. Sous le quota, l’inventaire réel reste vérifié mais le tri et les lectures de dates de tous les fichiers sont évités ; les règles d’éviction, les groupes atomiques et les contrôles de corruption demeurent nécessaires.

Ces changements concernent surtout les petits fichiers du cache et les opérations répétées. Ils ne démontrent pas une réduction de plusieurs gigaoctets du modèle ni la disparition d’un arrêt du pilote.

La validation doit distinguer comparaisons natives synthétiques, tests JVM, instrumentation Android et essais sur téléphone. Aucun gain de temps, mémoire, autonomie, classement linguistique ou stabilité prolongée du 12B n’est déduit de l’existence du code. L’OCR de film, le NPU, les projecteurs multimodaux et tout téléchargement automatique de poids restent hors de cette étape.
