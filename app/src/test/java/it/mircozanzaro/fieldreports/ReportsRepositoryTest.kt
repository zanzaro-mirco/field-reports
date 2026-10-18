package it.mircozanzaro.fieldreports

import it.mircozanzaro.fieldreports.data.DefaultErrorMapper
import it.mircozanzaro.fieldreports.data.DefaultReportsRepository
import it.mircozanzaro.fieldreports.data.ErrorMapper
import it.mircozanzaro.fieldreports.data.FixedClock
import it.mircozanzaro.fieldreports.data.SyncScheduler
import it.mircozanzaro.fieldreports.data.TestDispatcherProvider
import it.mircozanzaro.fieldreports.data.TimeBasedStalenessPolicy
import it.mircozanzaro.fieldreports.data.local.InMemoryReportsLocalStore
import it.mircozanzaro.fieldreports.data.remote.ReportDto
import it.mircozanzaro.fieldreports.data.remote.ReportsApi
import it.mircozanzaro.fieldreports.domain.DomainError
import it.mircozanzaro.fieldreports.domain.Outcome
import it.mircozanzaro.fieldreports.domain.Report
import it.mircozanzaro.fieldreports.domain.ReportStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Test del livello dati: mappatura DTO, traduzione degli errori e — da quando
 * esiste la cache — la politica di sincronizzazione.
 *
 * Lo store locale è quello in memoria: qui si verifica *quando* si chiama la
 * rete e *cosa* finisce in cache, non come SQLite salva una riga. Quella parte
 * ha una suite sua (`RoomReportsLocalStoreTest`), che gira sullo stesso
 * contratto.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportsRepositoryTest {

    private val dispatcher = StandardTestDispatcher()
    private val clock = FixedClock(now = 1_000)

    /** Conta le richieste: è tutto quello che il repository può fare con lui. */
    private class RecordingScheduler : SyncScheduler {
        var requests: Int = 0

        override fun scheduleWhenOnline() {
            requests++
        }
    }

    private val scheduler = RecordingScheduler()

    private class FakeApi(
        var dtos: List<ReportDto> = emptyList(),
        var failure: Throwable? = null,
    ) : ReportsApi {
        var callCount: Int = 0

        override suspend fun fetchReports(): List<ReportDto> {
            callCount++
            failure?.let { throw it }
            return dtos
        }
    }

    private fun repository(
        api: ReportsApi,
        store: InMemoryReportsLocalStore = InMemoryReportsLocalStore(),
        maxAgeMs: Long = 60_000,
        errorMapper: ErrorMapper = DefaultErrorMapper(),
    ) = DefaultReportsRepository(
        api = api,
        local = store,
        dispatchers = TestDispatcherProvider(dispatcher),
        syncScheduler = scheduler,
        clock = clock,
        stalenessPolicy = TimeBasedStalenessPolicy(maxAgeMs),
        errorMapper = errorMapper,
    )

    private fun dto(
        id: String? = "R-1",
        status: String? = "OPEN",
        createdAt: Long? = 0L,
    ) = ReportDto(
        id = id,
        title = "Intervento",
        customer = "Cliente",
        status = status,
        createdAt = createdAt,
        technician = "M. Z.",
    )

    private fun report(id: String, createdAt: Long = 0L) = Report(
        id = id,
        title = "Intervento $id",
        customer = "Cliente",
        status = ReportStatus.OPEN,
        createdAtEpochMs = createdAt,
        technician = "M. Z.",
    )

    // --- mappatura -----------------------------------------------------------

    @Test
    fun `un refresh riuscito mette in cache i modelli di dominio`() = runTest(dispatcher) {
        val repository = repository(FakeApi(listOf(dto())))

        assertTrue(repository.refresh() is Outcome.Success)

        val reports = repository.observeReports().first()
        assertEquals(1, reports.size)
        assertEquals(ReportStatus.OPEN, reports.first().status)
    }

    @Test
    fun `scarta i record senza id invece di crashare`() = runTest(dispatcher) {
        val api = FakeApi(listOf(dto(), dto(id = null), dto(id = "  ")))
        val repository = repository(api)

        repository.refresh()

        assertEquals(1, repository.observeReports().first().size)
    }

    @Test
    fun `uno stato sconosciuto si degrada invece di far fallire tutto`() =
        runTest(dispatcher) {
            val repository = repository(FakeApi(listOf(dto(status = "QUALCOSA_DI_NUOVO"))))

            repository.refresh()

            assertEquals(
                ReportStatus.OPEN,
                repository.observeReports().first().first().status,
            )
        }

    @Test
    fun `i rapporti si leggono dal piu recente`() = runTest(dispatcher) {
        val api = FakeApi(
            listOf(dto(id = "vecchio", createdAt = 100), dto(id = "nuovo", createdAt = 900)),
        )
        val repository = repository(api)

        repository.refresh()

        assertEquals("nuovo", repository.observeReports().first().first().id)
    }

    @Test
    fun `registra il momento della sincronizzazione riuscita`() = runTest(dispatcher) {
        val store = InMemoryReportsLocalStore()
        clock.now = 5_000

        repository(FakeApi(listOf(dto())), store).refresh()

        assertEquals(5_000L, store.lastSyncEpochMs())
    }

    // --- errori --------------------------------------------------------------

    @Test
    fun `una IOException diventa DomainError Network`() = runTest(dispatcher) {
        val result = repository(FakeApi(failure = IOException("boom"))).refresh()
        assertEquals(DomainError.Network, (result as Outcome.Failure).error)
    }

    @Test
    fun `un timeout diventa DomainError Timeout`() = runTest(dispatcher) {
        val result = repository(FakeApi(failure = SocketTimeoutException())).refresh()
        assertEquals(DomainError.Timeout, (result as Outcome.Failure).error)
    }

    @Test
    fun `un errore imprevisto conserva il messaggio`() = runTest(dispatcher) {
        val api = FakeApi(failure = IllegalStateException("stato incoerente"))
        val result = repository(api).refresh()

        val error = (result as Outcome.Failure).error
        assertTrue(error is DomainError.Unknown)
        assertEquals("stato incoerente", (error as DomainError.Unknown).message)
    }

    @Test
    fun `il ViewModel non conosce IOException`() = runTest(dispatcher) {
        // Verifica dell'inversione: fuori dal livello dati circolano solo
        // errori di dominio, mai eccezioni del trasporto.
        val result: Outcome<Unit> = repository(FakeApi(failure = IOException())).refresh()
        assertTrue(result is Outcome.Failure)
    }

    @Test
    fun `un refresh fallito non svuota la cache`() = runTest(dispatcher) {
        // È il criterio di fatto dello sviluppo, in forma di test: la rete che
        // cade è un'informazione da mostrare, non una ragione per cancellare
        // dati validi. Senza questa garanzia, aprire l'app offline
        // significherebbe perdere quello che si era già scaricato.
        val store = InMemoryReportsLocalStore(
            initialReports = listOf(report("R-1"), report("R-2")),
            initialSyncEpochMs = 1_000,
        )
        val api = FakeApi(failure = IOException("nessuna connettività"))

        val result = repository(api, store).refresh()

        assertTrue(result is Outcome.Failure)
        assertEquals(2, store.observeReports().first().size)
        assertEquals(1_000L, store.lastSyncEpochMs())
    }

    // --- politica di sincronizzazione ---------------------------------------

    @Test
    fun `refreshIfStale scarica se la cache e vuota`() = runTest(dispatcher) {
        val api = FakeApi(listOf(dto()))

        repository(api).refreshIfStale()

        assertEquals(1, api.callCount)
    }

    @Test
    fun `refreshIfStale non tocca la rete se i dati sono freschi`() = runTest(dispatcher) {
        val store = InMemoryReportsLocalStore(
            initialReports = listOf(report("R-1")),
            initialSyncEpochMs = 1_000,
        )
        val api = FakeApi(listOf(dto()))
        clock.now = 1_000 + 30_000 // metà della finestra

        val result = repository(api, store).refreshIfStale()

        assertTrue(result is Outcome.Success)
        assertEquals(0, api.callCount)
    }

    @Test
    fun `refreshIfStale scarica se i dati sono scaduti`() = runTest(dispatcher) {
        val store = InMemoryReportsLocalStore(
            initialReports = listOf(report("R-1")),
            initialSyncEpochMs = 1_000,
        )
        val api = FakeApi(listOf(dto()))
        clock.now = 1_000 + 60_001

        repository(api, store).refreshIfStale()

        assertEquals(1, api.callCount)
    }

    @Test
    fun `refresh scarica anche quando i dati sono freschi`() = runTest(dispatcher) {
        // La differenza fra le due operazioni: `refreshIfStale` è una politica,
        // `refresh` è un ordine dell'utente e non si discute.
        val store = InMemoryReportsLocalStore(
            initialReports = listOf(report("R-1")),
            initialSyncEpochMs = 1_000,
        )
        val api = FakeApi(listOf(dto()))
        clock.now = 1_000

        repository(api, store).refresh()

        assertEquals(1, api.callCount)
    }

    // --- ripresa quando torna la rete ---------------------------------------

    @Test
    fun `senza rete la sincronizzazione si ripianifica`() = runTest(dispatcher) {
        repository(FakeApi(failure = IOException("nessuna connettività"))).refresh()

        assertEquals(1, scheduler.requests)
    }

    @Test
    fun `anche un timeout si ripianifica`() = runTest(dispatcher) {
        repository(FakeApi(failure = SocketTimeoutException())).refresh()

        assertEquals(1, scheduler.requests)
    }

    @Test
    fun `un errore del servizio non aspetta la rete`() = runTest(dispatcher) {
        // La rete c'era: un 403 per il limite di richieste o un 404 non cambiano
        // quando la rete «torna». Ripianificarli vorrebbe dire un worker che
        // parte subito, fallisce uguale, e ritenta per niente.
        val repository = repository(
            api = FakeApi(failure = IllegalStateException("qualunque")),
            errorMapper = ErrorMapper { DomainError.Server(403) },
        )

        repository.refresh()

        assertEquals(0, scheduler.requests)
    }

    @Test
    fun `una risposta illeggibile non aspetta la rete`() = runTest(dispatcher) {
        repository(FakeApi(failure = IllegalStateException("stato incoerente"))).refresh()

        assertEquals(0, scheduler.requests)
    }

    @Test
    fun `una sincronizzazione riuscita non lascia niente in coda`() = runTest(dispatcher) {
        repository(FakeApi(listOf(dto()))).refresh()

        assertEquals(0, scheduler.requests)
    }

    @Test
    fun `una cancellazione risale e non diventa un errore`() = runTest(dispatcher) {
        // È quello che succede quando WorkManager ferma il worker perché la rete
        // è caduta a metà: chi ha chiesto il lavoro non lo vuole più. Prima
        // finiva nel ramo generico e tornava come `DomainError.Unknown`, cioè
        // come un guasto da mostrare all'utente.
        val api = FakeApi(failure = CancellationException("worker fermato"))

        val thrown = try {
            repository(api).refresh()
            null
        } catch (cancellation: CancellationException) {
            cancellation
        }

        assertEquals("worker fermato", thrown?.message)
        assertEquals(0, scheduler.requests)
    }
}
