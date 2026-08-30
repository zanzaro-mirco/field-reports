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
 *
 * Con l'arrivo della cache locale un errore ha smesso di essere sempre
 * bloccante, e lo stato lo riflette: [Error] copre lo schermo **solo** quando
 * non c'è nulla da mostrare. Se in cache ci sono dati validi, una
 * sincronizzazione fallita è un avviso sopra dei dati veri — [Ready] con
 * [Ready.refreshError] valorizzato. Modellarlo altrimenti significherebbe
 * nascondere all'utente rapporti che possediamo, ed è esattamente ciò che la
 * cache serve a evitare.
 */
sealed interface ReportsUiState {

    /** Prima apertura: cache vuota e sincronizzazione in corso. */
    data object Loading : ReportsUiState

    data class Ready(
        val reports: List<Report>,
        val filter: ReportStatus? = null,
        val isRefreshing: Boolean = false,
        val refreshError: DomainError? = null,
    ) : ReportsUiState {
        val visibleReports: List<Report>
            get() = filter?.let { f -> reports.filter { it.status == f } } ?: reports

        val isEmpty: Boolean get() = visibleReports.isEmpty()
    }

    /** Cache vuota **e** sincronizzazione fallita: non c'è proprio nulla da mostrare. */
    data class Error(val error: DomainError) : ReportsUiState
}
