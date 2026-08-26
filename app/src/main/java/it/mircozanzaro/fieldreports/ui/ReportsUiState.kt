package it.mircozanzaro.fieldreports.ui

import it.mircozanzaro.fieldreports.domain.DomainError
import it.mircozanzaro.fieldreports.domain.Report
import it.mircozanzaro.fieldreports.domain.ReportStatus

/**
 * Stato della schermata, modellato come gerarchia chiusa.
 *
 * Una `sealed interface` rende il `when` esaustivo senza `else`: se domani si
 * aggiunge uno stato, il compilatore segnala ogni punto da aggiornare invece
 * di lasciare un ramo silenziosamente scoperto. È anche ciò che rende
 * impossibili gli stati contraddittori del tipo "sto caricando e ho un errore".
 */
sealed interface ReportsUiState {
    data object Loading : ReportsUiState

    data class Ready(
        val reports: List<Report>,
        val filter: ReportStatus? = null,
    ) : ReportsUiState {
        val visibleReports: List<Report>
            get() = filter?.let { f -> reports.filter { it.status == f } } ?: reports

        val isEmpty: Boolean get() = visibleReports.isEmpty()
    }

    data class Error(val error: DomainError) : ReportsUiState
}
