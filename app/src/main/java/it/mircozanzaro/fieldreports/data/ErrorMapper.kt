package it.mircozanzaro.fieldreports.data

import it.mircozanzaro.fieldreports.domain.DomainError
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Traduce le eccezioni tecniche in errori di dominio.
 *
 * Esiste per una ragione precisa: senza, il ViewModel finirebbe a conoscere
 * `IOException` e `SocketTimeoutException`, cioè dettagli del trasporto.
 * Cambiare client HTTP significherebbe toccare la presentazione. Qui invece
 * si tocca una classe sola.
 */
fun interface ErrorMapper {
    fun map(throwable: Throwable): DomainError
}

class DefaultErrorMapper : ErrorMapper {
    override fun map(throwable: Throwable): DomainError = when (throwable) {
        is SocketTimeoutException -> DomainError.Timeout
        is IOException -> DomainError.Network
        else -> DomainError.Unknown(throwable.message ?: "Errore sconosciuto")
    }
}
