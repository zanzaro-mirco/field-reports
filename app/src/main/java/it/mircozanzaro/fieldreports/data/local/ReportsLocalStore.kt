package it.mircozanzaro.fieldreports.data.local

import it.mircozanzaro.fieldreports.domain.Report
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * La cache locale, vista dal repository.
 *
 * Room sta dietro un'interfaccia per la stessa ragione per cui ci sta la rete:
 * il repository non deve sapere che sotto c'è SQLite, e il test del repository
 * non deve avere bisogno di un database per girare. È anche ciò che rende
 * verificabile la separazione — se domani la cache diventasse un file o un
 * DataStore, cambierebbe un'implementazione sola.
 *
 * **Contratto**, valido per ogni implementazione:
 * - [observeReports] emette subito lo stato corrente e poi a ogni scrittura;
 * - i rapporti escono ordinati dal più recente al più vecchio;
 * - [replaceAll] è atomica: o si vedono tutti i dati nuovi, o tutti i vecchi.
 *
 * Il contratto è scritto qui e verificato da una suite di test unica che gira
 * su entrambe le implementazioni: è il modo per sapere che sono davvero
 * intercambiabili, invece di sperarlo.
 */
interface ReportsLocalStore {

    fun observeReports(): Flow<List<Report>>

    /** `null` se non è mai avvenuta una sincronizzazione riuscita. */
    suspend fun lastSyncEpochMs(): Long?

    /** Sostituisce l'intero contenuto e registra il momento della sincronizzazione. */
    suspend fun replaceAll(reports: List<Report>, syncedAtEpochMs: Long)
}

/**
 * Implementazione in memoria.
 *
 * Serve ai test del repository, che riguardano la logica di sincronizzazione e
 * non SQL, e alle anteprime Compose. Vive nel sorgente principale accanto a
 * `FakeReportsApi`, per la stessa ragione: è un'implementazione semplificata ma
 * funzionante del contratto, non un mock.
 */
class InMemoryReportsLocalStore(
    initialReports: List<Report> = emptyList(),
    initialSyncEpochMs: Long? = null,
) : ReportsLocalStore {

    private val reports = MutableStateFlow(initialReports.newestFirst())
    private var lastSync: Long? = initialSyncEpochMs

    override fun observeReports(): Flow<List<Report>> = reports.asStateFlow()

    override suspend fun lastSyncEpochMs(): Long? = lastSync

    override suspend fun replaceAll(reports: List<Report>, syncedAtEpochMs: Long) {
        this.reports.value = reports.newestFirst()
        lastSync = syncedAtEpochMs
    }

    private fun List<Report>.newestFirst(): List<Report> =
        sortedByDescending(Report::createdAtEpochMs)
}
