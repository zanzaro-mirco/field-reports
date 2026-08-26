package it.mircozanzaro.fieldreports.ui

import it.mircozanzaro.fieldreports.domain.DomainError

/**
 * Traduce un errore di dominio nel testo mostrato all'utente.
 *
 * Sta nella presentazione e non nel dominio: il messaggio è una scelta di
 * prodotto, cambia con la lingua e con il tono, e non deve costringere a
 * toccare la logica. Nell'app reale qui si leggono le risorse stringa.
 */
fun interface ErrorTextProvider {
    fun textFor(error: DomainError): String
}

class ItalianErrorTextProvider : ErrorTextProvider {
    override fun textFor(error: DomainError): String = when (error) {
        DomainError.Network -> "Nessuna connessione. Controlla la rete e riprova."
        DomainError.Timeout -> "Il server non ha risposto in tempo. Riprova."
        is DomainError.Server -> "Errore del servizio (codice ${error.code})."
        is DomainError.Unknown -> error.message
    }
}
