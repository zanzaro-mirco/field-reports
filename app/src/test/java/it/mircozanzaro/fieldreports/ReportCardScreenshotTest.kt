package it.mircozanzaro.fieldreports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import it.mircozanzaro.fieldreports.domain.Report
import it.mircozanzaro.fieldreports.domain.ReportStatus
import it.mircozanzaro.fieldreports.ui.ReportCard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot di riferimento della riga della lista.
 *
 * Gli altri test verificano che i testi giusti siano presenti; nessuno di loro
 * si accorge se la card perde la spaziatura, cambia gerarchia tipografica o
 * vede sparire una riga sotto il bordo. Un'immagine di riferimento sì — ed è
 * l'unico modo per far fallire la pipeline su una regressione puramente
 * visiva.
 *
 * Girano con Roborazzi su Robolectric, quindi sulla JVM: `./gradlew
 * recordRoborazziDebug` rigenera le immagini, `verifyRoborazziDebug` le
 * confronta. È quest'ultimo che la CI esegue.
 *
 * Le tre condizioni fotografate sono i tre stati di lavorazione, perché è
 * l'unica parte della card che cambia da sola. La larghezza è fissata e il
 * tema è esplicito: uno screenshot che dipende dalla dimensione dello schermo
 * o dalle impostazioni della macchina fallisce a caso, e un test che fallisce
 * a caso viene disattivato entro una settimana.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-normal-mdpi")
class ReportCardScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun report(status: ReportStatus) = Report(
        id = "R-1041",
        title = "Sostituzione contatore trifase",
        customer = "Acquedotto Nord",
        status = status,
        createdAtEpochMs = 1_753_600_000_000,
        technician = "M. Rossi",
    )

    private fun fotografa(status: ReportStatus, nome: String) {
        compose.setContent {
            MaterialTheme {
                ReportCard(
                    report = report(status),
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.background)
                        .width(360.dp)
                        .padding(12.dp),
                )
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$nome.png")
    }

    @Test
    fun `card di un rapporto aperto`() {
        fotografa(ReportStatus.OPEN, "report-card-aperto")
    }

    @Test
    fun `card di un rapporto in corso`() {
        fotografa(ReportStatus.IN_PROGRESS, "report-card-in-corso")
    }

    @Test
    fun `card di un rapporto chiuso`() {
        fotografa(ReportStatus.CLOSED, "report-card-chiuso")
    }
}
