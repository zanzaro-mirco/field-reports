package it.mircozanzaro.fieldreports.domain

import kotlinx.coroutines.flow.Flow

/**
 * Contratto di accesso ai rapporti, definito nel dominio.
 *
 * L'interfaccia sta qui e l'implementazione sta in `data`: è l'inversione
 * delle dipendenze applicata sul serio. Prima il ViewModel dipendeva dalla
 * classe concreta, e la sostituzione nei test funzionava solo perché si
 * cambiava l'API sotto — un livello troppo in basso.
 *
 * Con l'arrivo della cache locale il contratto si sdoppia, e non per gusto:
 * **leggere** e **sincronizzare** hanno cicli di vita diversi. La lettura è un
 * flusso continuo che vive quanto la schermata; la sincronizzazione è
 * un'operazione singola che può fallire. Un unico `suspend fun loadReports()`
 * costringeva a confonderle, e obbligava la UI a chiedere i dati alla rete.
 *
 * Da qui in poi il repository è single-source-of-truth: la sola sorgente che la
 * UI osserva è la cache locale, e la rete si limita ad aggiornarla.
 */
interface ReportsRepository {

    /**
     * I rapporti in cache, dal più recente. Emette a ogni scrittura: chi
     * osserva non deve richiedere nulla dopo un [refresh].
     */
    fun observeReports(): Flow<List<Report>>

    /** Sincronizza sempre. È il gesto esplicito dell'utente sul pulsante aggiorna. */
    suspend fun refresh(): Outcome<Unit>

    /**
     * Sincronizza solo se i dati in cache sono invecchiati. È l'avvio della
     * schermata: riaprire l'app tre volte in un minuto non deve valere tre
     * chiamate di rete.
     */
    suspend fun refreshIfStale(): Outcome<Unit>
}
