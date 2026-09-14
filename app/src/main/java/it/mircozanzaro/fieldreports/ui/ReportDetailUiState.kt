package it.mircozanzaro.fieldreports.ui

import it.mircozanzaro.fieldreports.domain.Report

/**
 * Stato della schermata di dettaglio.
 *
 * Manca apposta un caso "rimosso". Un rapporto che qui non c'è mai stato e uno
 * che sparisce mentre lo si guarda sembrano la stessa situazione, e non lo
 * sono: la differenza non sta nei dati ma in cosa deve fare lo schermo. Nel
 * primo caso resta e spiega ([NotFound]); nel secondo se ne va, una volta — e
 * andarsene è un evento, non uno stato. Vedi [ReportDetailViewModel.events].
 */
sealed interface ReportDetailUiState {

    data object Loading : ReportDetailUiState

    data class Ready(val report: Report) : ReportDetailUiState

    /**
     * L'id non è nella cache fin dalla prima lettura. Succede quando la
     * navigazione viene ripristinata su un rapporto che nel frattempo la cache
     * non ha più: l'app chiusa dal sistema sul dettaglio, e riaperta dopo che la
     * cache è cambiata.
     */
    data object NotFound : ReportDetailUiState
}

/** Il rapporto aperto è stato tolto dalla cache mentre lo si guardava. */
data class ReportRemoved(val reportId: String)
