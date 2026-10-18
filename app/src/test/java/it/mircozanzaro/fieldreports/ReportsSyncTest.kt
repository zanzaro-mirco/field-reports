package it.mircozanzaro.fieldreports

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import it.mircozanzaro.fieldreports.data.DefaultReportsRepository
import it.mircozanzaro.fieldreports.data.StandardDispatcherProvider
import it.mircozanzaro.fieldreports.data.local.InMemoryReportsLocalStore
import it.mircozanzaro.fieldreports.data.remote.ReportDto
import it.mircozanzaro.fieldreports.data.remote.ReportsApi
import it.mircozanzaro.fieldreports.domain.DomainError
import it.mircozanzaro.fieldreports.domain.Outcome
import it.mircozanzaro.fieldreports.domain.Report
import it.mircozanzaro.fieldreports.domain.ReportsRepository
import it.mircozanzaro.fieldreports.sync.ReportsSyncWorker
import it.mircozanzaro.fieldreports.sync.ReportsWorkerFactory
import it.mircozanzaro.fieldreports.sync.WorkManagerSyncScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.UUID

/**
 * La sincronizzazione in background, sul confine con WorkManager.
 *
 * Due livelli, perché verificano due cose diverse:
 * - il **worker** da solo, costruito con `TestListenableWorkerBuilder`: quale
 *   risposta dà a WorkManager per ogni esito del repository;
 * - il **giro intero** su un WorkManager di test: il lavoro pianificato resta
 *   fermo finché il vincolo sulla rete non è soddisfatto, e quando lo è parte e
 *   riempie la cache. Il «quando torna la rete» lo simula il `TestDriver`, che
 *   dichiara soddisfatti i vincoli di un lavoro.
 *
 * Quello che qui non si può verificare è il sistema vero: che JobScheduler
 * svegli l'app chiusa quando il telefono ritrova il Wi-Fi. Quella parte si
 * guarda su un dispositivo, come scritto nel README.
 */
