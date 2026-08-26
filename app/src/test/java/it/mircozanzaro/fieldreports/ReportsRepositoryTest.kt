package it.mircozanzaro.fieldreports

import it.mircozanzaro.fieldreports.data.DefaultReportsRepository
import it.mircozanzaro.fieldreports.data.TestDispatcherProvider
import it.mircozanzaro.fieldreports.data.remote.ReportDto
import it.mircozanzaro.fieldreports.data.remote.ReportsApi
import it.mircozanzaro.fieldreports.domain.DomainError
import it.mircozanzaro.fieldreports.domain.Outcome
import it.mircozanzaro.fieldreports.domain.Report
import it.mircozanzaro.fieldreports.domain.ReportStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Test del livello dati: mappatura DTO, ordinamento e traduzione degli errori.
 *
 * È la parte che prima non era testata separatamente, perché mappatura e
 * gestione degli errori erano annegate nel repository insieme alla chiamata.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportsRepositoryTest {

    private val dispatcher = StandardTestDispatcher()

    private class FakeApi(
        var dtos: List<ReportDto> = emptyList(),
        var failure: Throwable? = null,
    ) : ReportsApi {
        override suspend fun fetchReports(): List<ReportDto> {
            failure?.let { throw it }
            return dtos
        }
    }

    private fun repository(api: ReportsApi) =
        DefaultReportsRepository(api, TestDispatcherProvider(dispatcher))

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

    @Test
    fun `mappa i DTO in modelli di dominio`() = runTest(dispatcher) {
        val result = repository(FakeApi(listOf(dto()))).loadReports()
        val reports = (result as Outcome.Success).value
        assertEquals(1, reports.size)
        assertEquals(ReportStatus.OPEN, reports.first().status)
    }

    @Test
    fun `scarta i record senza id invece di crashare`() = runTest(dispatcher) {
        val api = FakeApi(listOf(dto(), dto(id = null), dto(id = "  ")))
        val reports = (repository(api).loadReports() as Outcome.Success).value
        assertEquals(1, reports.size)
    }

    @Test
    fun `uno stato sconosciuto si degrada invece di far fallire tutto`() =
        runTest(dispatcher) {
            val api = FakeApi(listOf(dto(status = "QUALCOSA_DI_NUOVO")))
            val reports = (repository(api).loadReports() as Outcome.Success).value
            assertEquals(ReportStatus.OPEN, reports.first().status)
        }

    @Test
    fun `ordina dal piu recente`() = runTest(dispatcher) {
        val api = FakeApi(
            listOf(dto(id = "vecchio", createdAt = 100), dto(id = "nuovo", createdAt = 900)),
        )
        val reports = (repository(api).loadReports() as Outcome.Success).value
        assertEquals("nuovo", reports.first().id)
    }

    @Test
    fun `una IOException diventa DomainError Network`() = runTest(dispatcher) {
        val api = FakeApi(failure = IOException("boom"))
        val result = repository(api).loadReports()
        assertEquals(DomainError.Network, (result as Outcome.Failure).error)
    }

    @Test
    fun `un timeout diventa DomainError Timeout`() = runTest(dispatcher) {
        val api = FakeApi(failure = SocketTimeoutException())
        val result = repository(api).loadReports()
        assertEquals(DomainError.Timeout, (result as Outcome.Failure).error)
    }

    @Test
    fun `un errore imprevisto conserva il messaggio`() = runTest(dispatcher) {
        val api = FakeApi(failure = IllegalStateException("stato incoerente"))
        val result = repository(api).loadReports()
        val error = (result as Outcome.Failure).error
        assertTrue(error is DomainError.Unknown)
        assertEquals("stato incoerente", (error as DomainError.Unknown).message)
    }

    @Test
    fun `il ViewModel non conosce IOException`() = runTest(dispatcher) {
        // Verifica dell'inversione: fuori dal livello dati circolano solo
        // errori di dominio, mai eccezioni del trasporto.
        val result: Outcome<List<Report>> =
            repository(FakeApi(failure = IOException())).loadReports()
        assertTrue(result is Outcome.Failure)
    }
}
