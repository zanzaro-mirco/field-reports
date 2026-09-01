package it.mircozanzaro.fieldreports

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import it.mircozanzaro.fieldreports.domain.DomainError
import it.mircozanzaro.fieldreports.domain.Report
import it.mircozanzaro.fieldreports.domain.ReportStatus
import it.mircozanzaro.fieldreports.ui.ItalianErrorTextProvider
import it.mircozanzaro.fieldreports.ui.ReportsScreen
import it.mircozanzaro.fieldreports.ui.ReportsUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Test della schermata, senza ViewModel e senza emulatore.
 *
 * `ReportsScreen` riceve uno stato e risale eventi: è esattamente ciò che lo
 * state hoisting doveva rendere possibile, e questi test sono la prova che la
 * separazione regge. Non c'è un repository, non c'è una coroutine, non c'è un
 * dispositivo — si passa uno stato costruito a mano e si guarda cosa compare.
 *
 * Girano con Robolectric nel sorgente `test`, quindi in `./gradlew test` e
 * nella pipeline. Metterli in `androidTest` avrebbe significato scriverli e non
 * eseguirli mai, perché la CI non ha un emulatore.
 */
@RunWith(RobolectricTestRunner::class)
class ReportsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val errorText = ItalianErrorTextProvider()::textFor

    private fun report(
        id: String,
        titolo: String = "Sostituzione contatore",
        status: ReportStatus = ReportStatus.OPEN,
    ) = Report(
        id = id,
        title = titolo,
        customer = "Acquedotto Nord",
        status = status,
        createdAtEpochMs = 1_753_600_000_000,
        technician = "M. Rossi",
    )

    /** Monta la schermata su uno stato fisso, ignorando gli eventi. */
    private fun mostra(state: ReportsUiState) {
        compose.setContent {
            ReportsScreen(
                state = state,
                onRefresh = {},
                onFilterChange = {},
                errorText = errorText,
            )
        }
    }

    @Test
    fun `il caricamento mostra l'indicatore e nessuna lista`() {
        mostra(ReportsUiState.Loading)

        compose.onNodeWithTag("loading").assertIsDisplayed()
        compose.onNodeWithTag("report-list").assertDoesNotExist()
    }

    @Test
    fun `da caricamento a lista l'indicatore sparisce e compaiono i rapporti`() {
        // Il passaggio, non i due stati separati: è la transizione che l'utente
        // vede, ed è dove si annidano gli errori di ricomposizione.
        var state: ReportsUiState by mutableStateOf(ReportsUiState.Loading)
        compose.setContent {
            ReportsScreen(
                state = state,
                onRefresh = {},
                onFilterChange = {},
                errorText = errorText,
            )
        }

        compose.onNodeWithTag("loading").assertIsDisplayed()

        state = ReportsUiState.Ready(listOf(report("R-1041")))

        compose.onNodeWithTag("loading").assertDoesNotExist()
        compose.onNodeWithTag("report-list").assertIsDisplayed()
        compose.onNodeWithText("Sostituzione contatore").assertIsDisplayed()
    }

    @Test
    fun `un errore senza dati mostra il messaggio tradotto`() {
        mostra(ReportsUiState.Error(DomainError.Network))

        compose.onNodeWithTag("error").assertIsDisplayed()
        compose.onNodeWithText("Nessuna connessione. Controlla la rete e riprova.")
            .assertIsDisplayed()
    }

    @Test
    fun `offline con dati in cache si vedono lista e avviso insieme`() {
        // È il criterio della cache locale, verificato a schermo: l'errore non
        // sostituisce i rapporti, ci si aggiunge sopra.
        mostra(
            ReportsUiState.Ready(
                reports = listOf(report("R-1041")),
                refreshError = DomainError.Network,
            ),
        )

        compose.onNodeWithTag("offline-banner").assertIsDisplayed()
        compose.onNodeWithTag("report-list").assertIsDisplayed()
        compose.onNodeWithText("Sostituzione contatore").assertIsDisplayed()
        compose.onNodeWithTag("error").assertDoesNotExist()
    }

    @Test
    fun `senza aggiornamento in corso non c'e' l'indicatore di refresh`() {
        mostra(ReportsUiState.Ready(listOf(report("R-1041"))))

        compose.onNodeWithTag("refreshing").assertDoesNotExist()
    }

    @Test
    fun `durante l'aggiornamento l'indicatore compare sopra i dati`() {
        mostra(
            ReportsUiState.Ready(
                reports = listOf(report("R-1041")),
                isRefreshing = true,
            ),
        )

        compose.onNodeWithTag("refreshing").assertIsDisplayed()
        compose.onNodeWithTag("report-list").assertIsDisplayed()
    }

    @Test
    fun `una lista vuota lo dice invece di restare bianca`() {
        mostra(ReportsUiState.Ready(reports = emptyList()))

        compose.onNodeWithText("Nessun rapporto").assertIsDisplayed()
        compose.onNodeWithTag("report-list").assertDoesNotExist()
    }

    @Test
    fun `il filtro mostra solo i rapporti dello stato scelto`() {
        mostra(
            ReportsUiState.Ready(
                reports = listOf(
                    report("R-1", titolo = "Aperto", status = ReportStatus.OPEN),
                    report("R-2", titolo = "Chiuso", status = ReportStatus.CLOSED),
                ),
                filter = ReportStatus.CLOSED,
            ),
        )

        compose.onNodeWithText("Chiuso").assertIsDisplayed()
        compose.onNodeWithText("Aperto").assertDoesNotExist()
    }

    // --- gli eventi risalgono, non vengono gestiti qui -----------------------

    @Test
    fun `il pulsante aggiorna risale l'evento`() {
        var richieste = 0
        compose.setContent {
            ReportsScreen(
                state = ReportsUiState.Ready(listOf(report("R-1041"))),
                onRefresh = { richieste++ },
                onFilterChange = {},
                errorText = errorText,
            )
        }

        compose.onNodeWithTag("refresh").performClick()

        assertEquals(1, richieste)
    }

    @Test
    fun `toccare un chip risale lo stato scelto`() {
        var scelto: ReportStatus? = null
        compose.setContent {
            ReportsScreen(
                state = ReportsUiState.Ready(listOf(report("R-1041"))),
                onRefresh = {},
                onFilterChange = { scelto = it },
                errorText = errorText,
            )
        }

        compose.onNodeWithText("Chiusi").performClick()

        assertEquals(ReportStatus.CLOSED, scelto)
    }

    @Test
    fun `toccare il chip gia' attivo rimuove il filtro`() {
        // Il comportamento non ovvio: il chip fa da interruttore. Senza test,
        // è il genere di dettaglio che si perde alla prima rifattorizzazione.
        var scelto: ReportStatus? = ReportStatus.CLOSED
        compose.setContent {
            ReportsScreen(
                state = ReportsUiState.Ready(
                    reports = listOf(report("R-1041")),
                    filter = ReportStatus.CLOSED,
                ),
                onRefresh = {},
                onFilterChange = { scelto = it },
                errorText = errorText,
            )
        }

        compose.onNodeWithText("Chiusi").performClick()

        assertEquals(null, scelto)
    }
}
