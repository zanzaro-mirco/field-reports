import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

// La chiave di firma non sta nel repository, e non ci sta nemmeno il suo
// percorso. Arriva da `keystore.properties` quando si compila a mano, oppure
// dalle variabili d'ambiente che la pipeline riempie dai segreti del
// repository. `keystore.properties.esempio` accanto dice quali valori servono.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun signingValue(property: String, variable: String): String? =
    keystoreProperties.getProperty(property) ?: System.getenv(variable)

val releaseStore: String? = signingValue("storeFile", "KEYSTORE_PATH")

// Il numero di versione arriva dal tag che ha fatto partire il rilascio, e non
// e' scritto a mano qui: due posti che dichiarano la versione sono due posti
// che prima o poi si contraddicono. I valori predefiniti servono a tutte le
// compilazioni che non sono un rilascio.
val appVersionName: String = providers.gradleProperty("appVersionName").getOrElse("0.1.0")
val appVersionCode: Int = providers.gradleProperty("appVersionCode").getOrElse("1").toInt()

android {
    namespace = "it.mircozanzaro.fieldreports"
    compileSdk = 34

    defaultConfig {
        applicationId = "it.mircozanzaro.fieldreports"
        minSdk = 24
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Dichiarata solo se la chiave c'e' davvero: una configurazione con i
        // campi vuoti fallirebbe con un messaggio di Gradle invece che con uno
        // che spiega cosa manca.
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Senza la chiave si ripiega sulla firma di debug, perche' chi
            // clona il repository deve poter compilare in rilascio senza avere
            // una chiave che e' mia. Il rischio del ripiego - un APK firmato di
            // debug che finisce in una Release credendolo buono - non e'
            // lasciato al caso: la pipeline legge il certificato dell'APK prima
            // di pubblicarlo, e si ferma se trova quello di debug.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")

            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // `minSdk` è 24 e `java.time` richiede la 26. Il desugaring lo rende
        // disponibile comunque: l'alternativa era scrivere a mano un parser
        // ISO-8601 per le date che arrivano dall'API — fragile, e da testare.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // Serve a Robolectric: i test del DAO girano sulla JVM ma hanno bisogno
        // delle risorse Android impacchettate.
        unitTests.isIncludeAndroidResources = true
    }
}

// Lo schema del database viene esportato e versionato: è il riferimento rispetto
// a cui si scriveranno le migrazioni quando esisterà una versione 2.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx)
    // Dichiarato anche se Retrofit lo porterebbe comunque: la composition root
    // usa `OkHttpClient` e `MediaType` in prima persona, e appoggiarsi a una
    // dipendenza transitiva per tipi che si scrivono nel proprio codice
    // significa rompersi il giorno in cui quella transitiva cambia.
    implementation(libs.okhttp)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}
