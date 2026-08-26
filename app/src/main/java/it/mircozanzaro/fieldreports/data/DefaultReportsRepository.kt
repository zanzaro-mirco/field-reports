package it.mircozanzaro.fieldreports.data

import it.mircozanzaro.fieldreports.data.remote.ReportDto
import it.mircozanzaro.fieldreports.data.remote.ReportsApi
import it.mircozanzaro.fieldreports.data.remote.toDomain
import it.mircozanzaro.fieldreports.domain.Outcome
import it.mircozanzaro.fieldreports.domain.Report
import it.mircozanzaro.fieldreports.domain.ReportsRepository
import kotlinx.coroutines.withContext

/**
 * Implementazione del repository.
 *
 * Tre responsabilità volutamente separate da chi le usa: chiamare l'API,
 * tradurre DTO ed errori in dominio, e garantire che il lavoro avvenga fuori
 * dal thread principale. Il dispatcher è iniettato invece di essere cablato:
 * nei test si passa un dispatcher controllabile e la suite diventa
 * deterministica.
 */
class DefaultReportsRepository(
    private val api: ReportsApi,
    private val dispatchers: DispatcherProvider,
    private val errorMapper: ErrorMapper = DefaultErrorMapper(),
) : ReportsRepository {

    override suspend fun loadReports(): Outcome<List<Report>> =
        withContext(dispatchers.io) {
            try {
                val dtos: List<ReportDto> = api.fetchReports()
                val reports: List<Report> = dtos
                    .mapNotNull(ReportDto::toDomain)
                    .sortedByDescending(Report::createdAtEpochMs)
                Outcome.Success(reports)
            } catch (throwable: Throwable) {
                Outcome.Failure(errorMapper.map(throwable))
            }
        }
}
