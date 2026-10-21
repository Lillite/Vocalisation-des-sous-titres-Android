# Sous-titres en voix — 0.3.2

Version corrective issue de Cinéma 0.3.1. L’application `fr.soustitresvoix.player.stable` utilise une signature durable conservée hors du dépôt. Son identité distincte permet de conserver les anciennes éditions de test, dont la clé privée n’est pas disponible ici. Les préférences et les autorisations d’ouverture des vidéos restent propres à chaque installation ; choisir de nouveau la voix et les vidéos dans cette édition. Aucun fichier vidéo original n’est modifié.

La procédure de vérification et de signature figure dans `TESTING.md`. Les rapports d’exécution précisent les environnements réellement testés ; une compilation ou un ancien résultat CI ne valide pas automatiquement une nouvelle version.

## Premier essai

Choisir une voix, ouvrir « Essai intégré : sous-titres et mire de cadrage », activer « Plein écran cinéma », puis « Étirement intégral ». Les quatre coins rouge, vert, bleu et jaune doivent rester visibles ; le cercle central devient une ellipse lorsque l’écran n’a pas les proportions de la vidéo. Le zoom est remis à 100 %, les proportions changent, les anciennes marges retirées sont annulées. Dans Image / zoom, les modes sont explicitement distingués et les transformations accidentelles verrouillées. L’analyse des marges encodées est facultative, avec aperçu et confirmation avant traitement. Les filtres visuels SDR sont facultatifs et comparables à l’original.

Dans Voix / écran : choisir Adaptatif, vitesse de base 100 %, plafond 180 % comme point de départ, tolérance 600 ms. Le mode estime la durée même sans cache ; il utilise des mesures lorsqu’un WAV est préparé. Si le délai ne suffit pas, le film attend encore plutôt que perdre des mots. Préparer cinq minutes ou toute la vidéo est une opération explicite, annulable ; lecture en pause pendant la préparation. Le cache reste limité et ne garantit pas la conservation de toute une œuvre trop volumineuse.

## Documents

- `docs/REVISION_03.md` : changements exacts, limites et périmètre de validation.
- `docs/REVISION_031.md` : corrections supplémentaires issues de la relecture et contrôles de livraison.
- `docs/COUVERTURE_DEMANDES_031.md` : correspondance détaillée avec chaque demande, fonctions intégrées et limites.
- `docs/VOIX_CATALOGUE_2026.md` : catalogue documentaire de moteurs, modèles et passerelles, liens primaires, dates et tailles connues.
- `docs/ETUDE_ACCELERATION.md` : GPU/NPU, preuves nécessaires et fonctions non intégrées.
- `docs/TESTS_TELEPHONE.md` : parcours d’essai initial, à compléter par les scénarios 0.3.

## Compilation

Java 17 pour la compilation et Java 21 pour les tests Android 36 Robolectric, Gradle 8.13, SDK 36, Build Tools 36.0.0, Kotlin 2.2.21, AGP 8.11.1, Media3 1.11.1. FFmpeg ne sert qu’à générer la démonstration synthétique sur le serveur de compilation.

```
gradle --no-daemon :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
gradle --no-daemon :app:connectedDebugAndroidTest
```

Les tests de la 0.2 sont conservés. Nouveaux contrôles : ponctuation, échéances et répétitions simultanées, persistance et intégrité du cache, détection prudente des marges, annulation de l’indexation MKV/SRT, vitesse transmise au vrai service TTS de test, pixels des quatre coins après étirement, restauration du cadrage, effets SDR et traitement audio PCM. Les voix externes réelles, TalkBack complet, HDR et performances du OnePlus restent à mesurer sur l’appareil. Pas de nouveau modèle vocal neuronal ou backend NPU intégré.

Le lecteur ne demande ni Internet, ni microphone, ni caméra, ni permission générale de stockage. Les pages du catalogue ne s’ouvrent dans le navigateur que sur demande. Un moteur vocal externe peut avoir ses propres permissions. Les clés de signature et mots de passe sont exclus du dépôt.
