plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ytaudio.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ytaudio.app"
        minSdk = 29
        targetSdk = 34
        val vc = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        versionCode = vc
        versionName = "2.$vc"
        // يُملأ تلقائياً على GitHub Actions (owner/repo)، ويُستخدم لفحص التحديثات
        buildConfigField("String", "UPDATE_REPO", "\"${System.getenv("GITHUB_REPOSITORY") ?: ""}\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildFeatures { buildConfig = true }

    // توقيع ثابت حتى يُثبَّت كل تحديث فوق النسخة السابقة
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("ytaudio.jks")
            storeType = "pkcs12"
            storePassword = "ytaudio-pass"
            keyAlias = "ytaudio"
            keyPassword = "ytaudio-pass"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST",
                "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/*.kotlin_module", "/META-INF/{AL2.0,LGPL2.1}"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.work:work-runtime:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
