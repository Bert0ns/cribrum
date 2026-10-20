import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "io.github.bert0ns.cribrum"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.bert0ns.cribrum"
        minSdk = 30 // Android 11: scoped storage rules and MANAGE_EXTERNAL_STORAGE (design §4, §9)
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The release workflow sets both from the version it publishes.
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("versionName").orNull ?: "0.1.0"
        // ONNX Runtime ships a 35 MB native library per ABI: only phones' is kept.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // The release key, which the release workflow writes from repository secrets. Without it a
        // release build comes out unsigned, and Android refuses to install it.
        providers.environmentVariable("CRIBRUM_KEYSTORE").orNull?.let { keystore ->
            create("release") {
                storeFile = file(keystore)
                storePassword = providers.environmentVariable("CRIBRUM_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("CRIBRUM_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("CRIBRUM_KEYSTORE_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true // Robolectric
        unitTests.all {
            // Robolectric reaches into FileDescriptor on SDK 37
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        // They say a newer release came out, which changes by the day, not with the code.
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
        // Cribrum is for phones, so the APK leaves out ChromeOS's x86_64 (abiFilters above).
        disable += "ChromeOsAbiSupport"
    }
}

// Ships prompt embeddings generated in the lab, without committing them here (issue #107).
val promptResourcesDir = layout.buildDirectory.dir("generated/resources/prompts")
val copyPromptEmbeddings = tasks.register<Copy>("copyPromptEmbeddings") {
    from(rootProject.file("../lab/src/cribrum_lab/prompt_embeddings.json"))
    into(promptResourcesDir)
}
android.sourceSets.getByName("main").resources.directories.add(promptResourcesDir.get().asFile.path)
tasks.named("preBuild") { dependsOn(copyPromptEmbeddings) }

room {
    schemaDirectory("$projectDir/schemas")
}

/** Downloads pinned files and checks their SHA-256, as lab/src/cribrum_lab/models.py does. */
abstract class DownloadModel : DefaultTask() {
    /** File name to "url sha256". */
    @get:Input
    abstract val files: MapProperty<String, String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun download() {
        for ((name, source) in files.get()) {
            val (url, sha256) = source.split(" ")
            val target = outputDirectory.file(name).get().asFile
            if (target.exists() && sha256(target) == sha256) continue
            target.parentFile.mkdirs()
            val partial = File(target.path + ".part")
            URI(url).toURL().openStream().use { input -> partial.outputStream().use(input::copyTo) }
            check(sha256(partial) == sha256) { "$url: SHA-256 mismatch" }
            partial.renameTo(target)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

// The MobileCLIP2-S0 image encoder, from the same revision and with the same SHA-256 as
// lab/src/cribrum_lab/mobileclip.py, with its license. Packed in the APK, never committed.
val downloadMobileClip = tasks.register<DownloadModel>("downloadMobileClip") {
    files.put(
        "mobileclip2_s0/vision_model.onnx",
        "https://huggingface.co/plhery/mobileclip2-onnx/resolve/ba95759a5bdbaca53e9111e2550a76ec09c8fd9e/onnx/s0/vision_model.onnx " +
            "13d20ebfa8a8f63890eb2727fe4dc63009ff970f43e0f7d9d2ed999659f70c8a",
    )
    files.put(
        "mobileclip2_s0/LICENSE",
        "https://huggingface.co/apple/MobileCLIP2-S0/resolve/3136ea51c8ed56b9f9abfab04cb816735aaad6cb/LICENSE " +
            "30c59d255d2b4075ba11b81ce5aa0109c28d28138d66d52f7f5d8cbba814df8b",
    )
    outputDirectory.set(layout.buildDirectory.dir("generated/mobileclip"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(downloadMobileClip, DownloadModel::outputDirectory)
    }
}

// Unit tests run the model on the JVM, which needs ONNX Runtime's desktop build instead.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.exifinterface)
    implementation(libs.cryptohash)
    implementation(libs.work.runtime)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coil.compose)
    implementation(libs.compose.material.icons)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.onnxruntime.android)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.work.testing)
    testImplementation(libs.onnxruntime.jvm)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
}
