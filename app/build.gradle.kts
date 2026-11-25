import com.google.gson.Gson
import com.google.gson.JsonObject
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.orange.universal"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.orange.universal"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // If you plan to enable ProGuard, then make sure to add @Keep on the main class. Otherwise, Xed-Editor won't be able to find it.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        // Should match with Xed-Editor
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
}

kotlin { jvmToolchain(21) }

// Always try to match the versions of library to the versions used in Xed-Editor
dependencies {
    // Xed-Editor extension SDK, required to interact with the application, do NOT remove
    compileOnly(files("libs/sdk.jar"))

    // If a library is used in Xed-Editor and your extension is common, then you should use compileOnly. Otherwise, it slows down the app.
    compileOnly(libs.androidx.appcompat)
    compileOnly(libs.material)
    compileOnly(libs.androidx.constraintlayout)
    compileOnly(libs.androidx.navigation.fragment)
    compileOnly(libs.androidx.navigation.ui)
    compileOnly(libs.androidx.navigation.fragment.ktx)
    compileOnly(libs.androidx.navigation.ui.ktx)
    compileOnly(libs.androidx.activity)
    compileOnly(libs.androidx.lifecycle.viewmodel)
    compileOnly(libs.androidx.lifecycle.runtime)
    compileOnly(libs.androidx.activity.compose)
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.compose.ui)
    compileOnly(libs.androidx.compose.ui.graphics)
    compileOnly(libs.androidx.compose.material3)
    compileOnly(libs.androidx.navigation.compose)
    compileOnly(libs.utilcode)
    compileOnly(libs.coil.compose)
    compileOnly(libs.gson)
    compileOnly(libs.commons.net)
    compileOnly(libs.okhttp)
    compileOnly(libs.material.motion.compose)
    compileOnly(libs.nanohttpd)
    compileOnly(libs.photoview)
    compileOnly(libs.glide)
    compileOnly(libs.androidx.browser)
    compileOnly(libs.anrwatchdog)
    compileOnly(libs.lsp4j)
    compileOnly(libs.kotlin.reflect)
    compileOnly(libs.androidx.documentfile)
    compileOnly(libs.compose.dnd)
    compileOnly(libs.androidx.compose.material.icons.core)
    compileOnly(libs.pine.core)
    compileOnly(libs.androidx.lifecycle.process)
    compileOnly(libs.androidsvg.aar)
}

//  ---------------- Below is the code for building the extension --------------------

val GITHUB_OWNER = "Xed-Editor"
val GITHUB_REPO = "Xed-Editor"
val TAG_NAME = "sdk-latest"
val ASSET_NAME = "sdk.jar"

val API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/tags/$TAG_NAME"
val DOWNLOAD_URL =
    "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/$TAG_NAME/$ASSET_NAME"

val timestampFile = project.layout.buildDirectory.file("sdk_updated_at.txt")
val outputFile = project.layout.projectDirectory.file("libs/$ASSET_NAME")

tasks.register<DefaultTask>("downloadLatestJar") {
    outputs.upToDateWhen { false }
    description = "Downloads the Xed-Editor SDK JAR from GitHub."
    group = "build"

    outputs.file(outputFile)
    outputs.file(timestampFile)

    doLast {
        outputFile.asFile.parentFile.mkdirs()
        timestampFile.get().asFile.parentFile.mkdirs()

        // GitHub's unauthenticated API can return HTTP 403 when its shared
        // rate limit is exhausted. The release asset URL is independent of
        // the API, so API failure must not prevent a normal build.
        val remoteUpdatedAt = try {
            val connection = URI.create(API_URL).toURL().openConnection()
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            val json = connection.getInputStream().bufferedReader().use { it.readText() }
            val release = Gson().fromJson(json, JsonObject::class.java)
            release.get("updated_at")?.asString
        } catch (e: Exception) {
            logger.warn("Could not read SDK release metadata (possibly GitHub API rate limit). Will download the SDK asset directly.")
            null
        }

        val storedUpdatedAt = if (timestampFile.get().asFile.exists()) {
            timestampFile.get().asFile.readText().trim()
        } else {
            null
        }

        if (remoteUpdatedAt != null && remoteUpdatedAt == storedUpdatedAt && outputFile.asFile.exists()) {
            println("✅ $ASSET_NAME is up to date (Timestamp: $remoteUpdatedAt).")
            return@doLast
        }

        println("Downloading $ASSET_NAME from the GitHub release asset...")

        val tempFile = File(outputFile.asFile.parentFile, "$ASSET_NAME.download")
        try {
            val connection = URI.create(DOWNLOAD_URL).toURL().openConnection()
            connection.connectTimeout = 20000
            connection.readTimeout = 30000
            connection.getInputStream().use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            if (!tempFile.exists() || tempFile.length() == 0L) {
                throw GradleException("Downloaded $ASSET_NAME is empty.")
            }
            if (!tempFile.renameTo(outputFile.asFile)) {
                outputFile.asFile.writeBytes(tempFile.readBytes())
                tempFile.delete()
            }
            if (remoteUpdatedAt != null) {
                timestampFile.get().asFile.writeText(remoteUpdatedAt)
            }
            println("Successfully downloaded $ASSET_NAME to ${outputFile.asFile.path}")
        } catch (e: Exception) {
            tempFile.delete()
            logger.error("Failed to download JAR from $DOWNLOAD_URL", e)
            throw GradleException("SDK JAR download failed. Check the GitHub release asset URL.", e)
        }
    }
}

