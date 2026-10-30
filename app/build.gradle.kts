import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
// The release key and its passwords stay outside the source repository.
val releaseSigning = providers.environmentVariable("SUBTITRES_SIGNING_PROPERTIES").orNull?.let { location ->
    val propertiesFile = file(location).canonicalFile
    require(!propertiesFile.toPath().startsWith(rootDir.canonicalFile.toPath())) {
        "Release signing properties must be outside the repository."
    }
    Properties().apply { propertiesFile.inputStream().use { load(it) } }
}
// Generate only our synthetic demonstration, never a personal film.
val demoResources = layout.buildDirectory.dir("generated/demo-res")
val demoFile = demoResources.map { it.file("raw/demonstration.mkv") }
val demoSubtitles = rootProject.file("examples/demonstration.srt")
// Four corners, a circle and a grid make actual stretching/cropping observable.
val demoPattern = "geq=lum='if(between(hypot(X-W/2,Y-H/2),78,81),220,lum(X,Y))':cb='cb(X,Y)':cr='cr(X,Y)'," +
    "drawgrid=width=80:height=60:thickness=1:color=white@0.25," +
    "drawbox=x=0:y=0:w=iw:h=ih:color=white:t=2," +
    "drawbox=x=0:y=0:w=64:h=64:color=0xff0000:t=fill," +
    "drawbox=x=576:y=0:w=64:h=64:color=0x00ff00:t=fill," +
    "drawbox=x=0:y=296:w=64:h=64:color=0x0000ff:t=fill," +
    "drawbox=x=576:y=296:w=64:h=64:color=0xffff00:t=fill"
val generateDemo = tasks.register<Exec>("generateDemo") {
    inputs.file(demoSubtitles)
    inputs.property("pattern", demoPattern)
    inputs.property("deterministicEncoding", 1)
    outputs.file(demoFile)
    doFirst { demoFile.get().asFile.parentFile.mkdirs() }
    commandLine("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", "color=c=0x162033:s=640x360:r=12:d=28",
        "-f", "lavfi", "-i", "sine=frequency=220:sample_rate=48000:duration=28",
        "-i", demoSubtitles.absolutePath,
        "-map", "0:v", "-map", "1:a", "-map", "2:s",
        "-vf", demoPattern,
        "-c:v", "libx264", "-preset", "ultrafast", "-crf", "30", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "32k", "-af", "volume=0.08",
        "-c:s", "srt", "-metadata:s:s:0", "language=fra", "-t", "28",
        // Stable Matroska IDs and codec metadata allow comparing independently built APKs.
        "-fflags", "+bitexact", "-flags:v", "+bitexact", "-flags:a", "+bitexact",
        "-threads:v", "1", "-threads:a", "1",
        demoFile.get().asFile.absolutePath)
}
val diagnosticModel = demoResources.map { it.file("raw/diagnostic_model.gguf") }
val generateDiagnosticModel = tasks.register<Exec>("generateDiagnosticModel") {
    val generator = file("src/main/cpp/tests/make_tiny_gguf.py")
    inputs.file(generator)
    outputs.file(diagnosticModel)
    commandLine("python3", generator.absolutePath, diagnosticModel.get().asFile.absolutePath)
}
// The terminating model exercises real JNI in the test APK only. No trained
// weights or additional test model are shipped in the application APK.
val translationTestAssets = layout.buildDirectory.dir("generated/translation-test-assets")
val translationTestModel = translationTestAssets.map { it.file("translation_test_model.gguf") }
val generateTranslationTestModel = tasks.register<Exec>("generateTranslationTestModel") {
    val generator = file("src/main/cpp/tests/make_tiny_gguf.py")
    inputs.file(generator)
    inputs.property("terminating", true)
    inputs.property("roundtrip", true)
    outputs.file(translationTestModel)
    doFirst { translationTestModel.get().asFile.parentFile.mkdirs() }
    commandLine("python3", generator.absolutePath, translationTestModel.get().asFile.absolutePath, "--terminating", "--roundtrip")
}
// This second synthetic fixture exercises the model's own ChatML control
// tokens through the real chat-template JNI/IPC path. It is test-APK only.
val translationChatTestModel = translationTestAssets.map { it.file("translation_chat_test_model.gguf") }
val generateTranslationChatTestModel = tasks.register<Exec>("generateTranslationChatTestModel") {
    val generator = file("src/main/cpp/tests/make_tiny_gguf.py")
    inputs.file(generator)
    inputs.property("terminating", true)
    inputs.property("roundtrip", true)
    inputs.property("chatTemplate", "controls")
    outputs.file(translationChatTestModel)
    doFirst { translationChatTestModel.get().asFile.parentFile.mkdirs() }
    commandLine("python3", generator.absolutePath, translationChatTestModel.get().asFile.absolutePath,
        "--terminating", "--roundtrip", "--chat-template", "controls")
}
android {
    namespace = "fr.soustitresvoix.player"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "fr.soustitresvoix.player.translation"
        minSdk = 26
        targetSdk = 36
        versionCode = 19
        versionName = "0.5.12-runtime"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON" } }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    sourceSets.getByName("main").res.srcDir(demoResources)
    sourceSets.getByName("androidTest").assets.srcDir(translationTestAssets)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        if (releaseSigning != null) create("privateRelease") {
            fun required(name: String): String = requireNotNull(releaseSigning.getProperty(name)) {
                "Missing release signing property: $name"
            }
            val keyFile = file(required("storeFile")).canonicalFile
            require(!keyFile.toPath().startsWith(rootDir.canonicalFile.toPath())) {
                "The release key must be outside the repository."
            }
            storeFile = keyFile
            storeType = "PKCS12"
            storePassword = required("storePassword")
            keyAlias = required("keyAlias")
            keyPassword = required("keyPassword")
        }
    }
    buildTypes {
        release {
            // Git provenance is verified separately; PR merge SHA and final merge SHA differ.
            vcsInfo.include = false
            isMinifyEnabled = false
            if (releaseSigning != null) signingConfig = signingConfigs.getByName("privateRelease")
        }
    }
    testBuildType = if (providers.gradleProperty("verifyRelease").orNull == "true") "release" else "debug"
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint { abortOnError = true; checkReleaseBuilds = true }
}
tasks.named("preBuild") { dependsOn(generateDemo); dependsOn(generateDiagnosticModel) }
// AGP's legacy AndroidTest asset source set does not preserve a mapped task
// provider as its producer. All AndroidTest consumers, including lint model
// generation, must depend on this small deterministic test-only asset.
tasks.configureEach {
    if (name.contains("AndroidTest")) {
        dependsOn(generateTranslationTestModel)
        dependsOn(generateTranslationChatTestModel)
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation("androidx.activity:activity:1.11.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-effect:1.11.1")
    // Instrumentation exercises both debug and the exact release variant.
    implementation("androidx.tracing:tracing:1.3.0")
    implementation("com.google.mlkit:translate:17.0.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}

// Android 36 Robolectric runs on Java 21; Android production compilation remains Java 17.
val androidTestJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}
tasks.withType<Test>().configureEach {
    javaLauncher.set(androidTestJavaLauncher)
    maxHeapSize = "1536m"
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.net=ALL-UNNAMED",
        "--add-opens=java.base/java.security=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED"
    )
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    testLogging { events("passed", "failed", "skipped") }
}
