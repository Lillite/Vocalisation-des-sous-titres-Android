package fr.soustitresvoix.player

import org.json.JSONObject

/** Only fixed application-owned codes and bounded numeric measurements may cross the model process boundary. */
class LocalGgufDiagnostic private constructor(
    val stage: Stage,
    val reason: Reason,
    val elapsedMs: Long,
    val openClStatus: Int?,
    val directPlatforms: Int,
    val icdPlatforms: Int,
    val gpuDevices: Int,
    val probeCached: Boolean,
    val missingSymbol: String? = null
) {
    enum class Stage(val label: String) {
        BACKEND("préparation du moteur"),
        OPENCL_DRIVER("accès au pilote OpenCL"),
        OPENCL_DIRECT("interrogation directe du pilote"),
        OPENCL_ICD("interrogation par le chargeur ICD"),
        OPENCL_DEVICE("détection du périphérique de calcul"),
        MODEL_LOAD("chargement des poids du modèle"),
        CONTEXT_INIT("création du contexte de calcul"),
        GENERATION("calcul du texte")
    }

    enum class Reason(val label: String) {
        BACKEND_NOT_COMPILED("Le backend OpenCL n’est pas compilé pour cette architecture de l’application."),
        CPU_BACKEND_MISSING("Le backend CPU requis n’a pas été trouvé."),
        DRIVER_NOT_ACCESSIBLE("La bibliothèque du pilote OpenCL n’est pas accessible à l’application."),
        ENTRY_POINTS_MISSING("Les fonctions requises du pilote OpenCL sont absentes."),
        DIRECT_SYMBOLS_MISSING("Une ou plusieurs fonctions OpenCL nécessaires au calcul direct sont absentes du pilote."),
        DIRECT_QUERY_FAILED("L’interrogation directe des plateformes du pilote a échoué."),
        DIRECT_NO_PLATFORM("L’interrogation directe du pilote n’a trouvé aucune plateforme OpenCL."),
        ICD_ENTRY_POINT_MISSING("Le pilote ne fournit pas la fonction de plateforme attendue par le chargeur ICD."),
        ICD_CONFIGURATION_FAILED("La configuration locale du chargeur ICD a échoué."),
        ICD_QUERY_FAILED("Le chargeur ICD n’a pas pu interroger les plateformes OpenCL."),
        ICD_NO_PLATFORM("Le chargeur ICD n’a trouvé aucune plateforme OpenCL utilisable."),
        REGISTRATION_FAILED("L’enregistrement du backend OpenCL a échoué."),
        NO_SUPPORTED_GPU("Aucun GPU pris en charge n’a été exposé par le backend OpenCL."),
        DEVICE_NOT_REGISTERED("Le périphérique OpenCL n’a pas été retrouvé dans le registre des moteurs."),
        MODEL_LOAD_FAILED("Le chargement des poids avec le moteur sélectionné a échoué ; la cause précise n’est pas établie."),
        CONTEXT_INIT_FAILED("La création du contexte avec le moteur sélectionné a échoué ; la cause précise n’est pas établie."),
        NO_GPU_EXECUTION("Aucun calcul GPU effectif n’a été observé pendant cet appel."),
        ALLOCATION_FAILED("Une allocation de mémoire a échoué pendant cette étape."),
        UNEXPECTED_FAILURE("Une erreur interne a interrompu cette étape ; sa cause précise n’est pas établie.")
    }

    fun render(): String = buildString {
        append("Diagnostic local — étape : "); append(stage.label)
        append(" ["); append(stage.name); append("]\n")
        append(reason.label); append(" ["); append(reason.name); append("]\n")
        if (reason == Reason.DIRECT_SYMBOLS_MISSING) missingSymbol?.let {
            append("Fonction OpenCL requise absente : "); append(it); append(".\n")
        }
        append("Temps natif observé avant ce diagnostic : "); append(elapsedMs); append(" ms.")
        openClStatus?.let { append("\nCode OpenCL observé : "); append(it); append('.') }
        if (directPlatforms >= 0) { append("\nPlateformes du pilote, accès direct : "); append(directPlatforms); append('.') }
        if (icdPlatforms >= 0) { append("\nPlateformes exposées par le chargeur ICD : "); append(icdPlatforms); append('.') }
        if (gpuDevices >= 0) { append("\nPériphériques GPU pris en charge observés : "); append(gpuDevices); append('.') }
        if (probeCached) append("\nRésultat de la détection déjà mémorisé dans le processus ; cette consultation ne relance pas la détection du pilote.")
    }

    internal fun toJson(): JSONObject = JSONObject()
        .put("stage", stage.name).put("reason", reason.name).put("elapsedMs", elapsedMs)
        .put("openClStatus", openClStatus ?: JSONObject.NULL)
        .put("directPlatforms", directPlatforms).put("icdPlatforms", icdPlatforms)
        .put("gpuDevices", gpuDevices).put("probeCached", probeCached)
        .apply { if (reason == Reason.DIRECT_SYMBOLS_MISSING) missingSymbol?.let { put("missingSymbol", it) } }

    override fun toString(): String = "LocalGgufDiagnostic(stage=${stage.name}, reason=${reason.name})"

    companion object {
        // Exact required subset of generate_direct_opencl.py's pinned table.
        // Optional functions do not fail binding and cannot be a missing-symbol diagnosis.
        private val REQUIRED_DIRECT_SYMBOLS = setOf(
            "clBuildProgram", "clCreateBuffer", "clCreateCommandQueue", "clCreateContext", "clCreateImage",
            "clCreateKernel", "clCreateProgramWithSource", "clCreateSubBuffer", "clEnqueueBarrierWithWaitList",
            "clEnqueueCopyBuffer", "clEnqueueFillBuffer", "clEnqueueMapBuffer", "clEnqueueMarkerWithWaitList",
            "clEnqueueNDRangeKernel", "clEnqueueReadBuffer", "clEnqueueUnmapMemObject", "clEnqueueWriteBuffer",
            "clFinish", "clFlush", "clGetDeviceIDs", "clGetDeviceInfo", "clGetKernelWorkGroupInfo",
            "clGetPlatformIDs", "clGetPlatformInfo", "clGetProgramBuildInfo", "clReleaseEvent",
            "clReleaseKernel", "clReleaseMemObject", "clReleaseProgram", "clSetKernelArg", "clWaitForEvents")

        /** A malformed optional diagnostic is discarded; its untrusted contents never reach an error message. */
        internal fun parse(value: JSONObject?): LocalGgufDiagnostic? {
            value ?: return null
            return runCatching {
                val stage = Stage.valueOf(value.get("stage") as? String ?: return null)
                val reason = Reason.valueOf(value.get("reason") as? String ?: return null)
                val elapsed = integer(value, "elapsedMs", 0, 86_400_000) ?: return null
                val status = if (value.has("openClStatus") && value.isNull("openClStatus")) null
                    else (integer(value, "openClStatus", Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()) ?: return null).toInt()
                val direct = integer(value, "directPlatforms", -1, 65_535) ?: return null
                val icd = integer(value, "icdPlatforms", -1, 65_535) ?: return null
                val devices = integer(value, "gpuDevices", -1, 65_535) ?: return null
                val cached = value.get("probeCached") as? Boolean ?: return null
                val missing = if (reason == Reason.DIRECT_SYMBOLS_MISSING && value.has("missingSymbol") &&
                    !value.isNull("missingSymbol")) {
                    (value.get("missingSymbol") as? String)?.takeIf { it in REQUIRED_DIRECT_SYMBOLS } ?: return null
                } else null
                LocalGgufDiagnostic(stage, reason, elapsed, status, direct.toInt(), icd.toInt(), devices.toInt(), cached, missing)
            }.getOrNull()
        }

        private fun integer(value: JSONObject, key: String, min: Long, max: Long): Long? {
            val number = value.opt(key)
            val result = when (number) {
                is Int -> number.toLong()
                is Long -> number
                else -> return null
            }
            return result.takeIf { it in min..max }
        }
    }
}
