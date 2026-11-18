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
        versionCode = 1
        versionName = "0.1.0"
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
