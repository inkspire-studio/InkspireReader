
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotzilla)
    alias(libs.plugins.google.services)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    signingConfigs {
        create("release") {
            val path = keystoreProperties["storeFile"] as String
            storeFile = rootProject.file(path)
            storePassword = keystoreProperties["storePassword"] as String
            keyAlias = keystoreProperties["keyAlias"] as String
            keyPassword = keystoreProperties["keyPassword"] as String
        }
    }
//    androidResources {
//        generateLocaleConfig = true
//    }

    namespace = "com.inkspire.ebookreader"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.inkspire.ebookreader"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "1.0.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isMinifyEnabled = false
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.collections.immutable)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.adaptive)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.foundation)

    implementation(libs.firebase.analytics.ktx)

    implementation(libs.androidx.palette.ktx)
    implementation(libs.lottie.compose)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.sqlite.bundled)
    ksp(libs.androidx.room.compiler)
    annotationProcessor(libs.androidx.room.compiler)

    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.viewmodel.lifecycle)

    implementation(libs.androidx.media)
    implementation(libs.androidx.media3.common.ktx)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.exoplayer)

    implementation(libs.moko.permissions.compose)
    implementation(libs.moko.permissions.notification)

    implementation(libs.kotzilla.sdk)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.koin.compose)
    implementation(libs.koin.compose.viewmodel)

    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.readium.streamer)
    implementation(libs.readium.shared)
    implementation(libs.readium.navigator)
    implementation(libs.readium.opds)
    implementation(libs.readium.lcp)
    implementation(libs.siegmann.epublib) {
        exclude(group = "org.slf4j")
        exclude(group = "xmlpull")
    }
    implementation(libs.pdfbox.android)

    implementation(libs.kotlinx.serialization.json)

    implementation(libs.slf4j.android)
    implementation(libs.jsoup)

    implementation(libs.coil.compose)

    implementation(libs.haze.materials)
    implementation(libs.richeditor.compose) {
        exclude(group = "org.jetbrains.compose.material", module = "material")
        exclude(group = "org.jetbrains.compose.material3", module = "material3")
    }

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.configureEach {
    if (name.startsWith("ksp") && name.endsWith("Kotlin")) {
        dependsOn("generateKotzillaConfig")
    }
}