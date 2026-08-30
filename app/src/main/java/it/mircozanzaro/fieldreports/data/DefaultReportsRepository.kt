package it.mircozanzaro.fieldreports.data

import it.mircozanzaro.fieldreports.data.local.ReportsLocalStore
import it.mircozanzaro.fieldreports.data.remote.ReportDto
import it.mircozanzaro.fieldreports.data.remote.ReportsApi
import it.mircozanzaro.fieldreports.data.remote.toDomain
import it.mircozanzaro.fieldreports.domain.Outcome
import it.mircozanzaro.fieldreports.domain.Report
import it.mircozanzaro.fieldreports.domain.ReportsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Implementazione del repository, con la cache locale come sorgente unica.
 *
 * La regola è una sola e vale in entrambi i versi: **si legge sempre dal
 * database, si scrive nel database solo dalla rete**. La UI non vede mai il
 * risultato di una chiamata HTTP; vede il contenuto della cache, che una
 * sincronizzazione riuscita ha aggiornato. È ciò che rende l'app leggibile
 * offline senza un solo ramo `if (isOnline)` sparso nella presentazione.
 *
 * Da qui discende il comportamento che conta davvero: **se la rete fallisce, la
 * cache non viene toccata**. Un errore di sincronizzazione è un'informazione da
 * mostrare, non una ragione per cancellare dati validi.
 *
 * Le dipendenze sono tutte iniettate — API, cache, dispatcher, orologio,
 * politica di scadenza, traduzione degli errori — e ognuna per un motivo
 * preciso: sono le sei cose che nei test vanno controllate a mano.
 */
class DefaultReportsRepository(
    private val api: ReportsApi,
    private val local: ReportsLocalStore,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock = SystemClock(),
    private val stalenessPolicy: StalenessPolicy = TimeBasedStalenessPolicy(),
    private val errorMapper: ErrorMapper = DefaultErrorMapper(),
) : ReportsRepository {

    override fun observeReports(): Flow<List<Report>> = local.observeReports()

    override suspend fun refresh(): Outcome<Unit> =
        withContext(dispatchers.io) { fetchAndStore() }

    override suspend fun refreshIfStale(): Outcome<Unit> =
        withContext(dispatchers.io) {
            val lastSync = local.lastSyncEpochMs()
            if (stalenessPolicy.isStale(lastSync, clock.nowEpochMs())) {
                fetchAndStore()
            } else {
                // Dati ancora buoni: non è un errore, semplicemente non c'è
                // niente da fare. Il chiamante non deve distinguere i due casi.
                Outcome.Success(Unit)
            }
        }

    private suspend fun fetchAndStore(): Outcome<Unit> = try {
        val dtos: List<ReportDto> = api.fetchReports()
        val reports: List<Report> = dtos.mapNotNull(ReportDto::toDomain)
        local.replaceAll(reports, clock.nowEpochMs())
        Outcome.Success(Unit)
    } catch (throwable: Throwable) {
        // Nessuna scrittura è avvenuta: la cache resta quella di prima.
        Outcome.Failure(errorMapper.map(throwable))
    }
}
