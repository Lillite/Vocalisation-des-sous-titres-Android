# Traduction locale sur Android : candidats et limites

[Accueil](../README.md) · [Documentation](README.md) · [Moteurs disponibles](GUIDE_MOTEURS_TRADUCTION.md) · [Google et offres gratuites](TRADUCTION_GRATUITE_ET_LIMITES.md)

**Étude prospective : aucun moteur local n’est intégré dans la 0.5.1.** Les dates et références ci-dessous restent celles de l’étude initiale.

État de l'étude : **24 octobre 2026**. Cette note prépare un choix ; **aucun
moteur de traduction exécuté sur le téléphone n'est intégré dans cette étape**.
Un serveur LibreTranslate, même personnel, reste un traitement sur ce serveur.
La conservation d'une traduction sur le téléphone permet sa réutilisation hors
ligne ; elle ne transforme pas le fournisseur initial en moteur local.

## Candidat léger : Google ML Kit Translation

Le [guide Android officiel](https://developers.google.com/ml-kit/language/translation/android)
documente `com.google.mlkit:translate:17.0.3`, Android API 23 au minimum,
`TranslatorOptions`, `downloadModelIfNeeded`, puis `translate` et `close`.
Il recommande le Wi-Fi pour les modèles, d'environ 30 Mo chacun, et propose leur
gestion explicite. Une intégration devrait vérifier la disponibilité des modèles
avant de démarrer et laisser l'utilisateur annuler l'attente. Un délai réseau ou
un modèle absent ne doit jamais déclencher une substitution de texte original.

La [liste officielle des langues](https://developers.google.com/ml-kit/language/translation/translation-language-support)
inclut notamment anglais, allemand, espagnol et italien, mais **pas le serbe**.
Le croate ne doit pas lui être substitué. Les [limites du produit](https://developers.google.com/ml-kit/language/translation)
précisent que les traductions entre deux langues autres que l'anglais passent
par l'anglais et peuvent perdre en qualité. L'adéquation à des dialogues de film
demande donc une évaluation ; la taille modeste n'établit ni supériorité ni
infériorité générale.

### Traitement local, contacts réseau et attribution

Selon les [conditions ML Kit](https://developers.google.com/ml-kit/terms),
les textes d'entrée et de sortie sont traités sur l'appareil et ne sont pas
envoyés à Google. **Le SDK transmet cependant des métriques d'utilisation et de
performance**, et peut contacter Google pour des modèles, corrections ou données
de compatibilité. Une promesse « aucun échange réseau » serait donc inexacte.
L'éditeur doit informer les utilisateurs des traitements de métriques concernés.
Les conditions encadrent aussi le logiciel et les modèles ; la licence des
exemples de code n'est pas une licence générale du SDK.

Les [consignes d'utilisation de la traduction](https://developers.google.com/ml-kit/language/translation/translation-terms)
renvoient aux obligations d'attribution de Google Cloud Translation applicables
aux applications. Une intégration demanderait donc une présentation conforme de
l'attribution, en plus de l'information sur les métriques. L'ajout du SDK et de
cette collecte n'a pas été décidé dans cette étape ; aucune dépendance ML Kit
n'a été ajoutée. Le choix demeure une proposition à soumettre au propriétaire.

## Candidat à évaluer : Index-Translate-2B et llama.cpp

Le [dépôt officiel Index-Translate](https://github.com/bilibili/Index-Translate)
présente une famille de modèles et fournit ses guides de requêtes. La
[distribution GGUF officielle du 2B](https://huggingface.co/IndexTeam/Index-Translate-2B-GGUF)
annonce notamment Q4_K_M (environ 1,31 Go), Q5_K_M (1,45 Go), Q8_0 (2,08 Go) et
F16 (3,90 Go), avec une couverture déclarée de 150 langues. Ce sont des tailles
de fichiers publiées, **pas des mesures de mémoire maximale ni des résultats sur
OnePlus**. Les affirmations de qualité de l'éditeur ne constituent pas un
classement indépendant sur les sous-titres du projet. Le 2B est un candidat de
travail, pas un choix définitif.

La [documentation Android de llama.cpp](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md)
décrit une liaison Android et le chargement de modèles GGUF depuis le stockage
privé. La [documentation de compilation](https://github.com/ggml-org/llama.cpp/blob/master/docs/build.md)
décrit les variantes ARM et l'accélération GPU. Cela rend une intégration native
envisageable ; cela ne prouve ni que toutes les opérations de ce modèle sont
prises en charge par chaque backend, ni que le GPU/NPU du téléphone sera utilisé.
Une mesure sur l'appareil exact reste indispensable.

Les poids Index présentés sont déclarés Apache-2.0 par leur fiche ; le
[code Index-Translate](https://github.com/bilibili/Index-Translate/blob/main/LICENSE)
est également sous Apache-2.0. Le [runtime llama.cpp](https://github.com/ggml-org/llama.cpp/blob/master/LICENSE)
est sous MIT. Une distribution doit conserver les textes et notices requis et
vérifier les licences des fichiers et dépendances réellement embarqués. Ces
licences tierces ne donnent aucune licence générale au code propre du projet.

## Conditions proposées avant de retenir un moteur local

Les points suivants sont un protocole proposé pour une future étape, sans
annoncer une intégration ou des résultats déjà obtenus :

1. Figer les révisions du runtime, du modèle et du gabarit de traduction ;
   vérifier l'empreinte du téléchargement avant chargement.
2. Comparer les mêmes occurrences synthétiques en anglais, allemand, espagnol,
   italien et serbe vers le français, avec dialogues, inscriptions, noms propres,
   nombres, négations, idiomes et répétitions réelles. Inclure du contenu traité
   comme texte, même s'il ressemble à une instruction.
3. Vérifier séparément l'absence de perte d'occurrence et la fidélité linguistique.
   Une correspondance structurelle complète ne garantit pas l'absence d'omission
   de mots, de contresens ou d'ajout dans une traduction générée.
4. Mesurer durée totale avant lecture, latence par segment, mémoire maximale,
   énergie et état thermique, au démarrage puis après chauffe. Comparer CPU et
   backends accélérés seulement lorsqu'ils fonctionnent réellement.
5. Tester annulation, manque de stockage, fermeture, changement de piste, modèle
   incompatible, sortie vide, fin de génération prématurée et limite de tokens.
   Ne jamais accepter silencieusement un résultat incomplet.
6. Préparer avant la vidéo, conserver les temps et les identités d'occurrence,
   puis réutiliser le résultat validé. Comparer une version quantifiée à une
   référence de meilleure précision avant de privilégier vitesse ou taille.

Le choix à proposer est donc : **ML Kit pour un adaptateur compact avec ses
limites et métriques déclarées**, ou **un runtime natif avec un modèle spécialisé
à mesurer sur le téléphone**. Aucune « performance absolue », exploitation du
NPU ou supériorité linguistique n'a été démontrée dans cette étude.