tasks.register<Delete>("cleanApkOutputs") {
    description = "Clears all generated files and subdirectories from the build/outputs/APK folder."
    group = "cleanup"
    delete(layout.buildDirectory.dir("outputs/apk"))
}

tasks.named("preBuild").configure {
    dependsOn("cleanApkOutputs")
    dependsOn("downloadLatestJar")
}

// Shared config for the extension zip, parameterized by variant so debug and release
// each get their own task that also cleans the "output" folder and pulls in the right APK.
fun registerPackageTask(taskName: String, assembleTaskName: String) {
    tasks.register<Zip>(taskName) {
        outputs.upToDateWhen { false }
        description = "Assembles ($assembleTaskName) and archives the extension into a single ZIP file."
        group = "build"

        // Ensures a single gradlew invocation builds the APK first, then packages it.
        dependsOn(assembleTaskName)

        val apkDir = layout.buildDirectory.dir("outputs/apk")

        doFirst {
            val outputDir = File(rootDir, "output")
            if (outputDir.exists()) {
                outputDir.deleteRecursively()
            }

            outputDir.mkdirs()

            val apkFiles = apkDir.get().asFile.walk().filter { it.extension == "apk" }.toList()
            if (apkFiles.size > 1) {
                throw GradleException("Multiple APK files detected. This build system cannot handle multiple APK files")
            }
            if (apkFiles.isEmpty()) {
                throw GradleException("No APK files found")
            }
        }

        val manifest = File(rootDir, "manifest.json")

        if (!manifest.exists()) {
            throw GradleException("No manifest.json file not found")
        }

        val manifestJson: JsonObject by lazy {
            val text = manifest.readText()
            Gson().fromJson(text, JsonObject::class.java)
        }

        val extensionId: String by lazy {
            manifestJson.get("id").asString
        }

        val iconFile = File(rootDir, "icon.png")
        val readmeFile = File(rootDir, "README.md")
        val changelogFile = File(rootDir, "CHANGELOG.md")
        val filesDir = File(rootDir, "files")

        if (!iconFile.exists()) {
            logger.warn("WARNING: No icon.png file found. It is recommended to include an icon so your extension has a recognizable visual identity.")
        }

        if (!readmeFile.exists()) {
            logger.warn("WARNING: No README.md file found. It is recommended to include one to document your extension, its features, and usage instructions.")
        }

        if (!changelogFile.exists()) {
            logger.warn("WARNING: No CHANGELOG.md file found. It is recommended to include one to help users track changes between releases.")
        }

        archiveFileName.set("$extensionId.xed")

        from(apkDir) {
            include("**/*.apk")
            into("")
            eachFile { path = name }
        }
        includeEmptyDirs = false
        from(manifest) { into("") }
        from(iconFile) { into("") }
        from(readmeFile) { into("") }
        from(changelogFile) { into("") }
        from(filesDir) { into("files") }

        destinationDirectory.set(File(rootDir, "output"))
    }
}

registerPackageTask("buildExtensionRelease", "assembleRelease")
registerPackageTask("buildExtensionDebug", "assembleDebug")