# Saisie précise et traitements audio avancés — étape 6

> **Référence historique : cette étape a été intégrée à `main` et à la [version 0.4.0 — édition Traduction](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/tag/v0.4.0).** Les mentions ci-dessous de travail séparé, de livraison future, d’ancienne version ou d’effectifs intermédiaires décrivent le développement de cette étape. La branche de travail est conservée par son [tag d’archive](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/tree/archive/20261024/work/etape-6-audio-expert-20261023). Ces fonctions sont conservées en 0.5.1. Pour installer et vérifier la version actuelle : [accueil](../README.md), [notes 0.5.1](releases/0.5.1.md) et [TESTING.md](../TESTING.md).

[Index](README.md) · [Commandes audio précédentes](COMMANDES_AUDIO_ETAPE_5.md) · [Architecture](ARCHITECTURE.md) · [Validation](../TESTING.md)

## Périmètre, état et point de retour

Cette étape ajoute une saisie numérique précise aux réglages du film et un
processeur audio optionnel : préampli, égaliseur paramétrique à cinq bandes,
balance, mélange mono et limiteur de crêtes numériques. Media3 reste le moteur
de lecture ; la voix narrée garde son parcours séparé.

Le point de retour est l’étape 5, commit
[`647de93e99ef778cfa3199f3c225e1fc0b759baf`](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/commit/647de93e99ef778cfa3199f3c225e1fc0b759baf),
conservé sur `backup/etape-5-validee-20261023-647de93`. Le travail de l’étape 6
se trouve sur `work/etape-6-audio-expert-20261023`. Il ne constitue pas une
nouvelle publication de l’application ; la livraison reste réservée à une
instruction ultérieure.

Ce guide décrit le comportement prévu et implémenté. Les résultats de tests
doivent être rattachés à leurs sources exactes dans la demande de fusion et
dans [TESTING.md](../TESTING.md). Un résultat de l’étape 5 ne valide pas à lui
seul les nouveaux traitements.

## Accéder aux réglages et saisir une valeur

Ouvrir **Volumes rapides**. Les deux volumes du film et les trois durées ont
maintenant un bouton **Saisir** en plus des boutons de pas et des retours au
défaut. Le bouton **Traitements audio avancés du film**, placé au début de ce
panneau, ouvre les nouveaux réglages. Chaque bande d’égalisation possède son
propre sous-panneau.

Une saisie affiche son unité, ses bornes, sa valeur actuelle et une description.
Les pourcentages, les décibels, les fréquences et Q acceptent un point ou une
virgule décimale. Les durées demandent un nombre entier de millisecondes.

| Saisie | Résultat attendu |
|---|---|
| `37,5` ou `37.5` dans un volume | Demande de 37,5 %, si la validation du panneau reste valable |
| `275` dans la durée de baisse | Demande de 275 ms, indépendante du pas rapide de 40 ms |
| `−3,25` dans le préampli | Demande de −3,25 dB ; le signe moins typographique est accepté |
| `100,0000000001` dans un volume | Refus : la valeur décimale dépasse 100 %, avant conversion en nombre binaire |
| `275,0` dans une durée | Refus : la saisie des millisecondes doit être entière, sans séparateur décimal |
| `1e3`, `1 000`, `20 Hz`, `NaN` | Refus : notation exponentielle, séparateur de milliers, unité ajoutée et valeur non finie ne font pas partie de la grammaire |

La saisie est limitée à 24 caractères après retrait des espaces extérieurs.
**Appliquer** relit les paramètres compagnons du groupe, puis ne remplace que
le champ modifié. **Annuler** conserve tous les réglages. En cas d’erreur, le
dialogue conserve le texte saisi et montre le motif ; aucune valeur partielle
n’est enregistrée. Le texte peut donc être corrigé sans devoir être retapé.

La comparaison des bornes utilise des décimaux exacts avant la conversion vers
le type du lecteur : `Float` pour les deux volumes historiques, `Double` pour
les nouveaux paramètres continus. La précision de stockage finale reste celle
de ces types ; une saisie longue n’impose pas une précision acoustique équivalente.
L’interface réaffiche la valeur retenue.

Les anciens pas de volume conservent leur règle : arrondir le pourcentage à
l’entier le plus proche, puis ajouter ou retirer cinq points. Ainsi, après
une saisie de 37,5 %, **+5 points** donne 43 %. Pour conserver une fraction,
utiliser **Saisir**. Les pas des durées restent 40, 100 et 50 ms.

