package it.mircozanzaro.fieldreports

import it.mircozanzaro.fieldreports.data.local.ReportsLocalStore
import it.mircozanzaro.fieldreports.domain.Report
import it.mircozanzaro.fieldreports.domain.ReportStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Il contratto di [ReportsLocalStore], scritto una volta sola.
 *
 * Le sottoclassi forniscono l'implementazione da esaminare e non aggiungono
 * asserzioni: la stessa suite gira sullo store in memoria e su quello Room. È
 * la sola prova che i due sono davvero intercambiabili — un test scritto due
 * volte diverge alla prima modifica, e a quel punto "implementano la stessa
 * interfaccia" resta vero solo per il compilatore.
 */
abstract class ReportsLocalStoreContract {

    /** Uno store vuoto e nuovo per ogni test. */
    abstract fun createStore(): ReportsLocalStore

    @Test
    fun `parte vuoto e senza data di sincronizzazione`() = runTest {
        val store = createStore()
        assertEquals(emptyList<Report>(), store.observeReports().first())
        assertNull(store.lastSyncEpochMs())
    }

    @Test
    fun `restituisce i rapporti dal piu recente`() = runTest {
        val store = createStore()
        store.replaceAll(
            listOf(
                report("vecchio", createdAt = 100),
                report("nuovo", createdAt = 900),
                report("intermedio", createdAt = 500),
            ),
            syncedAtEpochMs = 1_000,
        )

        val ids = store.observeReports().first().map(Report::id)
        assertEquals(listOf("nuovo", "intermedio", "vecchio"), ids)
    }

    @Test
    fun `replaceAll sostituisce il contenuto invece di aggiungersi`() = runTest {
        val store = createStore()
        store.replaceAll(listOf(report("R-1"), report("R-2")), syncedAtEpochMs = 1_000)
        store.replaceAll(listOf(report("R-3")), syncedAtEpochMs = 2_000)

        val ids = store.observeReports().first().map(Report::id)
        assertEquals(listOf("R-3"), ids)
    }

    @Test
    fun `registra il momento dell'ultima sincronizzazione`() = runTest {
        val store = createStore()
        store.replaceAll(listOf(report("R-1")), syncedAtEpochMs = 1_700_000_000_000)
        assertEquals(1_700_000_000_000L, store.lastSyncEpochMs())
    }

    @Test
    fun `una sincronizzazione senza rapporti resta comunque registrata`() = runTest {
        // Il caso limite per cui il timestamp sta in una tabella sua: se il
        // server risponde "nessun rapporto", la sincronizzazione è avvenuta.
        val store = createStore()
        store.replaceAll(emptyList(), syncedAtEpochMs = 4_200)

        assertEquals(emptyList<Report>(), store.observeReports().first())
        assertEquals(4_200L, store.lastSyncEpochMs())
    }

    @Test
    fun `conserva tutti i campi del rapporto`() = runTest {
        val store = createStore()
        val original = Report(
            id = "R-99",
            title = "Taratura stampante fiscale",
            customer = "Supermercati Est",
            status = ReportStatus.IN_PROGRESS,
            createdAtEpochMs = 1_753_400_000_000,
            technician = "M. Rossi",
        )
        store.replaceAll(listOf(original), syncedAtEpochMs = 1)

        assertEquals(original, store.observeReports().first().single())
    }

    @Test
    fun `il flusso emette il contenuto aggiornato dopo una scrittura`() = runTest {
        val store = createStore()
        val reports = store.observeReports()

        assertEquals(0, reports.first().size)
        store.replaceAll(listOf(report("R-1")), syncedAtEpochMs = 1)
        assertEquals(1, reports.first().size)
    }

    protected fun report(
        id: String,
        createdAt: Long = 0L,
        status: ReportStatus = ReportStatus.OPEN,
    ) = Report(
        id = id,
        title = "Intervento $id",
        customer = "Cliente",
        status = status,
        createdAtEpochMs = createdAt,
        technician = "M. Z.",
    )
}
