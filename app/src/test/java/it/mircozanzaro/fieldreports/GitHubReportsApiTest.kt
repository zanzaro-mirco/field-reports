package it.mircozanzaro.fieldreports

import it.mircozanzaro.fieldreports.data.remote.GitHubApi
import it.mircozanzaro.fieldreports.data.remote.GitHubReportsApi
import it.mircozanzaro.fieldreports.data.remote.ReportDto
import it.mircozanzaro.fieldreports.data.remote.toDomain
import it.mircozanzaro.fieldreports.domain.ReportStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Test del client HTTP contro un server finto in locale.
 *
 * MockWebServer è un vero server HTTP su `localhost`: la richiesta viene
 * costruita, serializzata, inviata e la risposta deserializzata davvero. È la
 * differenza fra verificare il client e verificare un doppio del client — con
 * un mock di `GitHubApi` non si accorgerebbe nessuno di un `@SerialName`
 * sbagliato o di un percorso storto.
 *
 * Nessuna chiamata esce dalla macchina, quindi la suite resta ermetica e la CI
 * non dipende dalla disponibilità di GitHub.
 */
class GitHubReportsApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: GitHubReportsApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true }
        val gitHubApi = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GitHubApi::class.java)
        api = GitHubReportsApi(gitHubApi, owner = "zanzaro-mirco", repo = "field-reports")
    }

    @After
    fun tearDown() = server.shutdown()

    private fun respondWithFixture() {
        val body = javaClass.classLoader!!
            .getResourceAsStream("github-issues.json")!!
            .bufferedReader()
            .readText()
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
    }

    // --- la richiesta --------------------------------------------------------

    @Test
    fun `interroga il percorso e i parametri giusti`() = runTest {
        respondWithFixture()

        api.fetchReports()

        val request = server.takeRequest()
        assertEquals(
            "/repos/zanzaro-mirco/field-reports/issues?state=all&per_page=50",
            request.path,
        )
        assertEquals("GET", request.method)
    }

    // --- la mappatura --------------------------------------------------------

    @Test
    fun `mappa una issue in un rapporto`() = runTest {
        respondWithFixture()

        val report = api.fetchReports().first().toDomain()!!

        assertEquals("#1041", report.id)
        assertEquals("Sostituzione contatore trifase", report.title)
        assertEquals("m-rossi", report.technician)
        assertEquals("field-reports", report.customer)
        assertEquals(ReportStatus.OPEN, report.status)
    }

    @Test
    fun `una data ISO diventa millisecondi`() = runTest {
        respondWithFixture()

        val report = api.fetchReports().first().toDomain()!!

        // 2026-07-27T08:00:00Z
        assertEquals(1_785_139_200_000L, report.createdAtEpochMs)
    }

    @Test
    fun `il corpo della issue diventa la descrizione, con gli a capo normalizzati`() = runTest {
        // Le issue scritte dal sito di GitHub arrivano con `\r\n`. Il corpo è
        // anche facoltativo: la #1042 non ce l'ha, e non è un errore.
        respondWithFixture()

        val reports = api.fetchReports().map { it.toDomain()!! }

        assertEquals(
            "Il contatore segna consumi anche a impianto spento.\n\nSostituito e sigillato.",
            reports.first { it.id == "#1041" }.description,
        )
        assertEquals("", reports.first { it.id == "#1042" }.description)
    }

    @Test
    fun `le pull request non sono rapporti e vengono scartate`() = runTest {
        // L'endpoint delle issue restituisce anche le PR: è la prima
        // imperfezione dell'API vera che il mapper deve assorbire.
        respondWithFixture()

        val id = api.fetchReports().mapNotNull(ReportDto::id)

        assertTrue("la PR #1044 non doveva passare", id.none { it == "#1044" })
        assertEquals(4, id.size)
    }

    @Test
    fun `l'etichetta in progress diventa lo stato IN_PROGRESS`() = runTest {
        // GitHub non ha un terzo stato: va dedotto da un'etichetta.
        respondWithFixture()

        val report = api.fetchReports().map { it.toDomain()!! }.first { it.id == "#1042" }

        assertEquals(ReportStatus.IN_PROGRESS, report.status)
    }

    @Test
    fun `una issue chiusa resta chiusa anche se etichettata in progress`() = runTest {
        // L'etichetta racconta com'era la lavorazione, non com'è finita.
        respondWithFixture()

        val report = api.fetchReports().map { it.toDomain()!! }.first { it.id == "#1043" }

        assertEquals(ReportStatus.CLOSED, report.status)
    }

    @Test
    fun `campi mancanti o malformati non fanno fallire l'intera lista`() = runTest {
        // La issue #1045 ha titolo nullo, utente nullo e una data illeggibile.
        // Deve degradare da sola, senza portarsi dietro le altre quattro.
        respondWithFixture()

        val reports = api.fetchReports().mapNotNull(ReportDto::toDomain)
        val degraded = reports.first { it.id == "#1045" }

        assertEquals(4, reports.size)
        assertEquals("Senza titolo", degraded.title)
        assertEquals("", degraded.technician)
        assertEquals(0L, degraded.createdAtEpochMs)
    }

    @Test
    fun `una lista vuota non e' un errore`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        assertEquals(emptyList<ReportDto>(), api.fetchReports())
    }

    // --- i guasti ------------------------------------------------------------

    @Test
    fun `un 403 propaga HttpException con il codice`() = runTest {
        // È il caso reale del limite di richieste di GitHub senza token.
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"rate limit"}"""))

        val thrown = runCatching { api.fetchReports() }.exceptionOrNull()

        assertTrue(thrown is HttpException)
        assertEquals(403, (thrown as HttpException).code())
    }

    @Test
    fun `un 500 propaga HttpException con il codice`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val thrown = runCatching { api.fetchReports() }.exceptionOrNull()

        assertEquals(500, (thrown as HttpException).code())
    }

    @Test
    fun `un JSON malformato non passa per un errore di rete`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{ non sono una lista"))

        val thrown = runCatching { api.fetchReports() }.exceptionOrNull()

        assertNull("non deve essere una HttpException", thrown as? HttpException)
    }
}