## Réglages historiques conservés

| Réglage | Plage | Défaut | Application |
|---|---|---|---|
| Volume normal du film | 0 à 100 % | 100 % | Nouvelle cible immédiate, calculée selon l’état de la narration ; remplace le fondu en cours |
| Son du film pendant la voix | 0 à 100 % du volume normal | 25 % | Multiplicateur : 60 % × 25 % donne une cible de 15 % |
| Durée de baisse | 0 à 2 000 ms, entier | 160 ms | Prochaine transition planifiée vers le niveau réduit |
| Durée de remontée | 0 à 2 000 ms, entier | 500 ms | Prochaine transition planifiée après l’attente |
| Attente avant remontée | 0 à 2 000 ms, entier | 350 ms | Minimum ; l’anticipation d’une réplique proche peut le prolonger, même à zéro |
| Atténuation spectrale | Activée ou désactivée | Désactivée | Processeur existant autour de 250 à 3 200 Hz, pendant le signal de parole |

Les règles détaillées des fondus, de l’anticipation et du signal de parole
restent celles du [guide de l’étape 5](COMMANDES_AUDIO_ETAPE_5.md). Le volume de
la voix, son débit et le volume système sont distincts. Le panneau général
**Voix et confort** conserve sa pause historique ; **Volumes rapides** ne
déclenche pas cette mise en pause.

## Ordre et unités des traitements avancés

L’audio du film traverse d’abord le `SpeechBandProcessor` existant, puis le
nouvel `ExpertAudioProcessor`. À l’intérieur du nouveau processeur, l’ordre est :

1. préampli ;
2. bandes paramétriques 1 à 5 ;
3. mélange mono éventuel ;
4. balance ;
5. limiteur commun aux canaux ;
6. quantification et, si nécessaire, saturation finale en PCM16.

Cette étape ne modifie pas le signal `speaking` ni le processeur spectral
historique. Les bandes de l’égaliseur agissent sur tout le film lorsque leur
activation est demandée ; leur activation ne dépend pas de la présence d’une
phrase narrée. Si l’atténuation spectrale historique est aussi active, ses
effets précèdent ceux de l’égaliseur.

Les **dB de gain** décrivent un rapport d’amplitude : +6 dB correspond à environ
×1,995 et −6 dB à environ ×0,501. Les **dBFS** situent une amplitude numérique
par rapport à la pleine échelle. Aucun de ces nombres ne mesure les décibels
acoustiques à l’oreille. Le mélange, les traitements ultérieurs du lecteur,
Android et la sortie matérielle restent distincts.

## Commandes générales avancées

| Paramètre | Plage et défaut | Effet, modification et remise au défaut |
|---|---|---|
| **Activation générale** | Désactivée par défaut | Cocher autorise la chaîne avancée sur un flux compatible. Décocher revient progressivement au signal reçu sans ces traitements, tout en conservant les valeurs préparées. |
| **Préampli du film** | −24 à +12 dB ; défaut 0 dB | Saisie décimale avant les bandes. Une valeur négative réserve de la marge pour les gains positifs de l’égaliseur. Rétablir remet seulement le préampli à 0 dB. |
| **Balance stéréo** | −100 à +100 % ; défaut 0 % | Une valeur négative atténue seulement la droite ; une valeur positive atténue seulement la gauche. Les extrêmes coupent le côté opposé. Le côté favorisé n’est jamais amplifié. Rétablir revient au centre. Sans effet sur une entrée mono. |
| **Mono** | Désactivé par défaut | Cocher remplace les deux canaux stéréo par `(gauche + droite) / 2`, après les bandes et avant la balance. Décocher retrouve le parcours stéréo. Sans effet sur une entrée déjà mono. |
| **Limiteur** | Présélectionné ; inactif tant que l’activation générale est désactivée | Réduit ensemble les canaux lorsqu’une crête dépasse le plafond. Décocher relâche le gain résiduel sur 20 ms ; une amplification peut alors produire un écrêtage. |
| **Plafond du limiteur** | −12 à 0 dBFS ; défaut −1 dBFS | Saisie décimale. Un plafond plus bas entraîne une réduction plus tôt. Rétablir remet seulement le plafond à −1 dBFS. |
| **Relâchement du limiteur** | 10 à 2 000 ms, entier ; défaut 100 ms | Saisie d’une constante de temps de récupération exponentielle. Rétablir remet seulement cette constante à 100 ms. Ce n’est pas le réglage de remontée du film après la voix. |

