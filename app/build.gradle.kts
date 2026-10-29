plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val versionCodeOverride = providers.gradleProperty("versionCode").orNull?.toIntOrNull()
val versionNameOverride = providers.gradleProperty("versionName").orNull

android {
    namespace = "com.linuxwff.pigapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.linuxwff.pigapp"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeOverride ?: 1
        versionName = versionNameOverride ?: "0.1.0"
    }

    signingConfigs {
        val releaseStore = System.getenv("PIG_RELEASE_STORE_FILE")
        if (!releaseStore.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = System.getenv("PIG_RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("PIG_RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("PIG_RELEASE_KEY_PASSWORD")
                storeType = "PKCS12"
            }
        }

        create("ci") {
            storeFile = rootProject.file("keystore/ci-debug.p12")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("ci")
        }

        release {
            // A store key comes from PIG_RELEASE_STORE_FILE. CI performance
            // builds may opt into the committed debug key with
            // -PallowCiSigning=true; a distribution build without a real key
            // must fail instead of silently shipping a publicly-signable APK.
            // The check runs at task-graph time so debug/unit-test builds still
            // configure normally without a release key.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("ci")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    val hasReleaseKey = signingConfigs.findByName("release") != null
    val allowCiSigning = providers.gradleProperty("allowCiSigning").orNull == "true"
    gradle.taskGraph.whenReady {
        val releaseRequested = allTasks.any {
            it.name == "assembleRelease" || it.name == "bundleRelease" ||
                it.name == "packageRelease"
        }
        if (releaseRequested && !hasReleaseKey && !allowCiSigning) {
            throw GradleException(
                "PIG_RELEASE_STORE_FILE is not set; refusing to sign a release with the " +
                    "committed CI key (pass -PallowCiSigning=true for a local performance build)",
            )
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.26.0")
    implementation("org.jetbrains:markdown:0.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // WebDAV needs PROPFIND/MKCOL, which HttpURLConnection rejects outright.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
