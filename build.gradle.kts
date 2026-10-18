plugins {
    alias(libs.plugins.android.application) apply false
    // Non viene applicato da nessun modulo: AGP 9 compila il Kotlin da sé.
    // Resta qui perché dichiararlo mette KGP sul classpath alla versione del
    // catalogo, invece della 2.2.10 che AGP porta con sé.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
}
