# Régression détectée par l'exécution réelle sur Android

> **Note historique de la version initiale.** Les nombres de tests et l’environnement ci-dessous décrivent cette régression au moment de sa correction. La procédure actuelle se trouve dans [TESTING.md](../TESTING.md) ; voir aussi l’[index de la documentation](README.md) et l’[historique des versions](../CHANGELOG.md).

## Échec observé

L'exécution GitHub Actions 39185983950 a démarré un émulateur Android 35 et installé l'application. Le premier sous-titre du véritable MKV de démonstration a déclenché une `ExceptionInInitializerError` dans `SubtitleText`, causée par `PatternSyntaxException` dans le moteur ICU d'Android.

La compilation complète et les tests JVM avaient réussi. Ce cas illustre pourquoi ils ne suffisent pas à valider l'exécution Android.

## Correction

Les délimiteurs de fin littéraux sont désormais explicitement échappés dans les expressions régulières de nettoyage des styles ASS, des descriptions entre crochets et de détection de l'en-tête ASS. Aucun test existant n'a été retiré ou rendu moins exigeant.

## Contrôles ajoutés

`SubtitleParsingAndroidTest` ajoute trois tests instrumentés : nettoyage des styles et descriptions, import SRT/VTT/ASS avec plusieurs encodages, préservation des caractères Unicode lors du découpage vocal. Ils complètent les deux tests d'interface et de lecture MKV existants, soit cinq tests Android attendus.

La réussite de la correction doit être constatée dans une nouvelle exécution et dans ses rapports. Ce document n'affirme pas une réussite avant cette exécution.
