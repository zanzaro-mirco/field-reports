package it.mircozanzaro.fieldreports.domain

/**
 * Contratto di accesso ai rapporti, definito nel dominio.
 *
 * L'interfaccia sta qui e l'implementazione sta in `data`: è l'inversione
 * delle dipendenze applicata sul serio. Prima il ViewModel dipendeva dalla
 * classe concreta, e la sostituzione nei test funzionava solo perché si
 * cambiava l'API sotto — un livello troppo in basso.
 */
interface ReportsRepository {
    suspend fun loadReports(): Outcome<List<Report>>
}
