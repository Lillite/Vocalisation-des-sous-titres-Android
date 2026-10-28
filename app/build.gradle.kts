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
android {
    namespace = "fr.soustitresvoix.player"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "fr.soustitresvoix.player.translation"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.5.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets.getByName("main").res.srcDir(demoResources)
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
tasks.named("preBuild") { dependsOn(generateDemo) }
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
