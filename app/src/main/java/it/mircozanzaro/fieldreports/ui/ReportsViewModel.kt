package it.mircozanzaro.fieldreports.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.mircozanzaro.fieldreports.domain.DomainError
import it.mircozanzaro.fieldreports.domain.Outcome
import it.mircozanzaro.fieldreports.domain.Report
import it.mircozanzaro.fieldreports.domain.ReportStatus
import it.mircozanzaro.fieldreports.domain.ReportsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
 *
 * Con la cache locale lo stato smette di essere il risultato di una chiamata e
 * diventa la composizione di tre sorgenti indipendenti: i dati (un flusso che
 * arriva dal database), il filtro scelto dall'utente, e l'esito della
 * sincronizzazione in corso. Il [combine] le tiene allineate; nessuna delle tre
 * sa delle altre.
 */
class ReportsViewModel(
    private val repository: ReportsRepository,
) : ViewModel() {

    /**
     * Il filtro vive fuori dallo stato esposto, e non è un dettaglio.
     *
     * Prima era un campo di `Ready` aggiornato con una `copy`. Con una sorgente
     * reattiva quella soluzione si romperebbe in silenzio: la prima emissione
     * del database dopo un aggiornamento ricostruirebbe lo stato da zero e
     * cancellerebbe la scelta dell'utente. Tenuto separato, il filtro
     * sopravvive a qualunque scrittura in cache.
     */
    private val filter = MutableStateFlow<ReportStatus?>(null)

    private val refreshState = MutableStateFlow(RefreshState())

    private val _uiState = MutableStateFlow<ReportsUiState>(ReportsUiState.Loading)

    /**
     * Stato esposto come [StateFlow] e non come [MutableStateFlow]: la UI può
     * osservare ma non scrivere. Il flusso è unidirezionale per costruzione,
     * non per disciplina.
     */
    val uiState: StateFlow<ReportsUiState> = _uiState.asStateFlow()

    private var started: Boolean = false

    /** Avvia osservazione e prima sincronizzazione. Idempotente. */
    fun start() {
        if (started) return
        started = true

        // Prima si dichiara la sincronizzazione in corso, poi si osserva. Al
        // contrario, la primissima emissione del database — cache vuota,
        // nessun aggiornamento ancora dichiarato — verrebbe interpretata come
        // "nessun rapporto": all'avvio l'utente vedrebbe lampeggiare la
        // schermata vuota prima dell'indicatore di caricamento.
        launchRefresh(forced = false)

        viewModelScope.launch {
            combine(
                repository.observeReports(),
                filter,
                refreshState,
            ) { reports, activeFilter, refresh ->
                toUiState(reports, activeFilter, refresh)
            }.collect { state -> _uiState.value = state }
        }
    }

    /** Aggiornamento richiesto dall'utente: si sincronizza comunque. */
    fun refresh() = launchRefresh(forced = true)

    /** Applica o rimuove il filtro per stato, senza toccare la rete. */
    fun setFilter(status: ReportStatus?) {
        filter.value = status
    }

    private fun launchRefresh(forced: Boolean) {
        // Fuori dalla coroutine, quindi immediato: il tocco sul pulsante
        // aggiorna deve avere un riscontro a schermo nello stesso fotogramma,
        // non quando il dispatcher trova il tempo di far partire il lavoro.
        refreshState.update { it.copy(inFlight = true, error = null) }
        viewModelScope.launch {
            val outcome =
                if (forced) repository.refresh() else repository.refreshIfStale()
            refreshState.update { current ->
                when (outcome) {
                    is Outcome.Success -> current.copy(inFlight = false, error = null)
                    is Outcome.Failure -> current.copy(inFlight = false, error = outcome.error)
                }
            }
        }
    }

    /**
     * La regola di composizione, in un posto solo.
     *
     * L'ordine dei rami è la parte che conta: i dati vincono sull'errore. Uno
     * schermo di errore si mostra soltanto quando non esiste alcuna
     * alternativa, cioè quando la cache è vuota.
     */
    private fun toUiState(
        reports: List<Report>,
        filter: ReportStatus?,
        refresh: RefreshState,
    ): ReportsUiState = when {
        reports.isNotEmpty() -> ReportsUiState.Ready(
            reports = reports,
            filter = filter,
            isRefreshing = refresh.inFlight,
            refreshError = refresh.error,
        )

        refresh.error != null -> ReportsUiState.Error(refresh.error)

        refresh.inFlight -> ReportsUiState.Loading

        // Cache vuota, sincronizzazione riuscita: rapporti non ce ne sono
        // davvero. È un risultato valido, non un errore.
        else -> ReportsUiState.Ready(reports = emptyList(), filter = filter)
    }

    private data class RefreshState(
        val inFlight: Boolean = false,
        val error: DomainError? = null,
    )
}