Le mono répartit un son initialement présent d’un seul côté vers les deux
canaux, avec la division par deux de la moyenne. Des composantes en opposition
de phase peuvent s’annuler. Une balance non centrée est ensuite appliquée au
résultat mono : les deux sorties peuvent donc avoir des niveaux différents.

## Les cinq bandes paramétriques

Chaque bande utilise un filtre en cloche (*peaking EQ*) dont la fréquence,
le gain et Q sont réglables indépendamment. Les cinq bandes sont initialement
désactivées.

| Bande | Fréquence initiale | Gain initial | Q initial |
|---|---:|---:|---:|
| 1 | 60 Hz | 0 dB | 1 |
| 2 | 250 Hz | 0 dB | 1 |
| 3 | 1 000 Hz | 0 dB | 1 |
| 4 | 4 000 Hz | 0 dB | 1 |
| 5 | 12 000 Hz | 0 dB | 1 |

| Paramètre d’une bande | Plage | Interprétation et action |
|---|---|---|
| **Activation** | Activée ou désactivée | Cocher sélectionne cette bande. Le traitement général doit aussi être actif ; un gain nul reste neutre. Décocher conserve sa fréquence, son gain et Q. |
| **Fréquence centrale** | 20 à 20 000 Hz, décimale | Centre de l’amplification ou de l’atténuation. Saisir une fréquence puis appliquer. Rétablir retrouve la fréquence initiale de cette bande. |
| **Gain** | −12 à +12 dB, décimal | Valeur positive : amplification autour du centre ; valeur négative : atténuation. Rétablir remet seulement le gain à 0 dB. |
| **Facteur Q** | 0,1 à 10, décimal, sans unité | Une valeur plus élevée rend la correction plus étroite ; une valeur plus faible l’élargit. Rétablir remet Q à 1. |

Le bouton de retour au défaut d’une **bande entière** remet son activation à
désactivée, sa fréquence à la valeur du tableau, son gain à 0 dB et Q à 1.
Les quatre autres bandes et les commandes générales sont conservées.

