package it.mircozanzaro.fieldreports

import app.cash.turbine.test
import it.mircozanzaro.fieldreports.domain.DomainError
import it.mircozanzaro.fieldreports.domain.Outcome
import it.mircozanzaro.fieldreports.domain.Report
import it.mircozanzaro.fieldreports.domain.ReportStatus
import it.mircozanzaro.fieldreports.domain.ReportsRepository
import it.mircozanzaro.fieldreports.ui.ReportsUiState
import it.mircozanzaro.fieldreports.ui.ReportsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Test del ViewModel senza Android, senza rete e senza attese reali.
 *
 * Il doppio è un fake del **repository**, non dell'API: dopo l'introduzione
 * dell'interfaccia di dominio si può sostituire il livello giusto, invece di
 * raggiungerlo passando da quello sottostante.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    /**
     * Repository finto con una cache dentro.
     *
     * Riproduce il comportamento che conta: la lettura è un flusso, la
     * sincronizzazione è un'operazione separata che scrive in quel flusso solo
     * se riesce. La latenza non è cosmetica — una chiamata di rete impiega del
     * tempo, e senza quel tempo lo stato "aggiornamento in corso" non
     * esisterebbe mai abbastanza a lungo da poter essere osservato. È tempo
     * virtuale: la suite resta istantanea.
     */
    private class FakeReportsRepository(
        initialReports: List<Report> = emptyList(),
    ) : ReportsRepository {

        private val reports = MutableStateFlow(initialReports)

        /** Cosa troverà in cache un aggiornamento riuscito. */
        var fetched: List<Report> = initialReports

        /** Esito della prossima sincronizzazione. */
        var outcome: Outcome<Unit> = Outcome.Success(Unit)

        var refreshCount: Int = 0
        var refreshIfStaleCount: Int = 0
        val syncCount: Int get() = refreshCount + refreshIfStaleCount

        override fun observeReports(): Flow<List<Report>> = reports.asStateFlow()

        override suspend fun refresh(): Outcome<Unit> {
            refreshCount++
            return sync()
        }

        override suspend fun refreshIfStale(): Outcome<Unit> {
            refreshIfStaleCount++
            return sync()
        }

        private suspend fun sync(): Outcome<Unit> {
            delay(LATENCY_MS)
            if (outcome is Outcome.Success) reports.value = fetched
            return outcome
        }

        companion object {
            const val LATENCY_MS: Long = 100
        }
    }

    private fun report(id: String, status: ReportStatus, createdAt: Long) = Report(
        id = id,
        title = "Intervento $id",
        customer = "Cliente",
        status = status,
        createdAtEpochMs = createdAt,
        technician = "M. Z.",
    )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `lo stato iniziale e Loading e nulla parte senza start`() = runTest(dispatcher) {
        val repo = FakeReportsRepository()
        val viewModel = ReportsViewModel(repo)

        assertEquals(ReportsUiState.Loading, viewModel.uiState.value)
        assertEquals(0, repo.syncCount)
    }

    @Test
    fun `start osserva la cache e sincronizza una sola volta`() = runTest(dispatcher) {
        val repo = FakeReportsRepository()
        repo.fetched = listOf(report("R-1", ReportStatus.OPEN, 100))
        val viewModel = ReportsViewModel(repo)

        viewModel.uiState.test {
            assertEquals(ReportsUiState.Loading, awaitItem())
            viewModel.start()
            viewModel.start() // idempotente
            val ready = awaitItem() as ReportsUiState.Ready
            assertEquals(1, ready.reports.size)
            assertEquals(1, repo.syncCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `all'avvio si sincronizza solo se i dati sono vecchi`() = runTest(dispatcher) {
        // La decisione di scaricare o no appartiene al repository, che conosce
        // la data dell'ultima sincronizzazione. Il ViewModel si limita a
        // dichiarare l'intenzione: "aggiorna se serve".
        val repo = FakeReportsRepository()
        ReportsViewModel(repo).start()
        advanceUntilIdle()

        assertEquals(1, repo.refreshIfStaleCount)
        assertEquals(0, repo.refreshCount)
    }

    @Test
    fun `senza nulla in cache un errore di rete riempie lo schermo`() = runTest(dispatcher) {
        val repo = FakeReportsRepository()
        repo.outcome = Outcome.Failure(DomainError.Network)
        val viewModel = ReportsViewModel(repo)

        viewModel.start()
        advanceUntilIdle()

        val error = viewModel.uiState.value as ReportsUiState.Error
        assertEquals(DomainError.Network, error.error)
    }

    @Test
    fun `con dati in cache un errore di rete non nasconde i rapporti`() = runTest(dispatcher) {
        // È il criterio di fatto visto dalla presentazione: offline si vedono i
        // rapporti salvati, con un avviso sopra. Prima della cache questo caso
        // produceva una schermata di errore e basta.
        val repo = FakeReportsRepository(
            initialReports = listOf(report("R-1", ReportStatus.OPEN, 100)),
        )
        repo.outcome = Outcome.Failure(DomainError.Network)
        val viewModel = ReportsViewModel(repo)

        viewModel.start()
        advanceUntilIdle()

        val ready = viewModel.uiState.value as ReportsUiState.Ready
        assertEquals(1, ready.reports.size)
        assertEquals(DomainError.Network, ready.refreshError)
        assertFalse(ready.isRefreshing)
    }

    @Test
    fun `una cache vuota e una sincronizzazione riuscita non sono un errore`() =
        runTest(dispatcher) {
            val repo = FakeReportsRepository()
            val viewModel = ReportsViewModel(repo)

            viewModel.start()
            advanceUntilIdle()

            val ready = viewModel.uiState.value as ReportsUiState.Ready
            assertTrue(ready.isEmpty)
            assertNull(ready.refreshError)
        }

    @Test
    fun `durante un aggiornamento i dati restano a schermo`() = runTest(dispatcher) {
        val repo = FakeReportsRepository(
            initialReports = listOf(report("R-1", ReportStatus.OPEN, 100)),
        )
        val viewModel = ReportsViewModel(repo)
        viewModel.start()
        advanceUntilIdle()

        viewModel.refresh()
        runCurrent() // la chiamata è partita e sta aspettando la rete

        val refreshing = viewModel.uiState.value as ReportsUiState.Ready
        assertTrue(refreshing.isRefreshing)
        assertEquals(1, refreshing.reports.size)

        advanceUntilIdle()
        assertFalse((viewModel.uiState.value as ReportsUiState.Ready).isRefreshing)
    }

    @Test
    fun `un aggiornamento riuscito cancella l'errore precedente`() = runTest(dispatcher) {
        val repo = FakeReportsRepository(
            initialReports = listOf(report("R-1", ReportStatus.OPEN, 100)),
        )
        repo.outcome = Outcome.Failure(DomainError.Network)
        val viewModel = ReportsViewModel(repo)
        viewModel.start()
        advanceUntilIdle()
        assertEquals(
            DomainError.Network,
            (viewModel.uiState.value as ReportsUiState.Ready).refreshError,
        )

        repo.outcome = Outcome.Success(Unit)
        viewModel.refresh()
        advanceUntilIdle()

        assertNull((viewModel.uiState.value as ReportsUiState.Ready).refreshError)
    }

    @Test
    fun `il filtro non rifa la chiamata`() = runTest(dispatcher) {
        val repo = FakeReportsRepository(
            initialReports = listOf(
                report("R-1", ReportStatus.OPEN, 100),
                report("R-2", ReportStatus.CLOSED, 200),
            ),
        )
        val viewModel = ReportsViewModel(repo)
        viewModel.start()
        advanceUntilIdle()

        viewModel.setFilter(ReportStatus.CLOSED)
        advanceUntilIdle()

        val filtered = viewModel.uiState.value as ReportsUiState.Ready
        assertEquals(1, filtered.visibleReports.size)
        assertEquals("R-2", filtered.visibleReports.first().id)
        assertEquals(2, filtered.reports.size) // i dati non si perdono
        assertEquals(1, repo.syncCount)        // nessuna nuova chiamata
    }

    @Test
    fun `rimuovere il filtro mostra di nuovo tutti i rapporti`() = runTest(dispatcher) {
        val repo = FakeReportsRepository(
            initialReports = listOf(
                report("R-1", ReportStatus.OPEN, 100),
                report("R-2", ReportStatus.CLOSED, 200),
            ),
        )
        val viewModel = ReportsViewModel(repo)
        viewModel.start()
        advanceUntilIdle()

        viewModel.setFilter(ReportStatus.OPEN)
        advanceUntilIdle()
        viewModel.setFilter(null)
        advanceUntilIdle()

        assertEquals(2, (viewModel.uiState.value as ReportsUiState.Ready).visibleReports.size)
    }

    @Test
    fun `il filtro sopravvive a un aggiornamento della cache`() = runTest(dispatcher) {
        // La ragione per cui il filtro vive in un flusso suo e non dentro lo
        // stato: con una sorgente reattiva, ogni scrittura in cache ricostruisce
        // lo stato da capo. Se il filtro stesse lì dentro, un aggiornamento
        // arrivato dalla rete cancellerebbe la scelta dell'utente mentre sta
        // guardando la lista.
        val repo = FakeReportsRepository(
            initialReports = listOf(
                report("R-1", ReportStatus.OPEN, 100),
                report("R-2", ReportStatus.CLOSED, 200),
            ),
        )
        val viewModel = ReportsViewModel(repo)
        viewModel.start()
        advanceUntilIdle()
        viewModel.setFilter(ReportStatus.CLOSED)
        advanceUntilIdle()

        repo.fetched = listOf(
            report("R-1", ReportStatus.OPEN, 100),
            report("R-2", ReportStatus.CLOSED, 200),
            report("R-3", ReportStatus.CLOSED, 300),
        )
        viewModel.refresh()
        advanceUntilIdle()

        val ready = viewModel.uiState.value as ReportsUiState.Ready
        assertEquals(ReportStatus.CLOSED, ready.filter)
        assertEquals(3, ready.reports.size)
        assertEquals(2, ready.visibleReports.size)
    }

    @Test
    fun `refresh ripete la chiamata`() = runTest(dispatcher) {
        val repo = FakeReportsRepository(
            initialReports = listOf(report("R-1", ReportStatus.OPEN, 100)),
        )
        val viewModel = ReportsViewModel(repo)
        viewModel.start()
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(2, repo.syncCount)
        assertEquals(1, repo.refreshCount)
        assertTrue(viewModel.uiState.value is ReportsUiState.Ready)
    }
}
