package fr.soustitresvoix.player

/**
 * User-facing explanations only; this object never reads or changes playback.
 *
 * Checked against the pinned Media3 1.11.1 sources:
 * https://github.com/androidx/media/blob/1.11.1/libraries/effect/src/main/java/androidx/media3/effect/Contrast.java
 * https://github.com/androidx/media/blob/1.11.1/libraries/effect/src/main/java/androidx/media3/effect/Brightness.java
 * https://github.com/androidx/media/blob/1.11.1/libraries/effect/src/main/java/androidx/media3/effect/LanczosResample.java
 * Window brightness uses the existing normalized window override, not a nits value:
 * https://developer.android.com/reference/android/view/WindowManager.LayoutParams#screenBrightness
 * App-specific limits and protections come from ImageCommands, VisualTools and CinemaView.
 */
internal object ImageSettingsDescriptions {
    const val INTRO = "Modifie les réglages progressivement et compare le résultat sur la même scène. " +
        "Le contraste et la luminosité de l’image sont conservés pendant cette ouverture de vidéo, " +
        "puis remis à zéro à la prochaine ouverture, même pour le même fichier. " +
        "La comparaison et Lanczos sont également réinitialisés à l’ouverture. " +
        "Les corrections concernées restent neutralisées lorsqu’un format HDR ou Dolby Vision est détecté. " +
        "L’absence de cette détection ne certifie pas un format SDR."

    const val CONTRAST = "Modifie l’écart entre les tons de l’image. Une valeur positive renforce cet écart ; " +
        "une valeur négative l’atténue. Zéro conserve le contraste d’origine. " +
        "Plage du lecteur : de −0,30 à +0,30, bornes incluses ; ce coefficient n’est pas un pourcentage. " +
        "Utilise les boutons par pas de 0,01 ou la saisie d’une valeur décimale, avec une virgule ou un point. " +
        "Un contraste élevé peut rendre des détails sombres ou clairs moins visibles. " +
        "Le raccourci « Contraste léger » passe de zéro à +0,12, et ramène toute valeur non nulle à zéro."

    const val IMAGE_BRIGHTNESS = "Ajoute le même décalage aux composantes rouge, verte et bleue de la vidéo. " +
        "Une valeur positive éclaircit l’image ; une valeur négative l’assombrit. Zéro conserve les valeurs d’origine. " +
        "Plage du lecteur : de −0,20 à +0,20, bornes incluses ; ce coefficient n’est pas un pourcentage de lumière. " +
        "Utilise les boutons par pas de 0,01 ou la saisie décimale, avec une virgule ou un point. " +
        "Un réglage excessif peut masquer des détails ; il ne recrée pas les informations absentes du fichier. " +
        "Il ne change pas la luminosité de l’écran. Le raccourci d’éclaircissement passe de zéro à +0,06, " +
        "et ramène toute valeur non nulle à zéro."

    const val ORIGINAL_COMPARISON = "Coche pour neutraliser temporairement le contraste, la luminosité de l’image, " +
        "les suppressions de marges et le redimensionnement Lanczos. Leurs réglages restent conservés. " +
        "Décoche pour les retrouver si les protections courantes le permettent. " +
        "Le mode de cadrage choisi, le zoom, les déplacements et la luminosité de l’écran restent inchangés. " +
        "Tu peux modifier les corrections pendant la comparaison : elles sont mémorisées, mais restent masquées. " +
        "Cette case ne permet jamais de contourner la protection HDR."

    const val FRAMING_FIT = "Appuie pour voir toute l’image en conservant ses proportions. " +
        "Des bandes peuvent rester visibles lorsque les proportions de la vidéo et de l’écran diffèrent. " +
        "Ce choix remet le zoom à 1,00×, recentre l’image, annule les marges précédemment retirées " +
        "et active la protection contre le recadrage accidentel."

    const val FRAMING_STRETCH = "Appuie pour remplir la surface vidéo avec l’image entière, en modifiant ses proportions : " +
        "les formes peuvent paraître élargies ou allongées. Ce choix remet le zoom à 1,00×, recentre l’image, " +
        "annule les marges précédemment retirées et active la protection contre le recadrage accidentel. " +
        "Les bandes encodées dans les pixels du fichier ne sont pas supprimées par cet étirement."

    const val FRAMING_CROP = "Appuie pour remplir la surface vidéo en conservant les proportions, au prix d’une coupe " +
        "sur certains bords. Des personnages ou des textes incrustés peuvent sortir du cadre. " +
        "Si la protection est active, confirme explicitement le recadrage pour la désactiver. " +
        "Ce choix remet le zoom à 1,00× et recentre l’image. " +
        "Il est mémorisé pour cette vidéo ; il ne devient pas le choix par défaut pour les nouveaux fichiers."

    const val FRAME_PROTECTION = "Coche pour bloquer le zoom et les déplacements accidentels, revenir au zoom 1,00× " +
        "et recentrer l’image. Si le mode recadré est actif, l’application revient au mode image entière " +
        "avec proportions originales. Décoche pour permettre le zoom et les déplacements. " +
        "La suppression de marges validée dans un aperçu reste une opération distincte : " +
        "utilise son bouton d’annulation pour la retirer."

