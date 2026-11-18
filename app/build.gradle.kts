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
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            if (providers.gradleProperty("testRelease").isPresent) proguardFiles("proguard-test-release.pro")
        }
    }

    // ./gradlew connectedReleaseAndroidTest -Pminify -PtestRelease runs instrumented tests against the shrunk build.
    testBuildType = if (providers.gradleProperty("testRelease").isPresent) "release" else "debug"

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

    // Claude Code bridge: MCP server over Streamable HTTP.
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.15.0")
    implementation("io.ktor:ktor-server-cio:3.5.2")

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.modelcontextprotocol:kotlin-sdk-client:0.15.0")
    testImplementation("io.ktor:ktor-client-cio:3.5.2")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
