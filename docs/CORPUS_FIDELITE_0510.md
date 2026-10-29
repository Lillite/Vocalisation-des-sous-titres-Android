# Corpus de contrôle de fidélité — 0.5.10

[Accueil documentaire](README.md) · [Guide qualité](TRADUCTION_QUALITE_0510.md) · [SRT anglais](../examples/qualite-traduction-en.srt)

Ces 18 occurrences anglaises synthétiques complètent les 32 occurrences du comparatif historique, qui restent inchangées. La dernière reprend volontairement le texte de la quatrième, à un autre moment ; les deux doivent être conservées. Un chevauchement et un panneau de deux secondes font partie du fichier. Aucun média personnel n'est utilisé.

Sur le téléphone, le parcours SRT permet de charger ce corpus intégré. Le comparatif des stratégies de contexte sur les phrases inédites utilise ces mêmes sources, sur GPU OpenCL strict. Les critères ci-dessous ne sont jamais transmis au modèle. Ils ne constituent ni une traduction imposée mot pour mot ni un classement automatique : une autre formulation conservant le sens peut être préférable.

| Occurrence | Information à conserver lors de la relecture |
| --- | --- |
| 1 | Nora a prêté la carte à Sami ; Sami ne la lui a pas rendue. Ne pas inverser le prêteur et le destinataire. |
| 2 | Voyant orange allumé en continu : s'arrêter. Voyant clignotant : attendre. Les deux états sont distincts. |
| 3 | Rendez-vous déplacé de 6 h 45 à 16 h 05 ; préserver l'origine, la destination et les minutes. |
| 4 | Quatorze copies demandées, et non quarante. |
| 5 | Aucune des deux bouteilles ne contient d'alcool. |
| 6 | Elle a très peu parlé après l'annonce ; cela ne signifie pas qu'elle n'a jamais parlé. |
| 7 | La lettre est placée à l'intérieur de l'enveloppe vide. Préserver les deux objets et leur relation. |
| 8 | Colis à côté de la porte, et non derrière. |
| 9 | Frais de 1 250,50 euros ; dépôt de 80 euros remboursable. Préserver les valeurs, la monnaie et le caractère remboursable du dépôt. |
| 10 | Seul le technicien est autorisé à redémarrer la machine. |
| 11 | Arrivée vendredi seulement possible ; aucune promesse de venir. |
| 12 | Le locuteur demande qu'on lui rende son passeport avant le départ de son interlocuteur. |
| 13 | Fermeture jusqu'à 11 h 20 ; livraisons à l'arrière. Les deux informations du panneau sont nécessaires. |
| 14 | Lina n'a pas appelé le second locuteur : c'est lui qui l'a appelée. Conserver les deux tours de dialogue. |
| 15 | Première occurrence de « bank » : rive du cours d'eau. Seconde : établissement qui prête de l'argent. |
| 16 | Ouverture du musée tous les jours à l'exception du mercredi. |
| 17 | Interdiction d'ouvrir la boîte en l'absence du locuteur. La phrase n'impose pas de l'ouvrir lorsqu'il est présent. |
| 18 | Répétition légitime : conserver à nouveau quatorze copies et la négation de quarante, à son propre minutage. |

Les choix de tutoiement, de registre ou de ponctuation doivent être évalués selon la scène. Ces exemples variés ne forment pas une conversation continue : un historique peut donc aider certaines formulations et en perturber d'autres. Il faut compléter cette série par des échanges cohérents et des extraits inédits avant de retenir une stratégie générale.

Une génération terminée, un nombre d'occurrences exact et un export complet ne prouvent pas la fidélité linguistique. Noter séparément contresens, omissions, ajouts, nombres, personnes, conditions, français et performance. Aucun pourcentage de qualité n'est déduit automatiquement de ces seuls exemples.
