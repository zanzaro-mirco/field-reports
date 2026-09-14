package it.mircozanzaro.fieldreports

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import it.mircozanzaro.fieldreports.ui.ReportDetailScreen
import it.mircozanzaro.fieldreports.ui.ReportDetailUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId

/**
 * Test della schermata di dettaglio, montata su stati costruiti a mano come
 * `ReportsScreenTest` fa per la lista.
 *
 * Il fuso orario è fissato: una data scritta nel test dipende da dove gira, e la
 * CI non gira a Roma.
 */
@RunWith(RobolectricTestRunner::class)
class ReportDetailScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val rome: ZoneId = ZoneId.of("Europe/Rome")

    private fun show(state: ReportDetailUiState, onBack: () -> Unit = {}) {
        compose.setContent {
            ReportDetailScreen(state = state, onBack = onBack, zoneId = rome)
        }
    }

    @Test
    fun `mostra il rapporto, con la data e la descrizione`() {
        show(
            ReportDetailUiState.Ready(
                sampleReport(
                    id = "#1041",
                    title = "Sostituzione contatore trifase",
                    description = "Il contatore segna consumi anche a impianto spento.",
                    createdAtEpochMs = 1_785_139_200_000, // 2026-07-27T08:00:00Z
                ),
            ),
        )

        compose.onNodeWithTag("detail-title").assertTextEquals("Sostituzione contatore trifase")
        compose.onNodeWithText("#1041 · Aperto").assertIsDisplayed()
        compose.onNodeWithText("27 luglio 2026, 10:00").assertIsDisplayed()
        compose.onNodeWithText("Il contatore segna consumi anche a impianto spento.")
            .assertIsDisplayed()
    }

    @Test
    fun `senza descrizione e senza data lo dice, invece di inventare`() {
        // La data a zero è il segnale del mapper per "illeggibile": formattata
        // direbbe 1 gennaio 1970.
        show(ReportDetailUiState.Ready(sampleReport("#1045", createdAtEpochMs = 0)))

        compose.onNodeWithText("Data non disponibile").assertIsDisplayed()
        compose.onNodeWithText("Nessuna descrizione").assertIsDisplayed()
        compose.onNodeWithText("1970", substring = true).assertDoesNotExist()
    }

    @Test
    fun `un rapporto che non c'e' resta sullo schermo e spiega`() {
        show(ReportDetailUiState.NotFound)

        compose.onNodeWithTag("not-found").assertIsDisplayed()
        compose.onNodeWithTag("report-detail").assertDoesNotExist()
    }

    @Test
    fun `il pulsante indietro risale l'evento`() {
        var backs = 0
        show(ReportDetailUiState.Ready(sampleReport("R-1")), onBack = { backs++ })

        compose.onNodeWithTag("back").performClick()

        assertEquals(1, backs)
    }
}
