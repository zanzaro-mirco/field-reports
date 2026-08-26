package it.mircozanzaro.fieldreports.data.remote

import kotlinx.coroutines.delay
import java.io.IOException

/** Contratto verso la sorgente remota. Restituisce DTO, non modelli di dominio. */
interface ReportsApi {
    suspend fun fetchReports(): List<ReportDto>
}

/**
 * Sorgente simulata con latenza e guasti controllabili.
 *
 * Permette di riprodurre a comando lentezza ed errore, che sono gli stati che
 * nessuno prova finché non capitano in produzione.
 */
class FakeReportsApi(
    private val latency: Long = 400L,
    var failure: Throwable? = null,
) : ReportsApi {

    override suspend fun fetchReports(): List<ReportDto> {
        delay(latency)
        failure?.let { throw it }
        return sample
    }

    /** Scorciatoia per simulare l'assenza di rete. */
    fun goOffline() {
        failure = IOException("Nessuna connettività")
    }

    fun goOnline() {
        failure = null
    }

    private val sample: List<ReportDto> = listOf(
        ReportDto(
            id = "R-1041",
            title = "Sostituzione contatore trifase",
            customer = "Acquedotto Nord",
            status = "OPEN",
            createdAt = 1_753_600_000_000,
            technician = "M. Rossi",
        ),
        ReportDto(
            id = "R-1042",
            title = "Verifica lettore RFID magazzino",
            customer = "Logistica Veneta",
            status = "IN_PROGRESS",
            createdAt = 1_753_500_000_000,
            technician = "L. Bianchi",
        ),
        ReportDto(
            id = "R-1043",
            title = "Taratura stampante fiscale cassa 3",
            customer = "Supermercati Est",
            status = "CLOSED",
            createdAt = 1_753_400_000_000,
            technician = "M. Rossi",
        ),
    )
}
