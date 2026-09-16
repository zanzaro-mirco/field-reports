package it.mircozanzaro.fieldreports.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import it.mircozanzaro.fieldreports.domain.Report
import it.mircozanzaro.fieldreports.domain.ReportsRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel del dettaglio di un rapporto.
 *
 * Legge dalla cache come la lista, e non chiama la rete: un aggiornamento fatto
 * altrove si vede anche qui senza fare niente. Come nella lista, l'osservazione
 * parte da [start] e non dal costruttore.
 *
 * È l'unico punto dell'app da cui parte una navigazione decisa dal ViewModel.
 * Il tocco su una card no: apre il dettaglio direttamente dalla UI, perché non
 * c'è niente da decidere, e un giro fino al ViewModel e ritorno sarebbe solo un
 * posto in più in cui un evento può duplicarsi. Qui invece la decisione è sua —
 * il rapporto aperto è sparito, e lo sa solo chi osserva la cache.
 */
@HiltViewModel
class ReportDetailViewModel(
    private val repository: ReportsRepository,
    private val reportId: String,
) : ViewModel() {

    /**
     * Il costruttore che usa Hilt. L'id arriva dalla rotta tipizzata, che la
     * navigazione copia nel `SavedStateHandle` di questa destinazione: si legge
     * con lo stesso tipo con cui è stato scritto, senza nomi di chiave a mano.
     *
     * Il costruttore primario resta con l'id esplicito, ed è quello dei test del
     * ViewModel: provano l'evento, e un `SavedStateHandle` costruito a mano
     * sarebbe solo un modo più lungo di scrivere `"R-1"`. Che l'id arrivi
     * davvero dalla navigazione lo prova il test sul grafo vero.
     */
    @Inject
    constructor(repository: ReportsRepository, savedStateHandle: SavedStateHandle) : this(
        repository = repository,
        reportId = savedStateHandle.toRoute<ReportDetailDestination>().reportId,
    )

    private val _uiState = MutableStateFlow<ReportDetailUiState>(ReportDetailUiState.Loading)

    val uiState: StateFlow<ReportDetailUiState> = _uiState.asStateFlow()

    /**
     * Un `Channel`, e non uno `StateFlow` né lo `SharedFlow` che il piano
     * prevedeva. I tre differiscono esattamente nel punto che conta, cioè una
     * rotazione dello schermo — che distrugge l'osservatore della UI e ne crea
     * uno nuovo, mentre il ViewModel resta:
     *
     * - uno `StateFlow` conserva l'ultimo valore e lo ridà a ogni osservatore
     *   nuovo: dopo la rotazione l'evento arriva **due volte**;
     * - uno `SharedFlow` senza replay non ridà niente, ma butta ciò che emette
     *   mentre nessuno ascolta — e fra il vecchio osservatore e il nuovo nessuno
     *   ascolta. L'evento arriva **zero volte**;
     * - un `Channel` tiene l'evento finché qualcuno non lo prende, e lo dà a uno
     *   solo.
     *
     * Ognuno dei due casi sbagliati ha il suo test in `ReportDetailViewModelTest`.
     */
    private val _events = Channel<ReportRemoved>(Channel.BUFFERED)

    val events: Flow<ReportRemoved> = _events.receiveAsFlow()

    private var started: Boolean = false

    /** Avvia l'osservazione del rapporto. Idempotente. */
    fun start() {
        if (started) return
        started = true

        viewModelScope.launch {
            repository.observeReport(reportId)
                // Room riemette a ogni scrittura sulla tabella, anche quando
                // questo rapporto non è cambiato. Senza, la sincronizzazione
                // successiva a una rimozione — ancora `null` — produrrebbe un
                // secondo evento.
                .distinctUntilChanged()
                .collect(::onReport)
        }
    }

    private suspend fun onReport(report: Report?) {
        when {
            report != null -> _uiState.value = ReportDetailUiState.Ready(report)

            // C'era e non c'è più. Lo stato resta sull'ultimo rapporto visto:
            // durante l'animazione di uscita lo schermo mostra ancora il suo
            // contenuto, invece di lampeggiare su "non trovato" mentre se ne va.
            _uiState.value is ReportDetailUiState.Ready ->
                _events.send(ReportRemoved(reportId))

            else -> _uiState.value = ReportDetailUiState.NotFound
        }
    }
}
