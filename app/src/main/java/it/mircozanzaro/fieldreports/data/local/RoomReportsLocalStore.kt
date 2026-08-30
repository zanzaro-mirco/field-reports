package it.mircozanzaro.fieldreports.data.local

import androidx.room.withTransaction
import it.mircozanzaro.fieldreports.domain.Report
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * La cache su SQLite.
 *
 * Riceve il database e non il solo DAO perché [replaceAll] ha bisogno della
 * transazione: cancellare, reinserire e aggiornare il timestamp sono tre
 * scritture che devono valere come una. Senza transazione, un processo ucciso a
 * metà lascerebbe la tabella vuota con un timestamp fresco — cioè una cache
 * dichiarata valida e priva di dati, che è lo stato peggiore possibile.
 *
 * La conversione entity → dominio avviene qui: fuori da questa classe non
 * circola nulla che sappia di Room.
 */
class RoomReportsLocalStore(
    private val database: FieldReportsDatabase,
) : ReportsLocalStore {

    private val dao: ReportDao = database.reportDao()

    override fun observeReports(): Flow<List<Report>> =
        dao.observeAll().map { entities -> entities.map(ReportEntity::toDomain) }

    override suspend fun lastSyncEpochMs(): Long? =
        dao.lastSyncEpochMs(SyncStateEntity.SINGLE_ROW_ID)

    /**
     * Sostituzione totale invece di un confronto riga per riga.
     *
     * È la scelta giusta finché l'app è in sola lettura: la lista arriva intera
     * dal server, e un diff costerebbe complessità senza risolvere alcun
     * problema. Diventerà sbagliata nel momento in cui esisteranno modifiche
     * locali non ancora sincronizzate — quel giorno servirà una coda di
     * scritture, non un merge improvvisato qui dentro.
     */
    override suspend fun replaceAll(reports: List<Report>, syncedAtEpochMs: Long) {
        database.withTransaction {
            dao.deleteAll()
            dao.insertAll(reports.map(Report::toEntity))
            dao.setSyncState(SyncStateEntity(lastSyncEpochMs = syncedAtEpochMs))
        }
    }
}
