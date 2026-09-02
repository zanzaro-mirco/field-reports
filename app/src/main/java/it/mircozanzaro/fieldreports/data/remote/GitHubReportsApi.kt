package it.mircozanzaro.fieldreports.data.remote

import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Le issue di un repository, presentate come rapporti di intervento.
 *
 * Implementa il contratto [ReportsApi] già esistente e restituisce [ReportDto],
 * quindi il repository, il mapper verso il dominio e tutto ciò che sta sopra
 * non si accorgono del cambio di sorgente. È la ragione per cui il contratto
 * era definito in termini di DTO e non di risposta HTTP.
 *
 * **Perché una sorgente mappata e non inventata.** Nessuna API pubblica
 * restituisce rapporti di intervento tecnico. Fabbricare clienti e tecnici
 * dentro un mapper sarebbe stato peggio che non avere una sorgente vera: qui i
 * dati esistono davvero, e la corrispondenza è dichiarata invece che nascosta.
 *
 * Le tre imperfezioni che questa classe assorbe — e che sono il motivo per cui
 * un'API vera insegna più di un file JSON con lo schema perfetto:
 *
 * 1. l'endpoint restituisce anche le pull request, che non sono rapporti;
 * 2. `IN_PROGRESS` non esiste su GitHub e va dedotto da un'etichetta;
 * 3. le date arrivano in ISO-8601, il dominio le vuole in millisecondi.
 */
class GitHubReportsApi(
    private val api: GitHubApi,
    private val owner: String,
    private val repo: String,
    private val perPage: Int = DEFAULT_PER_PAGE,
) : ReportsApi {

    override suspend fun fetchReports(): List<ReportDto> =
        api.issues(owner = owner, repo = repo, state = "all", perPage = perPage)
            .filter { it.pullRequest == null } // una PR non è un rapporto
            .map(GitHubIssueDto::toReportDto)

    companion object {
        const val DEFAULT_PER_PAGE: Int = 50
    }
}

/**
 * Da issue a DTO di rapporto.
 *
 * Non produce direttamente un `Report`: si ferma a [ReportDto], così le
 * difese già scritte e già testate in `ReportDto.toDomain()` — scarto degli id
 * vuoti, titoli mancanti, stati sconosciuti — continuano a valere identiche.
 * Una seconda strada verso il dominio avrebbe significato una seconda copia di
 * quelle difese, cioè due copie che prima o poi divergono.
 */
internal fun GitHubIssueDto.toReportDto(): ReportDto = ReportDto(
    id = number?.let { "#$it" },
    title = title,
    customer = repositoryUrl?.substringAfterLast('/'),
    status = resolveStatus(),
    createdAt = createdAt?.toEpochMillisOrNull(),
    technician = user?.login,
)

/**
 * Lo stato di lavorazione, dedotto.
 *
 * GitHub conosce solo `open` e `closed`: il terzo stato del dominio non ha un
 * corrispondente e va cercato altrove. L'etichetta `in progress` è la
 * convenzione con cui i team la esprimono, quindi è lì che si guarda — ma solo
 * su una issue aperta, perché una chiusa è chiusa a prescindere da come era
 * etichettata mentre ci si lavorava.
 */
private fun GitHubIssueDto.resolveStatus(): String? {
    if (state == null) return null
    if (!state.equals("open", ignoreCase = true)) return state
    val inProgress = labels.any { label ->
        label.name?.replace('-', ' ')?.trim().equals("in progress", ignoreCase = true)
    }
    return if (inProgress) "IN_PROGRESS" else state
}

/**
 * ISO-8601 in millisecondi, o `null` se il testo non è una data.
 *
 * `Instant.parse` fa il lavoro e richiederebbe la API 26; il desugaring lo
 * rende disponibile fino alla 24, che è il `minSdk` del progetto. Un parser
 * scritto a mano sarebbe stato codice da mantenere e da testare per risolvere
 * un problema che la libreria standard risolve già.
 *
 * Una data illeggibile non fa fallire l'intera lista: diventa `null`, e
 * `ReportDto.toDomain()` decide cosa farne — la stessa disciplina applicata a
 * ogni altro campo.
 */
private fun String.toEpochMillisOrNull(): Long? = try {
    Instant.parse(this).toEpochMilli()
} catch (_: DateTimeParseException) {
    null
}
