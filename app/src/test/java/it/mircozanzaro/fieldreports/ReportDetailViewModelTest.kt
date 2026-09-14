package it.mircozanzaro.fieldreports

import app.cash.turbine.test
import it.mircozanzaro.fieldreports.data.local.InMemoryReportsLocalStore
import it.mircozanzaro.fieldreports.domain.Report
import it.mircozanzaro.fieldreports.ui.ReportDetailUiState
import it.mircozanzaro.fieldreports.ui.ReportDetailViewModel
import it.mircozanzaro.fieldreports.ui.ReportRemoved
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Test del ViewModel del dettaglio, e soprattutto del suo unico evento.
 *
 * Una rotazione, vista da qui, è un osservatore degli eventi che smette di
 * ascoltare e uno nuovo che comincia, mentre il ViewModel resta lo stesso. I
 * due test centrali riproducono i due modi in cui può andare storta: l'evento
 * ridato al nuovo osservatore, e l'evento emesso proprio nell'intervallo in cui
 * nessuno ascolta.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun cacheWith(vararg reports: Report) = CacheOnlyReportsRepository(
        InMemoryReportsLocalStore(reports.toList(), initialSyncEpochMs = 1),
    )

    @Test
    fun `lo stato iniziale e Loading e nulla parte senza start`() = runTest(dispatcher) {
        val viewModel = ReportDetailViewModel(cacheWith(sampleReport("R-1")), "R-1")
        advanceUntilIdle()

        assertEquals(ReportDetailUiState.Loading, viewModel.uiState.value)
    }

    @Test
    fun `un rapporto in cache arriva pronto`() = runTest(dispatcher) {
        val viewModel = ReportDetailViewModel(cacheWith(sampleReport("R-1")), "R-1")

        viewModel.start()
        advanceUntilIdle()

        val ready = viewModel.uiState.value as ReportDetailUiState.Ready
        assertEquals("R-1", ready.report.id)
    }

    @Test
    fun `un aggiornamento del rapporto si vede senza riaprire il dettaglio`() = runTest(dispatcher) {
        val repository = cacheWith(sampleReport("R-1", title = "Prima"))
        val viewModel = ReportDetailViewModel(repository, "R-1")
        viewModel.start()
        advanceUntilIdle()

        repository.store.replaceAll(listOf(sampleReport("R-1", title = "Dopo")), syncedAtEpochMs = 2)
        advanceUntilIdle()

        val ready = viewModel.uiState.value as ReportDetailUiState.Ready
        assertEquals("Dopo", ready.report.title)
    }

    @Test
    fun `un id che la cache non ha mai avuto e uno stato, non un evento`() = runTest(dispatcher) {
        // Non c'è niente da cui tornare indietro: lo schermo resta e spiega.
        val viewModel = ReportDetailViewModel(cacheWith(sampleReport("R-1")), "R-404")

        viewModel.start()
        advanceUntilIdle()

        assertEquals(ReportDetailUiState.NotFound, viewModel.uiState.value)
        viewModel.events.test { expectNoEvents() }
    }

    @Test
    fun `un rapporto che sparisce produce un evento, e lo stato resta sull'ultimo visto`() =
        runTest(dispatcher) {
            val repository = cacheWith(sampleReport("R-1"), sampleReport("R-2"))
            val viewModel = ReportDetailViewModel(repository, "R-2")
            viewModel.start()
            advanceUntilIdle()

            viewModel.events.test {
                repository.store.replaceAll(listOf(sampleReport("R-1")), syncedAtEpochMs = 2)

                assertEquals(ReportRemoved("R-2"), awaitItem())
            }
            // Durante l'animazione di uscita si vede ancora il rapporto, non un
            // "non trovato" che lampeggia mentre lo schermo se ne va.
            val ready = viewModel.uiState.value as ReportDetailUiState.Ready
            assertEquals("R-2", ready.report.id)
        }

    @Test
    fun `l'evento arriva una volta sola anche quando l'osservatore riparte`() = runTest(dispatcher) {
        // La rotazione dal primo lato: il vecchio osservatore ha già gestito
        // l'evento, e il nuovo non deve rivederlo. Uno StateFlow glielo
        // ridarebbe, e l'app tornerebbe indietro due volte.
        val repository = cacheWith(sampleReport("R-1"), sampleReport("R-2"))
        val viewModel = ReportDetailViewModel(repository, "R-2")
        viewModel.start()
        advanceUntilIdle()

        viewModel.events.test {
            repository.store.replaceAll(listOf(sampleReport("R-1")), syncedAtEpochMs = 2)
            assertEquals(ReportRemoved("R-2"), awaitItem())
        }

        viewModel.events.test { expectNoEvents() }
    }

    @Test
    fun `un evento emesso mentre nessuno ascolta non si perde`() = runTest(dispatcher) {
        // La rotazione dall'altro lato: l'evento nasce nell'istante in cui il
        // vecchio osservatore non c'è più e il nuovo non c'è ancora. Uno
        // SharedFlow senza replay lo butterebbe, e lo schermo resterebbe su un
        // rapporto che non esiste.
        val repository = cacheWith(sampleReport("R-1"), sampleReport("R-2"))
        val viewModel = ReportDetailViewModel(repository, "R-2")
        viewModel.start()
        advanceUntilIdle()

        repository.store.replaceAll(listOf(sampleReport("R-1")), syncedAtEpochMs = 2)
        advanceUntilIdle()

        viewModel.events.test {
            assertEquals(ReportRemoved("R-2"), awaitItem())
        }
    }

    @Test
    fun `le scritture successive in cache non ripetono l'evento`() = runTest(dispatcher) {
        val repository = cacheWith(sampleReport("R-1"), sampleReport("R-2"))
        val viewModel = ReportDetailViewModel(repository, "R-2")
        viewModel.start()
        advanceUntilIdle()

        viewModel.events.test {
            repository.store.replaceAll(listOf(sampleReport("R-1")), syncedAtEpochMs = 2)
            assertEquals(ReportRemoved("R-2"), awaitItem())

            // Un altro aggiornamento, e R-2 continua a non esserci.
            repository.store.replaceAll(
                listOf(sampleReport("R-1"), sampleReport("R-3")),
                syncedAtEpochMs = 3,
            )
            advanceUntilIdle()
            expectNoEvents()
        }
    }
}
