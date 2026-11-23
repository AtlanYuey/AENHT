import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Downloads ncnn's prebuilt Android libraries (super-resolution in the
 * reader, src/main/cpp) and checks them against a known SHA-256.
 */
abstract class FetchNcnn : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputDirectory abstract val destination: DirectoryProperty

    @TaskAction
    fun fetch() {
        val dir = destination.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        val zip = File(dir, "ncnn.zip")
        URI(url.get()).toURL().openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
        val digest = MessageDigest.getInstance("SHA-256")
        zip.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        check(hex == sha256.get()) { "ncnn download has SHA-256 $hex, expected ${sha256.get()}" }
        ZipInputStream(zip.inputStream().buffered()).use { entries ->
            while (true) {
                val entry = entries.nextEntry ?: break
                val target = File(dir, entry.name).canonicalFile
                check(target.path.startsWith(dir.canonicalPath)) { "bad path in ncnn zip: ${entry.name}" }
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile.mkdirs()
                    target.outputStream().use { entries.copyTo(it) }
                }
            }
        }
        zip.delete()
    }
}

val ncnnVersion = "20261020"
val ncnnDir = layout.buildDirectory.dir("ncnn")
val fetchNcnn = tasks.register<FetchNcnn>("fetchNcnn") {
    url.set("https://github.com/Tencent/ncnn/releases/download/$ncnnVersion/ncnn-$ncnnVersion-android-vulkan-shared.zip")
    sha256.set("1b01048590564bae1c3d2d2a2b37ff101d50ad86c2cd2dc862bce7087905444d")
    destination.set(ncnnDir)
}
tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) dependsOn(fetchNcnn)
}

// Release signing from the environment (CI secrets); unsigned otherwise.
val keystorePath: String? = System.getenv("ANDROID_KEYSTORE_PATH")

/** The reviewed screenshots to compare with (-PscreenshotsVerify=<folder>), or null: record them. */
val screenshotsGolden: String? = (project.findProperty("screenshotsVerify") as String?)?.let { value ->
    require(value.isNotBlank() && value != "true") { "Name the folder of reviewed screenshots: -PscreenshotsVerify=<folder>" }
    value
}

android {
    namespace = "com.atlanyuey.aenht"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.atlanyuey.ENHentai"
        // Android 10: what the app lock prompt and the system bars need is there from it on.
        minSdk = 29
        targetSdk = 37
        versionCode = 10
        versionName = "0.2.4"
        // Super-resolution's native code (ncnn): ARM phones, 64 and 32 bit.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DNCNN_ROOT=${ncnnDir.get().asFile.absolutePath}/ncnn-$ncnnVersion-android-vulkan-shared",
                    "-DANDROID_STL=c++_static",
                )
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    androidResources {
        localeFilters += listOf(
            "en", "ja", "b+zh+Hans", "b+zh+Hant", "ko", "es", "fr", "de", "it", "pt-rBR", "ru", "vi", "th", "in",
        )
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Not shrunk: the release is the debug app's code as tested, without
            // R8 renaming the classes that Ktor, Room and the native code use.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            vcsInfo.include = false
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += arrayOf(
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "META-INF/*.version",
                "META-INF/**/LICENSE*.txt",
            )
        }
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    lint {
        checkReleaseBuilds = false
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Screenshots are written to build/screens (the default). With
                // -PscreenshotsVerify=<folder> they are compared with the reviewed
                // set in that folder instead (see the tasks below), and a
                // difference, or a missing screenshot, fails the test.
                if (screenshotsGolden != null) {
                    it.systemProperty("roborazzi.test.verify", "true")
                } else {
                    it.systemProperty("roborazzi.test.record", "true")
                }
                // Where Robolectric downloads Android (a mirror can be set locally).
                (project.findProperty("robolectricRepo") as String?)?.let { repo ->
                    it.systemProperty("robolectric.dependency.repo.url", repo)
                }
                it.maxHeapSize = "4g"
                it.jvmArgs(
                    "--add-exports", "java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens", "java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens", "java.base/java.io=ALL-UNNAMED",
                )
            }
        }
    }
}

// Screenshot verification: the reviewed screenshots are an input of the test
// task (a changed one runs the tests again, never "up to date"), copied into
// build/screens where each test compares what it draws with its own.
if (screenshotsGolden != null) {
    val golden = file(screenshotsGolden)
    val prepareScreenshotGolden by tasks.registering(Sync::class) {
        doFirst {
            require(golden.isDirectory && !golden.listFiles().isNullOrEmpty()) {
                "No reviewed screenshots in $golden: record them first (a run without -PscreenshotsVerify) and keep the folder."
            }
        }
        from(golden) { include("*.png") }
        into(layout.buildDirectory.dir("screens"))
    }
    tasks.withType<Test>().configureEach {
        dependsOn(prepareScreenshotGolden)
        inputs.dir(golden).withPropertyName("reviewedScreenshots")
        outputs.upToDateWhen { false }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.backdrop)
    implementation(libs.kyant.shapes)
    implementation(libs.telephoto.zoomable.coil3)
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Screenshot tests (Robolectric native graphics + Roborazzi).
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.coil.test)
}
