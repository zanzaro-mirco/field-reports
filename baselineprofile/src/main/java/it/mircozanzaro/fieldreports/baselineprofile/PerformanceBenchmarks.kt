package it.mircozanzaro.fieldreports.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Avvio a freddo e scorrimento della lista, ognuno misurato due volte.
 *
 * - **Senza compilazione** (`CompilationMode.None`): ART interpreta tutto e
 *   compila solo ciò che scalda strada facendo. Non è un caso di laboratorio: è
 *   esattamente l'app appena installata da un APK scaricato da una Release, che
 *   non riceve i profili dal cloud come quelle del Play Store.
 * - **Con il Baseline Profile** (`Partial` con `Require`): il codice del profilo
 *   è compilato prima. `Require` e non `UseIfAvailable`, perché se il profilo
 *   mancasse la misura deve fallire, non dare in silenzio il numero di prima.
 *
 * Si lancia con
 * `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class PerformanceBenchmarks {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupWithoutCompilation() = startup(CompilationMode.None())

    @Test
    fun startupWithBaselineProfile() = startup(withProfile())

    @Test
    fun scrollWithoutCompilation() = scroll(CompilationMode.None())

    @Test
    fun scrollWithBaselineProfile() = scroll(withProfile())

    private fun withProfile() = CompilationMode.Partial(BaselineProfileMode.Require)

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
    }

    private fun scroll(mode: CompilationMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = mode,
        iterations = ITERATIONS,
        // Ogni giro parte da un processo nuovo: altrimenti dal secondo in poi il
        // codice dello scorrimento sarebbe già caldo, e la differenza fra le due
        // modalità sparirebbe per una ragione che non ha a che fare con il profilo.
        //
        // A mano e non con `startupMode = COLD`, che era la prima versione:
        // Macrobenchmark chiude il processo *dopo* questo blocco, prima di quello
        // misurato, e lo scorrimento cercava una lista che non c'era più.
        setupBlock = {
            killProcess()
            dropShaderCache()
            startActivityAndWait()
            waitForReports()
        },
    ) {
        scrollReports()
    }

    private companion object {
        const val ITERATIONS = 10
    }
}
