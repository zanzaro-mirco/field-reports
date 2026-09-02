package it.mircozanzaro.fieldreports.data

import it.mircozanzaro.fieldreports.domain.DomainError
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Traduce le eccezioni tecniche in errori di dominio.
 *
 * Esiste per una ragione precisa: senza, il ViewModel finirebbe a conoscere
 * `IOException` e `SocketTimeoutException`, cioè dettagli del trasporto.
 * Cambiare client HTTP significherebbe toccare la presentazione. Qui invece
 * si tocca una classe sola.
 *
 * L'arrivo di Retrofit ha messo alla prova quella frase, ed è andata come
 * doveva: è **questa** l'unica classe di logica già esistente che ha dovuto
 * cambiare, e ha guadagnato due rami. `HttpException` e
 * `SerializationException` sono tipi di libreria che si fermano qui dentro;
 * sopra continua a circolare solo [DomainError].
 */
fun interface ErrorMapper {
    fun map(throwable: Throwable): DomainError
}

class DefaultErrorMapper : ErrorMapper {
    override fun map(throwable: Throwable): DomainError = when (throwable) {
        // Prima di `IOException`: `SocketTimeoutException` ne è una sottoclasse,
        // e invertire i due rami renderebbe il timeout irraggiungibile.
        is SocketTimeoutException -> DomainError.Timeout
        is IOException -> DomainError.Network

        // Una risposta arrivata ma non riuscita: 403 per il limite di richieste
        // di GitHub, 404 per un repository che non esiste, 5xx per un guasto
        // dall'altra parte. È il ramo che fino all'arrivo del client HTTP
        // nessuno produceva.
        is HttpException -> DomainError.Server(throwable.code())

        // Il server ha risposto e la risposta non ha la forma attesa. Il
        // messaggio della libreria parla di discriminatori e indici JSON: non
        // è qualcosa da mostrare a un tecnico in cantiere.
        is SerializationException ->
            DomainError.Unknown("Risposta del servizio non riconosciuta.")

        else -> DomainError.Unknown(throwable.message ?: "Errore sconosciuto")
    }
}
