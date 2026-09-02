package it.mircozanzaro.fieldreports

import it.mircozanzaro.fieldreports.data.DefaultErrorMapper
import it.mircozanzaro.fieldreports.domain.DomainError
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * La frontiera fra le eccezioni tecniche e il dominio.
 *
 * È l'unica classe già esistente che l'arrivo di Retrofit ha costretto a
 * cambiare, ed è esattamente ciò che il suo KDoc prometteva. Questi test
 * verificano la promessa: qualunque cosa lanci il client, di là esce un
 * [DomainError].
 */
class ErrorMapperTest {

    private val mapper = DefaultErrorMapper()

    private fun httpException(codice: Int) = HttpException(
        Response.error<Any>(
            codice,
            """{"message":"errore"}""".toResponseBody("application/json".toMediaType()),
        ),
    )

    @Test
    fun `il limite di richieste di GitHub diventa un errore di servizio`() {
        // 403 senza token dopo 60 richieste in un'ora: il caso più probabile in
        // assoluto per questa app, e fino all'arrivo del client HTTP il ramo
        // Server non aveva nessuno che lo producesse.
        assertEquals(DomainError.Server(403), mapper.map(httpException(403)))
    }

    @Test
    fun `un repository inesistente diventa un errore di servizio`() {
        assertEquals(DomainError.Server(404), mapper.map(httpException(404)))
    }

    @Test
    fun `un guasto del server conserva il codice`() {
        assertEquals(DomainError.Server(503), mapper.map(httpException(503)))
    }

    @Test
    fun `un timeout resta un timeout e non diventa un errore di rete`() {
        // SocketTimeoutException è una sottoclasse di IOException: se l'ordine
        // dei rami si invertisse, questo test sarebbe l'unico ad accorgersene.
        assertEquals(DomainError.Timeout, mapper.map(SocketTimeoutException()))
    }

    @Test
    fun `l'assenza di rete diventa DomainError Network`() {
        assertEquals(DomainError.Network, mapper.map(UnknownHostException("api.github.com")))
        assertEquals(DomainError.Network, mapper.map(IOException("connessione interrotta")))
    }

    @Test
    fun `una risposta illeggibile non mostra il messaggio della libreria`() {
        val errore = mapper.map(SerializationException("Unexpected JSON token at offset 12"))

        assertTrue(errore is DomainError.Unknown)
        val messaggio = (errore as DomainError.Unknown).message
        assertEquals("Risposta del servizio non riconosciuta.", messaggio)
        assertTrue("non deve trapelare il gergo della libreria", !messaggio.contains("JSON"))
    }

    @Test
    fun `un errore imprevisto conserva il proprio messaggio`() {
        val errore = mapper.map(IllegalStateException("stato incoerente"))

        assertEquals(DomainError.Unknown("stato incoerente"), errore)
    }
}
