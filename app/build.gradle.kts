plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
// Generate a synthetic demo; never upload a personal film.
val demoResources = layout.buildDirectory.dir("generated/demo-res")
val demoFile = demoResources.map { it.file("raw/demonstration.mkv") }
val demoSubtitles = rootProject.file("examples/demonstration.srt")
val generateDemo = tasks.register<Exec>("generateDemo") {
    inputs.file(demoSubtitles); outputs.file(demoFile)
    doFirst { demoFile.get().asFile.parentFile.mkdirs() }
    commandLine("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", "color=c=0x162033:s=640x360:r=12:d=28",
        "-f", "lavfi", "-i", "sine=frequency=220:sample_rate=48000:duration=28",
        "-i", demoSubtitles.absolutePath, "-map", "0:v", "-map", "1:a", "-map", "2:s",
        "-c:v", "libx264", "-preset", "ultrafast", "-crf", "30", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "32k", "-af", "volume=0.08", "-c:s", "srt",
        "-metadata:s:s:0", "language=fra", "-t", "28", demoFile.get().asFile.absolutePath)
}
android {
    namespace = "fr.soustitresvoix.player"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "fr.soustitresvoix.player.cinema"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0-cinema-test"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets.getByName("main").res.srcDir(demoResources)
    androidResources { noCompress += "mkv" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    lint { abortOnError = true; checkReleaseBuilds = true }
}
tasks.named("preBuild") { dependsOn(generateDemo) }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
