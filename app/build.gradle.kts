plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.pocketagent"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.sovereignedge.pocketagent"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release signing reads from ~/.gradle/gradle.properties so keys and passwords never live in this repo:
    // pocketagent.storeFile, pocketagent.storePassword, pocketagent.keyAlias, pocketagent.keyPassword
    val releaseKeystore = providers.gradleProperty("pocketagent.storeFile").orNull
    if (releaseKeystore != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.gradleProperty("pocketagent.storePassword").get()
                keyAlias = providers.gradleProperty("pocketagent.keyAlias").get()
                keyPassword = providers.gradleProperty("pocketagent.keyPassword").get()
            }
        }
    }

    buildTypes {
        debug {
            // Test builds install alongside the signed release instead of clashing with its signature.
            applicationIdSuffix = ".debug"
        }
        release {
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
            // Experimental: ./gradlew assembleRelease -Pminify
            isMinifyEnabled = providers.gradleProperty("minify").isPresent
            isShrinkResources = providers.gradleProperty("minify").isPresent
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
}

dependencies {
    implementation("com.anthropic:anthropic-java:2.62.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.4")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.4.20")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
}
