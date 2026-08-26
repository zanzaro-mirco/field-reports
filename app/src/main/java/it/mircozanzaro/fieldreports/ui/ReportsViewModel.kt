package it.mircozanzaro.fieldreports.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.mircozanzaro.fieldreports.domain.Report
import it.mircozanzaro.fieldreports.domain.ReportStatus
import it.mircozanzaro.fieldreports.domain.ReportsRepository
import it.mircozanzaro.fieldreports.domain.onFailure
import it.mircozanzaro.fieldreports.domain.onSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel della schermata rapporti.
 *
 * Dipende dall'interfaccia `ReportsRepository` definita nel dominio, non da
 * una classe concreta del livello dati: è ciò che permette di sostituirla nei
 * test senza passare dall'API sottostante.
 *
 * Il caricamento iniziale non parte dal costruttore ma da [start]: un
 * costruttore con effetti collaterali rende ogni test dipendente dall'ordine
 * di esecuzione, e impedisce di verificare lo stato di partenza.
 */
class ReportsViewModel(
    private val repository: ReportsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ReportsUiState>(ReportsUiState.Loading)

    /**
     * Stato esposto come [StateFlow] e non come [MutableStateFlow]: la UI può
     * osservare ma non scrivere. Il flusso è unidirezionale per costruzione,
     * non per disciplina.
     */
    val uiState: StateFlow<ReportsUiState> = _uiState.asStateFlow()

    private var started: Boolean = false

    /** Avvia il primo caricamento. Idempotente: chiamarla due volte non ricarica. */
    fun start() {
        if (started) return
        started = true
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = ReportsUiState.Loading
            repository.loadReports()
                .onSuccess { reports: List<Report> ->
                    _uiState.value = ReportsUiState.Ready(reports)
                }
                .onFailure { error ->
                    _uiState.value = ReportsUiState.Error(error)
                }
        }
    }

    /** Applica o rimuove il filtro per stato, senza rifare la chiamata. */
    fun setFilter(status: ReportStatus?) {
        _uiState.update { current ->
            if (current is ReportsUiState.Ready) current.copy(filter = status) else current
        }
    }
}