Les coefficients suivent les formules de filtre en cloche du
[W3C Audio EQ Cookbook, note du 8 juin 2021](https://www.w3.org/TR/audio-eq-cookbook/).
Q suit la convention de ce filtre ; la largeur numérique dépend aussi du gain
et de la fréquence d’échantillonnage. Le traitement est un filtre récursif avec
une réponse de phase : aucune option de phase linéaire n’est annoncée.

Les corrections se cumulent. Cinq gains positifs portant sur la même zone ne
sont pas cinq réglages isolés de volume : leur combinaison peut demander une
réduction importante au limiteur. Abaisser le préampli réserve de la marge ;
désactiver le limiteur rend possible la saturation PCM16. L’égaliseur ne
récupère pas une distorsion déjà présente dans le fichier ou produite en amont
et ne sépare pas les dialogues, la musique et les effets.

### Bandes inapplicables à un format

Une fréquence doit rester **strictement inférieure à la moitié de la fréquence
d’échantillonnage** du flux. Par exemple, à 8 000 Hz d’échantillonnage, une bande
demandée à 4 000 Hz ou au-dessus ne peut pas être appliquée. Le processeur
conserve la demande et signale l’omission de la bande ; il ne déplace pas
silencieusement sa fréquence. Il omet aussi une bande dont les coefficients,
après calcul, échoueraient à la vérification stricte de stabilité numérique.

Le masque de diagnostic concerne les bandes sélectionnées avec un gain non
nul dans la cible de chaîne observée. Une bande désactivée ou à 0 dB n’a pas
de correction à calculer. Le format d’un autre fichier peut permettre une
fréquence précédemment inapplicable sans changer la préférence.

## Limiteur : plafond, récupération et limites

Le limiteur prend la plus grande amplitude absolue de la trame et calcule un
gain commun aux deux canaux. Une baisse nécessaire est immédiate ; la même
atténuation sur gauche et droite conserve leur rapport. Il n’insère pas de
tampon d’anticipation et ne retire aucune trame.

Le plafond effectif est arrondi vers le bas à une amplitude PCM16 positive
représentable. La réserve ne dépasse pas un pas de quantification. À 0 dBFS,
la borne symétrique retenue est `32767 / 32768` : le PCM16 signé dispose d’une
valeur négative extrême supplémentaire. Ce choix évite un dépassement causé
par l’arrondi final tout en gardant le même gain sur les deux canaux.

En l’absence d’une nouvelle crête, l’écart entre le gain courant et l’unité
décroît exponentiellement. Après une durée égale au relâchement demandé,
environ **63,2 % de cet écart** est récupéré ; le gain n’est pas soudainement
égal à un. Une durée courte peut rendre la modulation plus perceptible ; une
durée longue prolonge la réduction. Une nouvelle crête peut interrompre cette
récupération.

Il s’agit d’un **limiteur de crêtes d’échantillons**, sans mesure *true-peak* des
crêtes entre échantillons, sans mesure de sonie LUFS et sans normalisation
d’œuvre. Son action instantanée peut déformer un transitoire. Son plafond
concerne la sortie de ce processeur : il ne certifie pas le comportement de
traitements Media3 ultérieurs, du mixeur Android, du convertisseur ou du casque.

## Moment d’application et transitions

Une commande est d’abord validée et enregistrée, puis soumise au processeur.
La demande est consommée au début du prochain bloc PCM compatible complet.
Si aucun bloc n’arrive, par exemple pendant une pause ou sur un parcours
qui évite ce processeur, la soumission peut rester en attente.

Les changements du préampli, des bandes, du mono et de la balance utilisent
un fondu de **20 ms de trames audio traitées** entre deux chaînes stables.
Les coefficients récursifs ne sont pas interpolés. Si des changements arrivent
pendant ce fondu, seule la dernière cible en attente est conservée, puis son
fondu commence après le premier. Cela borne le nombre de chaînes évaluées
simultanément à deux ; le temps écoulé au mur peut augmenter si le flux s’arrête.

Changer seulement le limiteur ne réinitialise pas les états de l’égaliseur.
Modifier un paramètre latent dont le traitement est désactivé ne crée pas
artificiellement de transition audio. Une demande numérique identique,
y compris un autre signe de zéro, ne relance rien.

Une nouvelle activation ou un nouveau plafond du limiteur s’applique dès la
consommation de la demande, même pendant un fondu de l’égaliseur. Le plafond
peut donc imposer immédiatement une réduction. Désactiver seulement le limiteur
libère son gain résiduel sur 20 ms. Désactiver le traitement général maintient
le limiteur de l’ancienne chaîne jusqu’au retour au signal non traité, puis
libère son gain sur 20 ms. Une fois ces transitions terminées, le passage
globalement désactivé retrouve une copie exacte des échantillons PCM16 reçus.

Ces fondus réduisent les changements brusques de configuration ; ils ne
constituent pas une garantie de transition inaudible dans toutes les conditions.
La chaîne n’ajoute ni ne supprime de trame, ne rééchantillonne pas et ne change
pas volontairement la synchronisation entre la vidéo et ses sous-titres.

## Persistance, retours aux défauts et durée de vie

Tous les paramètres avancés persistent entre les vidéos et les ouvertures de
l’application, même lorsque le traitement général ou une bande est désactivé.
Le groupe est enregistré sous un format versionné. Une représentation absente,
incompatible ou invalide revient aux défauts du DSP, avec activation générale
désactivée ; les préférences vocales historiques sont indépendantes.

**Rétablir tous les traitements avancés** désactive le traitement général et les
cinq bandes, remet préampli et balance à zéro, désactive le mono et présélectionne
le limiteur à −1 dBFS avec un relâchement de 100 ms. Cette action ne réinitialise
pas les volumes du film et de la voix, les trois durées historiques ni le filtre
spectral. Les retours au défaut individuels ne changent que le paramètre indiqué.

Chaque panneau et chaque sous-dialogue conserve le ticket de l’ouverture
d’origine. Une autre ouverture, y compris du même fichier, rend ce ticket
périmé. La validation au clic refuse une demande tardive et laisse les
préférences intactes. Fermer le panneau propriétaire ferme ses sous-dialogues ;
une interface détachée ou une activité détruite ne peut plus écrire.

## Lire les observations sans les surinterpréter

Le panneau présente des observations du **dernier flux PCM vu**, mises à jour
après traitement d’un bloc. Elles peuvent encore décrire la fin du flux précédent
après une nouvelle ouverture ; elles ne sont pas attribuées automatiquement à
la nouvelle vidéo. **Actualiser** consulte ces données sans modifier la lecture,
les préférences, les traitements ou la narration.

| Information | Ce qu’elle établit et sa limite |
|---|---|
| Révision soumise | Configuration acceptée par l’adaptateur ; ne prouve pas qu’un bloc l’a consommée |
| Dernière révision consommée | Configuration lue pour un bloc complet ; peut être différente de la dernière soumission |
| Cible de chaîne observée | Cible du préampli, de l’égaliseur, du mono et de la balance ; le résultat peut encore être mélangé à l’ancienne chaîne |
| Dernière consigne du limiteur | Valeurs consommées, soumises à l’activation générale et à la transition de sortie du limiteur |
| Format et compatibilité | Dernier format activé pour le processeur ; un format compatible seul ne prouve pas qu’une trame est passée |
| Transition | État constaté après le dernier bloc, pas une mesure continue de la sortie acoustique |
| Bandes ignorées | Masque des bandes non neutres inapplicables dans la cible observée |
| Trames PCM traitées | Cumul depuis le dernier `flush` ou `reset`, y compris le passage désactivé ; une trame stéréo contient deux échantillons |
| Trames limitées | Trames non silencieuses auxquelles un gain inférieur à un a été appliqué, y compris son relâchement |
| Échantillons écrêtés après arrondi ou invalides | Valeurs non finies, ou valeurs arrondies hors de la plage PCM16 nécessitant une saturation lors de la conversion |
| Crête numérique | Maximum absolu du dernier bloc PCM16 écrit, après quantification ; zéro signifie silence numérique |

Les compteurs avancés repartent à zéro lorsque Media3 renouvelle le flux par
`flush` ou remet le processeur à zéro. Ils sont différents du compteur cumulé
de session du filtre spectral historique. Une observation absente reste
inconnue : elle n’est pas remplacée par une mesure inventée.

## Formats, intégration et protection de la vocalisation

`ExpertAudioProcessor` traite seulement une entrée **PCM16 à un ou deux canaux**,
avec une fréquence d’échantillonnage positive. Un format incompatible rend ce
processeur inactif sans convertir ou remapper arbitrairement ses canaux. Une
piste multicanal décodée puis convertie en stéréo avant ce point peut présenter
un flux compatible ; le nombre de canaux de l’entrée réellement observée fait foi.

Les processeurs personnalisés de `DefaultAudioSink` concernent le parcours PCM,
pas les sorties compressées directes (*passthrough*) ou déportées (*offload*).
La sortie flottante constitue aussi un autre parcours. Une capacité déclarée
« conditionnelle » et une commande acceptée ne prouvent donc pas un traitement
effectif. Cette étape ne force pas une nouvelle stratégie de décodage pour
faire apparaître une mesure.

Le processeur reste installé pendant un passage globalement désactivé compatible,
afin que son activation ne reconstruise pas le lecteur ni sa sortie audio.
`configure` prépare un format ; les états du nouveau flux ne sont créés qu’au
`flush` correspondant. Les anciens buffers peuvent ainsi terminer avec leur
ancien format. `reset` retire les observations et les états, tout en conservant
les préférences soumises pour une utilisation ultérieure.

Le traitement ne reçoit que le film. Il ne commande ni synthèse vocale, ni pause,
ni reprise, ni sélection de sous-titres. Les commandes avancées empruntent le port
audio validé ; elles ne passent pas par le rafraîchissement général susceptible
de préparer une voix. Un vrai changement termine le banc d’essai en cours pour
éviter d’attribuer ses mesures à deux configurations différentes.
L’empreinte des réglages du banc d’essai inclut tous les paramètres DSP
enregistrés, y compris ceux actuellement désactivés. Deux ensembles différents
ne partagent donc pas une identité de comparaison ; restaurer exactement les
mêmes préférences restaure cette partie de l’identité.

La conservation de ces frontières doit être vérifiée avec la phrase en cours,
la file vocale, les répétitions légitimes, les pauses, les déplacements, les
callbacks anciens et les fins de média. Les algorithmes de narration et le
`SpeechBandProcessor` historique ne font pas partie de la réécriture.

## Preuves demandées et limites

Les tests portent sur la validation exacte des nombres, la conservation des
champs compagnons, la persistance et les refus de panneaux périmés. Les tests
DSP utilisent des signaux synthétiques : réponse en fréquence mesurée par RMS,
annulation boost/coupure inverse, limites de Q et de fréquence, indépendance
des canaux, comportement du limiteur, transitions et copie PCM16 exacte au repos.
Les tests d’adaptateur vérifient aussi les buffers vides ou incomplets, le
fractionnement des blocs, les changements de format et les remises à zéro.

Les effectifs et réussites doivent provenir d’une exécution réellement effectuée,
associée à son commit, avec toutes les régressions antérieures conservées. Un
émulateur et une voix de test ne remplacent pas l’écoute sur le téléphone cible,
une vraie voix, TalkBack, les sorties Bluetooth ou une séance prolongée. Aucune
supériorité acoustique universelle ni absence absolue de bug n’est annoncée.

## Sources techniques

Les plages, valeurs par défaut, transitions et règles du limiteur sont des choix
du projet, définis par ses sources. Les documents tiers décrivent les formules
ou les contrats de la bibliothèque ; ils ne valident pas cette application.

- [W3C Audio EQ Cookbook](https://www.w3.org/TR/audio-eq-cookbook/), note du 8 juin 2021 : coefficients des biquads en cloche et convention de Q, issus de Robert Bristow-Johnson.
- [Media3 1.11.1 — AudioProcessor](https://github.com/androidx/media/blob/1.11.1/libraries/common/src/main/java/androidx/media3/common/audio/AudioProcessor.java) : buffers, format préparé et cycle de flux.
- [Media3 1.11.1 — BaseAudioProcessor](https://github.com/androidx/media/blob/1.11.1/libraries/common/src/main/java/androidx/media3/common/audio/BaseAudioProcessor.java) : activation des formats à `flush` et remise à zéro explicite.
- [Media3 1.11.1 — DefaultAudioSink](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java) : position des processeurs personnalisés et limites des parcours PCM, flottant, passthrough et offload.
- [Android Developers — DefaultAudioSink.Builder](https://developer.android.com/reference/androidx/media3/exoplayer/audio/DefaultAudioSink.Builder) : documentation officielle de l’assemblage des traitements ; la version du projet reste fixée dans ses fichiers Gradle.
- [`AudioDsp.kt`](../core/src/main/kotlin/fr/soustitresvoix/core/AudioDsp.kt), [`AudioNumericInput.kt`](../core/src/main/kotlin/fr/soustitresvoix/core/AudioNumericInput.kt) et [`AudioCommands.kt`](../core/src/main/kotlin/fr/soustitresvoix/core/AudioCommands.kt) : paramètres, validation et traitement pur.
- [`ExpertAudioProcessor.kt`](../app/src/main/java/fr/soustitresvoix/player/ExpertAudioProcessor.kt), [`AudioDspPreferences.kt`](../app/src/main/java/fr/soustitresvoix/player/AudioDspPreferences.kt), [`AudioDspControls.kt`](../app/src/main/java/fr/soustitresvoix/player/AudioDspControls.kt) et [`AudioNumberDialog.kt`](../app/src/main/java/fr/soustitresvoix/player/AudioNumberDialog.kt) : parcours PCM, persistance, panneaux et saisies.

## Étapes suivantes proposées

**Étape 7 — Dynamique contrôlée.** Ajouter un compresseur paramétrable du film,
avec seuil, ratio, attaque, relâchement et compensation explicitement définis.
Valider sur signaux connus la courbe statique, les enveloppes et l’interaction
avec le limiteur. Conserver l’activation volontaire et la séparation de la voix.

**Étape 8 — Profils et comparaison.** Enregistrer des configurations nommées et
proposer une comparaison A/B contrôlée, avec état visible, restauration exacte,
valeurs validées et distinction entre différence de niveau et différence de
traitement. Les comparaisons ne doivent pas déclencher de nouvelle narration.

**Étape 9 — Formats et multicanal.** Étendre les capacités uniquement après
définition des formats de calcul, des rôles des canaux, des règles de downmix
et du comportement des parcours directs. Tester chaque correspondance réelle,
puis les performances sur le téléphone. Un moteur alternatif reste une décision
distincte ; ajouter plusieurs moteurs ne suffit pas à établir une amélioration.
