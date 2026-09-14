package it.mircozanzaro.fieldreports

import it.mircozanzaro.fieldreports.data.local.InMemoryReportsLocalStore
import it.mircozanzaro.fieldreports.domain.Outcome
import it.mircozanzaro.fieldreports.domain.Report
import it.mircozanzaro.fieldreports.domain.ReportStatus
import it.mircozanzaro.fieldreports.domain.ReportsRepository
import kotlinx.coroutines.flow.Flow

/**
 * Un repository che è soltanto la sua cache: nessuna rete, e sincronizzazioni
 * che riescono sempre senza cambiare niente.
 *
 * Serve ai test del dettaglio e della navigazione, dove conta cosa succede
 * quando la cache cambia — e la si cambia a mano, scrivendo in [store]. La cache
 * è lo store in memoria vero, quello che rispetta lo stesso contratto di Room,
 * e non un'imitazione scritta per l'occasione.
 */
class CacheOnlyReportsRepository(
    val store: InMemoryReportsLocalStore = InMemoryReportsLocalStore(),
) : ReportsRepository {

    override fun observeReports(): Flow<List<Report>> = store.observeReports()

    override fun observeReport(id: String): Flow<Report?> = store.observeReport(id)

    override suspend fun refresh(): Outcome<Unit> = Outcome.Success(Unit)

    override suspend fun refreshIfStale(): Outcome<Unit> = Outcome.Success(Unit)
}

fun sampleReport(
    id: String,
    title: String = "Intervento $id",
    description: String = "",
    createdAtEpochMs: Long = 0L,
    status: ReportStatus = ReportStatus.OPEN,
) = Report(
    id = id,
    title = title,
    customer = "Acquedotto Nord",
    status = status,
    createdAtEpochMs = createdAtEpochMs,
    technician = "M. Rossi",
    description = description,
)
