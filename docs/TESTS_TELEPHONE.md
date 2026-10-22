# Vérifier l’application sur le téléphone

[Index de la documentation](README.md) · [Installation et présentation](../README.md)

Ce parcours concerne l’édition **0.3.2**. **Les vérifications ci-dessous restent à effectuer sur le téléphone : elles ne sont pas déclarées réussies par une compilation ou un test sur émulateur.** Pour chaque essai, noter « réussi », « échec » ou « non testé », avec le moteur vocal et les réglages utilisés.

## Installer et choisir la voix

1. Télécharger l’[APK 0.3.2 publié](https://github.com/Lillite/Vocalisation-des-sous-titres-Android/releases/download/v0.3.2/SousTitresVoix-0.3.2-stable.apk), puis ouvrir **Sous-titres en voix**. Cette édition utilise l’identifiant `fr.soustitresvoix.player.stable` et peut coexister avec les anciennes éditions de test. Il faut y choisir de nouveau la voix et les vidéos.
2. Aller dans **Réglages de la voix et du confort**. Au besoin, utiliser **Choisir le moteur vocal de CE lecteur**, puis installer les données vocales dans ce moteur. Ce choix ne modifie pas le moteur de TalkBack.
3. Utiliser **Choisir une voix hors ligne** et sélectionner une voix dans la langue des sous-titres. L’application ne traduit pas les textes.
4. Appuyer sur **Écouter un essai de la voix**. Le film doit rester en pause. Ajuster le débit et les volumes à un niveau confortable.
5. Choisir **Confort : phrases complètes**, puis lancer **Essai intégré : sous-titres et mire de cadrage**.

L’APK publié provient du commit `311d99052d287bb2227e85255740de201234e770`. Les archives de sources des anciens tags ne doivent pas servir à en déduire le code : voir l’[historique des branches et des versions](HISTORIQUE_BRANCHES.md).

## Narration, pauses et reprise

| Essai | Résultat à vérifier |
|---|---|
| Écouter la première phrase | Une seule lecture de cet événement, sans boucle |
| Laisser une phrase longue se terminer en Confort | La vidéo attend si nécessaire ; la phrase n’est pas coupée pour rattraper le film |
| Appuyer sur Pause au milieu d’une phrase | Vidéo et voix s’arrêtent ; aucune reprise spontanée |
| Reprendre explicitement | Le comportement correspond au réglage choisi dans **Choisir la reprise après Pause** |
| Reculer de dix secondes pendant la narration | L’ancienne phrase ne se poursuit pas au nouvel instant |
| Utiliser **Répéter la dernière phrase — met en pause** | La phrase est répétée ; le film reste en pause à la fin |
| Atteindre la fin de la démonstration en Confort | La dernière phrase peut se terminer |
| Refaire l’essai en Adaptatif | Accélération limitée au plafond choisi, puis pause si le temps manque ; aucun mot sacrifié pour tenir le délai |

Pour Adaptatif, une vitesse de base à 100 %, un plafond à 180 % et une tolérance de 600 ms constituent un point de départ à ajuster à l’écoute. Le mode **Continu : peut couper les phrases** ne convient pas pour vérifier la vocalisation complète.

## Sous-titres intégrés et externes

Choisir une vidéo locale avec une piste textuelle, puis **Choisir la piste de sous-titres**. Tester aussi **Ajouter un fichier de sous-titres** avec un SRT, VTT ou ASS/SSA connu. Utiliser d’abord les médias synthétiques disponibles ; un fichier personnel reste sur le téléphone.

Pour un extrait comportant des panneaux ou des sous-titres FULL, relever les textes et horaires attendus, puis vérifier :

- un panneau continu n’est pas relu à chaque changement de dialogue ;
- les dialogues qui se superposent au panneau restent prononcés ;
- deux événements distincts contenant les mêmes mots sont tous deux conservés ;
- un panneau qui réapparaît plus tard est de nouveau lu ;
- une phrase longue, sa ponctuation et ses segments ne disparaissent pas à la transition suivante ;
- un changement de piste ou un déplacement ne déclenche pas les callbacks d’une ancienne phrase.

Un MKV peut contenir un codec non pris en charge. Les sous-titres en images PGS/VobSub et le texte incrusté dans l’image ne sont pas vocalisés par cette version.

## Image et plein écran

1. Dans la démonstration, activer **Plein écran cinéma**, puis **Étirement intégral**. Les quatre coins rouge, vert, bleu et jaune doivent rester visibles. Si l’écran et la vidéo ont des proportions différentes, le cercle central devient une ellipse.
2. Ouvrir **Image / zoom** et comparer **Image entière, proportions originales**, **Tout l’écran — image entière étirée** et **Tout l’écran, image recadrée**. Vérifier que le recadrage volontaire demande sa confirmation lorsque la protection est active.
3. Revenir à un mode « image entière » après un zoom ou un recadrage : zoom à 100 %, déplacements annulés et marges précédemment retirées annulées.
4. Consulter **Diagnostic du cadrage et des bandes noires** et comparer taille vidéo, surface disponible et mode actif.
5. Sur un extrait adapté, essayer **Analyser les marges encodées — aperçu obligatoire**. Une proposition doit être présentée avant application. Vérifier **Annuler toute suppression de marges**, puis ouvrir une autre vidéo : elle ne doit pas hériter des marges de la précédente.

L’analyse échantillonnée ne certifie pas l’absence de texte dans toutes les marges de toute une œuvre. Les coins arrondis et l’emplacement physique de la caméra restent des contraintes de l’écran.

## Préparation, cache et moteur externe

- Essayer **Préparer les 5 prochaines minutes**, puis annuler : l’état doit être compréhensible et le film ne doit pas reprendre seul.
- Avec un extrait court, essayer **Indexer et préparer toute la vidéo**. Vérifier la lecture après la préparation, puis après fermeture et réouverture de l’application. Une préparation achevée ne garantit pas que tout un long film tient encore dans le cache limité.
- Changer de voix ou de débit : l’audio préparé avec les anciens paramètres ne doit pas être réutilisé comme s’il était à jour.
- Utiliser **Vider le cache vocal** et vérifier que la même phrase reste lisible en synthèse directe.
- Si un moteur choisi devient indisponible, vérifier que l’erreur reste visible et qu’un autre moteur peut être choisi. Ce scénario peut être marqué « non testé » ; il n’exige pas de désinstaller un moteur utilisé par d’autres applications.

## Accessibilité, interruptions et séance prolongée

Vérifier les libellés et l’ordre de navigation avec TalkBack, les polices agrandies, le portrait/paysage et l’agrandissement système. Les sous-titres ne doivent pas être lus involontairement deux fois par TalkBack et la synthèse du lecteur. Les commandes doivent rester accessibles sans geste obligatoire.

Tester la mise en arrière-plan, le verrouillage de l’écran, une interruption audio et le branchement/débranchement d’un casque. Vérifier l’absence de reprise inattendue. Ajuster séparément le son du film et celui de la voix, puis rouvrir les réglages pour contrôler la conservation des valeurs.

Enfin, effectuer une séance prolongée avec les fichiers et le moteur habituels. Relever la synchronisation, l’intelligibilité, les pauses, les éventuels blocages et la consommation observée. Les mesures locales ne remplacent pas l’écoute, et un essai sur émulateur ne mesure pas les performances du téléphone.

## Signaler un résultat

Indiquer la version installée, le modèle du téléphone, Android, le moteur et la voix, le mode vocal, les actions exactes et le résultat attendu puis observé. Pour un problème de lecture, ajouter le conteneur, les codecs, le type de sous-titres et l’instant concerné.

**Copier le diagnostic complet** peut aider. Relire son contenu avant de le partager et retirer toute information personnelle inutile. Ne pas envoyer de film personnel pour un simple diagnostic ; privilégier un court exemple de sous-titres synthétiques qui reproduit le problème.
