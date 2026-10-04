import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Порядковый номер сборки: берётся из CI (GITHUB_RUN_NUMBER / BUILD_NUMBER), локально = 1
val buildNumber: Int = (System.getenv("BUILD_NUMBER") ?: System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val baseVersionName = "1.0"

// Параметры подписи: из keystore.properties (локально) или из переменных окружения (CI)
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

// Пароль/алиас ключа, который лежит в репозитории (он не секретный по своей сути).
val DEFAULT_STORE_PASSWORD = "adblok"
val DEFAULT_KEY_ALIAS = "adblok"

fun signingValue(key: String, env: String): String? =
    (keystoreProps.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

android {
    namespace = "com.adblok.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.adblok.app"
        minSdk = 23
        targetSdk = 34
        versionCode = buildNumber
        versionName = "$baseVersionName.$buildNumber"
        resValue("string", "build_number", buildNumber.toString())
    }

    signingConfigs {
        create("release") {
            // Приоритет: keystore.properties -> секреты CI -> постоянный ключ в репозитории.
            // Последний вариант гарантирует, что ЛЮБАЯ сборка подписана одним и тем же ключом,
            // и обновление APK ставится поверх предыдущего без ошибки "конфликтует с другим пакетом".
            val storePath = signingValue("storeFile", "KEYSTORE_FILE")
                ?: rootProject.file("keystore/adblok-release.p12").takeIf { it.exists() }?.absolutePath
            if (storePath != null && file(storePath).exists()) {
                storeFile = file(storePath)
                storeType = if (storePath.endsWith(".p12")) "PKCS12" else "JKS"
                storePassword = signingValue("storePassword", "KEYSTORE_PASSWORD") ?: DEFAULT_STORE_PASSWORD
                keyAlias = signingValue("keyAlias", "KEY_ALIAS") ?: DEFAULT_KEY_ALIAS
                keyPassword = signingValue("keyPassword", "KEY_PASSWORD") ?: DEFAULT_STORE_PASSWORD
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val rc = signingConfigs.getByName("release")
            signingConfig = if (rc.storeFile != null) rc else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "ADBlok-${variant.buildType.name}-build${buildNumber}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
