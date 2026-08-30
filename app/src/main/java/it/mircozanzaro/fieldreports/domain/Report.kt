package it.mircozanzaro.fieldreports.domain

/** Stato di lavorazione di un rapporto di intervento. */
enum class ReportStatus {
    OPEN,
    IN_PROGRESS,
    CLOSED,
    ;

    companion object {
        /**
         * Interpreta un valore che arriva da fuori: il JSON del backend o una
         * riga scritta in cache da un'altra versione dell'app.
         *
         * Sta qui e non nei singoli mapper perché la regola di degradazione deve
         * essere una sola. Quando esisteva solo il DTO era ragionevole tenerla
         * privata lì; con due adattatori che leggono lo stesso enum, duplicarla
         * significa soltanto sceglierne una che prima o poi divergerà.
         */
        fun fromRaw(raw: String?): ReportStatus =
            when (raw?.uppercase()?.replace('-', '_')) {
                "OPEN" -> OPEN
                "IN_PROGRESS" -> IN_PROGRESS
                "CLOSED" -> CLOSED
                else -> OPEN // valore sconosciuto: si degrada, non si crasha
            }
    }
}

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
