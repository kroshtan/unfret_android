plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "net.unfret.android"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "net.unfret.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.0"
        // The Google Play build sells nothing and points nowhere to buy (Storefront); every other build is the direct
        // download's, with "Get checks".
        buildConfigField("boolean", "PLAY", "false")
        // "Support Unfret" (the donate and support links' words): the direct download only. The Play build has it
        // empty, so the words aren't in its APK at all (no resource, no code).
        buildConfigField("String", "SUPPORT_LABEL", "\"Support Unfret\"")
        // Unfret Dev (the `dev` build type): its own app next to Unfret, talking to dev.unfret.net only.
        buildConfigField("boolean", "DEV", "false")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No commit in the APK: it would differ between a checkout with git and one without, and the build must
            // be the same byte for byte wherever it runs. The release names its commit.
            vcsInfo.include = false
            // No signing config: Gradle builds the release unsigned, and scripts/sign-release.sh signs it with
            // apksigner (docs/RELEASING.md), so the key never reaches Gradle.
        }
        // The Google Play build: the release, with no price, purchase button or link to the checkout (Google Play's
        // payments policy; Storefront), and src/play/res's words without them. Built as the app bundle for Play,
        // `bundlePlay` (docs/RELEASING.md). A build type rather than a flavor, so the direct download's tasks and
        // files (assembleRelease, app-release-unsigned.apk, the debug ones) keep their names.
        create("play") {
            initWith(getByName("release"))
            buildConfigField("boolean", "PLAY", "true")
            buildConfigField("String", "SUPPORT_LABEL", "\"\"")
            vcsInfo.include = false
            matchingFallbacks += listOf("release")
        }
        // Unfret Dev: the release, as its own app (net.unfret.android.dev, "Unfret Dev", src/dev/res) that talks to
        // https://dev.unfret.net only, built on every push to main and signed with the separate dev key, never the
        // release key (scripts/sign-dev.sh; docs/RELEASING.md, "Dev builds"). Published on dev.unfret.net/android only.
        create("dev") {
            initWith(getByName("release"))
            applicationIdSuffix = ".dev"
            buildConfigField("boolean", "DEV", "true")
            vcsInfo.include = false
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = true
        lintConfig = file("lint.xml")
    }

    // No dependency metadata block in the APK (it is only readable by Google Play, and this app is also
    // distributed as a direct download).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
    }
}

// Unfret Dev's version: <versionName>-dev.<n> and versionCode n, n being CI's run number (UNFRET_DEV_BUILD), so each
// dev build installs over the one before. Its own package, so it never competes with Play's or the beta's versionCode.
androidComponents {
    onVariants(selector().withBuildType("dev")) { variant ->
        val run = providers.environmentVariable("UNFRET_DEV_BUILD").orElse("1").map { it.toInt().coerceAtLeast(1) }
        val name = android.defaultConfig.versionName
        variant.outputs.forEach { output ->
            output.versionCode.set(run)
            output.versionName.set(run.map { "$name-dev.$it" })
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    testImplementation(libs.junit)
    // Android's org.json is a stub in local unit tests; the real one stands in for it.
    testImplementation(libs.json)
}
