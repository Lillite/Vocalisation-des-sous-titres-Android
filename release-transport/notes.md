# Sous-titres en voix 0.5.10 — Qualité et fiabilité de la traduction locale (préversion)

[Accueil](https://github.com/Lillite/Vocalisation-des-sous-titres-Android#readme) · [Guide qualité et fiabilité](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/docs/TRADUCTION_QUALITE_0510.md) · [Validation](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/TESTING.md)

Cette préversion apporte les stratégies de contexte et les améliorations de fiabilité de la traduction locale. Elle conserve le modèle choisi, ses poids et sa précision de calcul. La **0.5.2 reste la stable recommandée**.

## Identification et distribution

| Élément | Référence |
| --- | --- |
| Version | **0.5.10-quality**, versionCode **17** |
| Statut | Préversion vérifiée, sans remplacer la dernière stable |
| APK signé | SousTitresVoix-0.5.10-quality.apk |
| Identité Android | fr.soustitresvoix.player.translation, conservée |
| Android minimum | Android 8.0 — API 26 |
| Architectures natives | ARM 64 bits ; x86_64 pour émulateur |
| Signature vérifiée | Certificat durable de l’édition Traduction, SHA-256 304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d |

L’APK a été signé avec le certificat durable après validation de la proposition et reconstruction vérifiée de ses sources fusionnées. **Installer par-dessus l’édition Traduction actuelle, sans désinstaller.** Les clés et mots de passe restent privés et hors du dépôt.

## Changements ajoutés

- **Stratégies explicites** : deux échanges récents par défaut, occurrence isolée ou blocs de six occurrences. Le réglage n’est pas présenté comme un classement de qualité ; le texte courant reste intégral.
- **Comparaison sur GPU** : les trois politiques traduisent les mêmes 32 occurrences de référence ou un corpus inédit de 18 occurrences avec sessions successives et libération confirmée, sans imposer une nouvelle série CPU. Les critères de relecture restent séparés des textes transmis au modèle.
- **Rapports complets sur téléphone** : export TXT intégral du comparatif après sa fin ou son arrêt, via le sélecteur Android ; une copie trop volumineuse est refusée explicitement plutôt que tronquée.
- **Identité v4** : stratégie de contexte et exécution demandée incluses dans la configuration ; anciens résultats conservés sous leur identité d’origine.
- **Reprise incrémentale** : ajouts durables des résultats validés, contrôle des enregistrements et prise en charge des journaux historiques, sans réécriture de tout le préfixe à chaque occurrence.
- **Cache de requêtes exactes** : mémoire locale GGUF additionnelle de 8 Mio par parcours, avec clé incluant l’historique complet, réponses revalidées et occurrences toujours distinctes. Les mesures indiquent une réutilisation sans inventer de nouveau calcul GPU.
- **Alertes numériques** : normalisation prudente des formes anglaises et françaises reconnues, heures et séparateurs ; consultation dans les parcours SRT et vidéo, sans correction automatique du texte.
- **Calcul équivalent** : réutilisation du buffer de sélection native, avec le même greedy et sans changement de poids, précision, noyaux, contexte de 2 048 tokens ou borne de sortie de 512 tokens.
- **Suivi et export** : statut SRT plus court, détails techniques consultables, copies inutiles évitées en mode complet et préparation de l’export vidéo hors interface.
- **Conservation** : chaque occurrence, dialogue, panneau, répétition réelle, chevauchement et minutage reste distinct ; les protections de Pause, déplacement, annulation, réponse périmée et libération demeurent requises.

Le [guide](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/docs/TRADUCTION_QUALITE_0510.md) précise les réglages, les différences entre les caches et les limites de l’évaluation.

## Limites

Ces améliorations ne garantissent ni fidélité parfaite ni accélération de chaque occurrence. Une réponse terminée et conservée valide une structure et un résultat disponible ; elle ne certifie pas le sens de la traduction. Les exemples connus ne sont pas corrigés en dur.

Le contexte récent peut glisser et limiter la réutilisation du préfixe natif. Les blocs suivent l’ordre des occurrences et ne détectent ni scènes ni locuteurs. Une paire historique trop longue peut être retirée entière pour respecter la capacité du modèle. Le mode isolé retire les autres occurrences ; en cas de subdivision d’un sous-titre long, ses propres fragments peuvent encore se fournir un contexte interne. Le meilleur réglage doit être établi sur des textes nouveaux et des dialogues cohérents, pas déduit du débit seul. Le corpus inédit de 18 phrases n’est pas une conversation continue et ne suffit pas à lui seul à choisir une stratégie générale.

Les alertes de nombres restent limitées aux formes reconnues et aux langues connues ; les cas ambigus peuvent nécessiter une relecture. Les pronoms, négations et conditions ne sont pas certifiés. Les améliorations de journal visent surtout les films longs et ne sont pas mesurées par le comparatif synthétique direct. Chaque parcours vidéo ou SRT possède son cache de pistes de 32 Mio, son cache de requêtes additionnel de 8 Mio et des journaux distincts de 32 Mio maximum par plan. Les caches restent évictables ; les résultats conservés durablement sont distincts.

Le modèle est chargé une fois par tâche et n’est pas conservé indéfiniment entre tâches indépendantes. Automatique conserve sa réserve mémoire. Les mesures ponctuelles de RAM, du lecteur et de température de batterie ne prouvent ni fluidité continue, ni vocalisation complète, ni température CPU/GPU. L’essai pendant une lecture réelle avec voix reste distinct du diagnostic en pause.

**Le NPU et l’OCR de film ne sont pas intégrés.** Aucun modèle n’est téléchargé automatiquement. L’OCR et la traduction restent indépendants. La signature durable et l’identité Android restent conservées.

## Validation et provenance

Les sources ont été validées avant fusion dans le [run 40905629491](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40905629491). Le [run après fusion 40910473051](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/actions/runs/40910473051) a reconstruit la release sans rejouer les suites et confirmé son contenu applicatif identique à l’APK testé. La signature durable, l’identité Android et les alignements ont ensuite été vérifiés.

| Preuve | Valeur |
| --- | --- |
| Source fusionnée | 911888461e046418fe029b74d09b22fc62af687f |
| APK signé | SousTitresVoix-0.5.10-quality.apk |
| SHA-256 APK | 0ed4e99d10ef1505b5715d458c2eb4cc299fd56c6c8139ac4319977f3ca7b392 |
| Certificat durable SHA-256 | 304a8f1ffe07b3e51ea6fecd5d507e33ed4785619503f03fdb7d1c7f81caad2d |

Les essais automatisés utilisent des textes et modèles miniatures synthétiques. Leur réussite ne mesure ni qualité linguistique de production, performances prolongées, chauffe, autonomie ou TalkBack réel. L’instrumentation sur émulateur et les essais de pilote synthétique ne prouvent pas un calcul GPU sur le OnePlus. Les effectifs appartiennent au run exact et ne sont pas remplacés par ceux d’une version antérieure. Le NPU et l’OCR de film restent hors du périmètre exécuté.

**Le travail propre à ce projet est réalisé intégralement par IA à partir des demandes et retours de l’utilisateur.** Android, Kotlin, Media3, llama.cpp, KleidiAI, OpenCL et les modèles restent attribués à leurs auteurs respectifs. Aucun poids de production ni secret n’est redistribué avec cette préversion.