    const val FRAME_DIAGNOSTIC = "Appuie pour consulter les dimensions de la vidéo codée, ses proportions déclarées, " +
        "les dimensions de sortie du traitement et la surface disponible. Le diagnostic indique aussi " +
        "le cadrage, le zoom, la protection et les marges validées. " +
        "Ces informations aident à distinguer les bandes dues aux proportions et celles encodées dans le fichier ; " +
        "elles ne mesurent pas les pixels effectivement visibles ni les petites zones masquées par les coins physiques ou le poinçon."

    const val MARGIN_ANALYSIS = "Appuie pour mettre la lecture en pause et analyser localement sept images réparties " +
        "dans la vidéo. Si des marges constantes sont proposées, vérifie les sept aperçus avant de les appliquer. " +
        "Aucune suppression n’est automatique et le fichier original n’est pas modifié. " +
        "Des scènes sombres, des textes près des bords ou des bandes variables peuvent empêcher une proposition fiable. " +
        "L’aperçu ne garantit pas l’absence de texte dans les autres images. " +
        "L’analyse exige Android 8.1 ou plus et reste désactivée lorsqu’un format HDR ou Dolby Vision est détecté."

    const val MARGIN_RESET = "Appuie pour annuler les suppressions de marges précédemment validées. " +
        "Cela remet à zéro les quatre marges retirées, sans changer les valeurs de contraste ou de luminosité de l’image. " +
        "Le mode de cadrage reste choisi séparément : un mode recadré peut donc encore couper des bords."

    const val DETAILED_SCALING = "Coche pour demander un redimensionnement par filtre Lanczos. " +
        "Il recalcule les pixels lors d’un changement de taille, en conservant les proportions à cette étape, " +
        "sans reconstruire de détails absents. Le cadrage choisi s’applique ensuite. " +
        "La cible suit la surface mesurée, avec un grand côté limité à 3 840 pixels ; près de la taille d’origine, " +
        "le filtre peut ne produire aucun changement. Il est neutralisé en comparaison ou sous protection HDR, " +
        "et attend une surface connue. Décoche pour revenir au redimensionnement habituel. " +
        "Compare la netteté et la fluidité : un coût graphique supplémentaire ne garantit pas une meilleure image."

    const val ZOOM = "Agrandit l’affichage de 1,00× à 4,00×. Les boutons ajoutent ou retirent 0,25×, " +
        "avec arrêt aux bornes. Au-delà de 1,00×, des bords sortent de la surface visible. " +
        "Décoche d’abord la protection du cadre ; elle maintient le zoom à 1,00×. " +
        "« Recentrer / réinitialiser » rétablit le zoom 1,00× et annule les déplacements, " +
        "sans remettre à zéro le contraste, la luminosité de l’image ou le mode de cadrage."

    const val PAN = "Après avoir désactivé la protection et augmenté le zoom, utilise les quatre boutons pour " +
        "déplacer l’image dans la direction indiquée. Chaque appui déplace l’affichage de 40 unités de " +
        "taille Android indépendantes de la densité, dans les limites permises par le zoom et la surface. " +
        "Au zoom 1,00× ou avec la protection active, le déplacement est nul. " +
        "« Recentrer / réinitialiser » annule ces déplacements et rétablit le zoom 1,00×."

    const val SCREEN_BRIGHTNESS = "Règle la luminosité demandée pour la fenêtre de l’application, indépendamment " +
        "de la correction des pixels de la vidéo. Les boutons ajoutent ou retirent 10 points sur une échelle " +
        "bornée de 1 à 100 %. Depuis le réglage système, le premier appui prend 50 % comme point de départ : " +
        "plus donne 60 % et moins donne 40 %. Ce départ n’est pas une mesure de la luminosité actuelle. " +
        "La valeur est une consigne Android, pas une mesure en nits ni un pourcentage de lumière émise."

    const val SCREEN_SYSTEM = "Appuie pour supprimer la consigne de luminosité propre à cette fenêtre et reprendre " +
        "le réglage préféré du système. Celui-ci peut être manuel ou adaptatif selon tes réglages Android. " +
        "Ce bouton n’active pas à lui seul la luminosité adaptative et ne change pas la correction de luminosité de l’image."

    const val CORRECTION_RESET = "Appuie pour demander ensemble un contraste de 0,00 et une luminosité de l’image de 0,00. " +
        "Cette remise à zéro concerne uniquement ces deux corrections et reste soumise aux protections courantes. " +
        "Elle conserve la case de comparaison, le cadrage, les marges, Lanczos, le zoom et la luminosité de l’écran."

    const val OBSERVATION = "Les valeurs demandées sont les réglages acceptés par le lecteur. " +
        "La dernière soumission indique les valeurs du dernier plan transmis au moteur pour cette ouverture. " +
        "Elle peut différer de la demande pendant le regroupement des changements, la comparaison ou la protection HDR. " +
        "L’absence de soumission connue n’est pas une mesure de zéro. " +
        "Un envoi au moteur ne prouve pas l’effet final sur les pixels. " +
        "Actualiser l’état consulte ces informations sans modifier les réglages."
}
