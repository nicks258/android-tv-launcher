import java.util.Properties

plugins {
    id("com.android.application")
}

// No dependencies on purpose: framework Views only, to keep RAM and APK size minimal.
android {
    namespace = "dev.plume"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.plume"
        minSdk = 26
        targetSdk = 36
        versionCode = (project.findProperty("versionCode") as? String)?.toIntOrNull() ?: 8
        val verName = (project.findProperty("versionName") as? String) ?: "1.6"
        versionName = verName
        // What's new: only this version's section of CHANGELOG.md ships, one entry per line, ** marks kept for bold.
        // A ### heading ships as a line that starts with #.
        val baseVersion = verName.split('.').take(2).joinToString(".")
        val notes = rootProject.file("CHANGELOG.md").readText().replace("\r", "")
            .substringAfter("\n## $baseVersion\n", "").substringBefore("\n## ")
            .lines().filter { it.startsWith("- ") || it.startsWith("### ") }
            .map { if (it.startsWith("### ")) "#" + it.removePrefix("### ") else it.removePrefix("- ") }
        require(notes.isNotEmpty()) { "CHANGELOG.md has no entries under \"## $baseVersion\"" }
        resValue("string", "whats_new", notes.joinToString("\\n") { it.replace("'", "\\'").replace("\"", "\\\"") })
    }

    buildFeatures {
        resValues = true
    }

    // CI fails on new lint errors only; the ones already here are listed in lint-baseline.xml
    // (./gradlew updateLintBaseline rewrites it). Translations have their own check, so lag there isn't an error.
    lint {
        baseline = file("lint-baseline.xml")
        disable += setOf("MissingTranslation", "ExtraTranslation")
    }

    // Release key: uses keystore.properties if provided, or app/release.keystore bundled in repo,
    // falling back to debug key so anyone can build.
    val keys = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        create("release") {
            if (keys != null) {
                storeFile = file(keys.getProperty("storeFile"))
                storePassword = keys.getProperty("storePassword")
                keyAlias = keys.getProperty("keyAlias")
                keyPassword = keys.getProperty("keyPassword")
            } else if (file("release.keystore").exists()) {
                storeFile = file("release.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    // Kotlin's own metadata files (reflection and tooling data) aren't used at run time.
    packaging {
        resources {
            excludes += setOf("kotlin/**", "**/*.kotlin_builtins", "META-INF/*.kotlin_module", "META-INF/*.version", "DebugProbesKt.bin")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        release {
            vcsInfo.include = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
