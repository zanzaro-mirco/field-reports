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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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

    private class FakeReportsRepository(
        var result: Outcome<List<Report>> = Outcome.Success(emptyList()),
    ) : ReportsRepository {
        var callCount: Int = 0
        override suspend fun loadReports(): Outcome<List<Report>> {
            callCount++
            return result
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
        assertEquals(0, repo.callCount)
    }

    @Test
    fun `start carica i rapporti una sola volta`() = runTest(dispatcher) {
        val repo = FakeReportsRepository(
            Outcome.Success(listOf(report("R-1", ReportStatus.OPEN, 100))),
        )
        val viewModel = ReportsViewModel(repo)

        viewModel.uiState.test {
            assertEquals(ReportsUiState.Loading, awaitItem())
            viewModel.start()
            viewModel.start() // idempotente
            val ready = awaitItem() as ReportsUiState.Ready
            assertEquals(1, ready.reports.size)
            assertEquals(1, repo.callCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `un errore di rete arriva alla UI come DomainError`() = runTest(dispatcher) {
        val repo = FakeReportsRepository(Outcome.Failure(DomainError.Network))
        val viewModel = ReportsViewModel(repo)

        viewModel.uiState.test {
            assertEquals(ReportsUiState.Loading, awaitItem())
            viewModel.start()
            val error = awaitItem() as ReportsUiState.Error
            assertEquals(DomainError.Network, error.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `il filtro non rifa la chiamata`() = runTest(dispatcher) {
        val repo = FakeReportsRepository(
            Outcome.Success(
                listOf(
                    report("R-1", ReportStatus.OPEN, 100),
                    report("R-2", ReportStatus.CLOSED, 200),
                ),
            ),
        )
        val viewModel = ReportsViewModel(repo)

        viewModel.uiState.test {
            awaitItem() // Loading
            viewModel.start()
            awaitItem() // Ready

            viewModel.setFilter(ReportStatus.CLOSED)
            val filtered = awaitItem() as ReportsUiState.Ready

            assertEquals(1, filtered.visibleReports.size)
            assertEquals("R-2", filtered.visibleReports.first().id)
            assertEquals(2, filtered.reports.size) // i dati non si perdono
            assertEquals(1, repo.callCount)        // nessuna nuova chiamata
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `rimuovere il filtro mostra di nuovo tutti i rapporti`() = runTest(dispatcher) {
        val repo = FakeReportsRepository(
            Outcome.Success(
                listOf(
                    report("R-1", ReportStatus.OPEN, 100),
                    report("R-2", ReportStatus.CLOSED, 200),
                ),
            ),
        )
        val viewModel = ReportsViewModel(repo)

        viewModel.uiState.test {
            awaitItem()
            viewModel.start()
            awaitItem()
            viewModel.setFilter(ReportStatus.OPEN)
            awaitItem()
            viewModel.setFilter(null)
            val all = awaitItem() as ReportsUiState.Ready
            assertEquals(2, all.visibleReports.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh ripete la chiamata`() = runTest(dispatcher) {
        val repo = FakeReportsRepository(
            Outcome.Success(listOf(report("R-1", ReportStatus.OPEN, 100))),
        )
        val viewModel = ReportsViewModel(repo)

        viewModel.uiState.test {
            awaitItem()
            viewModel.start()
            awaitItem()
            viewModel.refresh()
            assertEquals(ReportsUiState.Loading, awaitItem())
            assertTrue(awaitItem() is ReportsUiState.Ready)
            assertEquals(2, repo.callCount)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