@RunWith(RobolectricTestRunner::class)
class ReportsSyncTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Un repository che risponde quello che gli si dice, e conta. */
    private class ScriptedRepository(var outcome: Outcome<Unit>) : ReportsRepository {
        var staleRefreshes: Int = 0
        var forcedRefreshes: Int = 0

        override fun observeReports(): Flow<List<Report>> = flowOf(emptyList())
        override fun observeReport(id: String): Flow<Report?> = flowOf(null)

        override suspend fun refresh(): Outcome<Unit> {
            forcedRefreshes++
            return outcome
        }

        override suspend fun refreshIfStale(): Outcome<Unit> {
            staleRefreshes++
            return outcome
        }
    }

    private fun runWorker(
        repository: ReportsRepository,
        runAttemptCount: Int = 0,
    ): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<ReportsSyncWorker>(context)
            .setWorkerFactory(ReportsWorkerFactory { repository })
            .setRunAttemptCount(runAttemptCount)
            .build()
            .doWork()
    }

    // --- il worker -----------------------------------------------------------

    @Test
    fun `una sincronizzazione riuscita chiude il lavoro`() {
        val result = runWorker(ScriptedRepository(Outcome.Success(Unit)))

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `il worker non forza la rete se la cache e gia fresca`() {
        // Se nel frattempo l'utente ha aggiornato a mano, il lavoro deve trovare
        // la cache buona e finire lì. `refresh` la scaricherebbe di nuovo.
        val repository = ScriptedRepository(Outcome.Success(Unit))

        runWorker(repository)

        assertEquals(1, repository.staleRefreshes)
        assertEquals(0, repository.forcedRefreshes)
    }

    @Test
    fun `rete e timeout si ritentano`() {
        val network = runWorker(ScriptedRepository(Outcome.Failure(DomainError.Network)))
        val timeout = runWorker(ScriptedRepository(Outcome.Failure(DomainError.Timeout)))

        assertEquals(ListenableWorker.Result.retry(), network)
        assertEquals(ListenableWorker.Result.retry(), timeout)
    }

    @Test
    fun `al quinto tentativo senza rete il worker smette`() {
        val repository = ScriptedRepository(Outcome.Failure(DomainError.Network))

        // `runAttemptCount` parte da zero: il quinto tentativo è il numero 4.
        val fourth = runWorker(repository, runAttemptCount = ReportsSyncWorker.MAX_ATTEMPTS - 2)
        val fifth = runWorker(repository, runAttemptCount = ReportsSyncWorker.MAX_ATTEMPTS - 1)

        assertEquals(ListenableWorker.Result.retry(), fourth)
        assertEquals(ListenableWorker.Result.failure(), fifth)
    }

    @Test
    fun `un errore del servizio non si ritenta`() {
        val server = runWorker(ScriptedRepository(Outcome.Failure(DomainError.Server(404))))
        val unknown = runWorker(ScriptedRepository(Outcome.Failure(DomainError.Unknown("?"))))

        assertEquals(ListenableWorker.Result.failure(), server)
        assertEquals(ListenableWorker.Result.failure(), unknown)
    }

    // --- il giro intero ------------------------------------------------------

    private lateinit var workManager: WorkManager

    private fun initWorkManager(repository: ReportsRepository) {
        val configuration = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setWorkerFactory(ReportsWorkerFactory { repository })
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        workManager = WorkManager.getInstance(context)
    }

    private fun pendingWork(): List<WorkInfo> = workManager
        .getWorkInfosForUniqueWork(WorkManagerSyncScheduler.UNIQUE_WORK_NAME)
        .get()

    /** Il `CoroutineWorker` gira sul suo dispatcher: si aspetta che finisca. */
    private fun awaitFinished(id: UUID): WorkInfo = runBlocking {
        withTimeout(10_000) {
            workManager.getWorkInfoByIdFlow(id).first { it?.state?.isFinished == true }!!
        }
    }

    private fun networkReturns(id: UUID) {
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(id)
    }

    @Test
    fun `il lavoro pianificato aspetta la rete`() {
        val repository = ScriptedRepository(Outcome.Success(Unit))
        initWorkManager(repository)

        WorkManagerSyncScheduler { workManager }.scheduleWhenOnline()

        val work = pendingWork().single()
        assertEquals(WorkInfo.State.ENQUEUED, work.state)
        assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
        assertEquals(0, repository.staleRefreshes)
    }

    @Test
    fun `dieci richieste senza rete lasciano in coda lo stesso lavoro`() {
        // Lo stesso, non solo uno. Contare i lavori in coda non basta: con
        // `REPLACE` al posto di `KEEP` il conto resta uno, perché il vecchio
        // viene cancellato e sostituito — e il test che contava è rimasto verde
        // quando l'ho provato. La differenza si vede nell'id: con `REPLACE` un
        // worker che fallisce e richiede verrebbe sostituito da uno nuovo, con
        // il conto dei tentativi a zero, e il limite di cinque non scatterebbe
        // mai.
        initWorkManager(ScriptedRepository(Outcome.Success(Unit)))
        val scheduler = WorkManagerSyncScheduler { workManager }

        scheduler.scheduleWhenOnline()
        val first = pendingWork().single().id
        repeat(9) { scheduler.scheduleWhenOnline() }

        val pending = pendingWork().filter { !it.state.isFinished }
        assertEquals(listOf(first), pending.map { it.id })
    }

    @Test
    fun `quando la rete torna la cache si riempie`() {
        // Il criterio della voce, dall'inizio alla fine, con il repository vero:
        // la prima sincronizzazione fallisce senza rete e lascia un lavoro in
        // coda; la rete torna, il lavoro parte, e i rapporti finiscono in cache.
        val api = object : ReportsApi {
            var online = false

            override suspend fun fetchReports(): List<ReportDto> {
                if (!online) throw IOException("nessuna connettività")
                return listOf(
                    ReportDto(
                        id = "R-1",
                        title = "Intervento",
                        customer = "Cliente",
                        status = "OPEN",
                        createdAt = 0L,
                        technician = "M. Z.",
                    ),
                )
            }
        }
        val store = InMemoryReportsLocalStore()
        lateinit var repository: DefaultReportsRepository
        initWorkManager(lazyRepository { repository })
        repository = DefaultReportsRepository(
            api = api,
            local = store,
            dispatchers = StandardDispatcherProvider(),
            syncScheduler = WorkManagerSyncScheduler { workManager },
        )

        val offline = runBlocking { repository.refresh() }
        val work = pendingWork().single()

        assertEquals(Outcome.Failure(DomainError.Network), offline)
        assertEquals(WorkInfo.State.ENQUEUED, work.state)
        assertEquals(0, runBlocking { store.observeReports().first() }.size)

        api.online = true
        networkReturns(work.id)

        assertEquals(WorkInfo.State.SUCCEEDED, awaitFinished(work.id).state)
        assertEquals(listOf("R-1"), runBlocking { store.observeReports().first() }.map { it.id })
    }

    /**
     * Il WorkManager va configurato prima di costruire il repository, e il
     * repository serve alla factory del WorkManager: si rompe il cerchio
     * leggendolo solo quando un worker parte, come fa il `Provider` in
     * produzione.
     */
    private fun lazyRepository(get: () -> ReportsRepository): ReportsRepository =
        object : ReportsRepository {
            override fun observeReports() = get().observeReports()
            override fun observeReport(id: String) = get().observeReport(id)
            override suspend fun refresh() = get().refresh()
            override suspend fun refreshIfStale() = get().refreshIfStale()
        }
}
