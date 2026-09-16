// Il modulo che guida l'app su un telefono vero: genera il Baseline Profile e
// misura avvio a freddo e scorrimento, prima e dopo.
//
// È un modulo di test a sé, e non un sorgente `androidTest` dell'app, perché
// deve misurare l'APK di rilascio così com'è: stesso R8, stessa firma, nessuna
// libreria di test dentro il processo misurato.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "it.mircozanzaro.fieldreports.baselineprofile"
    compileSdk = 34

    defaultConfig {
        // Vale per il modulo che misura, non per l'app, che resta alla 24.
        minSdk = 28
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

baselineProfile {
    // Il telefono collegato, non un dispositivo gestito da Gradle: un emulatore
    // misura il computer che lo ospita, e il numero da scrivere è quello di un
    // telefono.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
