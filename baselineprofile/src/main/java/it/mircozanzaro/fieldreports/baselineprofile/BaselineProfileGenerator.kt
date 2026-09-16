package it.mircozanzaro.fieldreports.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Genera il Baseline Profile: l'elenco di classi e metodi che ART deve compilare
 * prima ancora che l'app parta, invece di interpretarli la prima volta.
 *
 * Si lancia con `./gradlew :app:generateBaselineProfile` e un telefono collegato
 * con Android 13 o successivo; il risultato finisce in
 * `app/src/release/generated/baselineProfiles/` ed è versionato.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /**
     * Tutto il percorso: avvio, scorrimento, un dettaglio aperto e chiuso. È il
     * Baseline Profile, che ART usa per compilare in anticipo.
     */
    @Test
    fun generate() = rule.collect(packageName = TARGET_PACKAGE) {
        pressHome()
        startActivityAndWait()
        waitForReports()
        scrollReports()
        openAReport()
    }

    /**
     * Solo l'avvio, fino alla lista. È il profilo di avvio, che R8 usa per
     * mettere vicine nel file dex le classi che servono prima del primo
     * fotogramma. Nella prima versione stava dentro il percorso completo, e ne
     * usciva identico al Baseline Profile: lo scorrimento non è avvio.
     */
    @Test
    fun startup() = rule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        waitForReports()
    }
}
