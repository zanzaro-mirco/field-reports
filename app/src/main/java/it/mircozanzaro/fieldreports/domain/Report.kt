package it.mircozanzaro.fieldreports.domain

/** Stato di lavorazione di un rapporto di intervento. */
enum class ReportStatus { OPEN, IN_PROGRESS, CLOSED }

/**
 * Rapporto di intervento tecnico.
 *
 * Modello di dominio puro: nessuna annotazione di serializzazione, nessun
 * riferimento al framework, nessuna conoscenza del formato di rete. Il DTO che
 * arriva dall'API è una classe separata: se cambia il contratto del backend,
 * cambia il mapper e non il dominio.
 */
data class Report(
    val id: String,
    val title: String,
    val customer: String,
    val status: ReportStatus,
    val createdAtEpochMs: Long,
    val technician: String,
)
