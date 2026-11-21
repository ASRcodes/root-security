import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.gms.google.services)
}

val releaseCredentials = Properties().apply {
    val credentialsFile = rootProject.file("keystore.properties")
    if (credentialsFile.isFile) credentialsFile.inputStream().use { load(it) }
}
fun signingValue(name: String, environment: String): String? =
    providers.environmentVariable(environment).orNull?.takeIf { it.isNotBlank() }
        ?: releaseCredentials.getProperty(name)?.takeIf { it.isNotBlank() }

val releaseStore = signingValue("storeFile", "DMM_KEYSTORE_FILE")
val releaseStorePassword = signingValue("storePassword", "DMM_KEYSTORE_PASSWORD")
val releaseAlias = signingValue("keyAlias", "DMM_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "DMM_KEY_PASSWORD")
val releaseSigningReady = listOf(releaseStore, releaseStorePassword, releaseAlias, releaseKeyPassword).all { it != null }

android {
    namespace = "com.asrcodes.dontmissmom"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.asrcodes.dontmissmom"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "3.2-final"

        testInstrumentationRunner = if (providers.gradleProperty("rootSecurityLiveChecks").orNull == "true")
            "com.asrcodes.dontmissmom.RootSecurityLiveRunner"
        else "com.asrcodes.dontmissmom.DmmTestRunner"
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("upload") {
                storeFile = rootProject.file(releaseStore!!)
                storePassword = releaseStorePassword
                keyAlias = releaseAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("upload")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
    buildFeatures { buildConfig = true }
    bundle { language { enableSplit = false } }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

// Never label an unsigned bundle as ready for Play. Credentials stay outside source control.
val requireReleaseSigning by tasks.registering {
    doLast {
        check(releaseSigningReady) {
            "Release signing is missing. Fill the ignored keystore.properties file or DMM_KEYSTORE_FILE, DMM_KEYSTORE_PASSWORD, DMM_KEY_ALIAS and DMM_KEY_PASSWORD environment variables with the existing upload key."
        }
        check(rootProject.file(releaseStore!!).isFile) { "The configured upload keystore does not exist." }
    }
}
tasks.matching { it.name == "signReleaseBundle" }.configureEach { dependsOn(requireReleaseSigning) }

dependencies {
    implementation(project(":protection"))
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    constraints {
        // Firebase brings DataStore transitively. 1.2.1 fixes the native library's
        // RELRO alignment for devices using 16 KB memory pages.
        implementation("androidx.datastore:datastore-preferences:1.2.1")
    }

    // --- Core Android ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    // --- UI ---
    implementation(libs.material)
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.8.6")
    implementation("androidx.lifecycle:lifecycle-livedata:2.8.6")

    // --- Firebase Setup ---
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))

    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation("com.google.firebase:firebase-messaging")

    // Google account sign-in
    implementation("com.google.android.gms:play-services-auth:20.7.0")

    implementation(libs.play.services.location)

    // --- Testing ---
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
