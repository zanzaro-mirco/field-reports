package it.mircozanzaro.fieldreports.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import it.mircozanzaro.fieldreports.domain.Report
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Il dettaglio collegato al suo ViewModel. Come [ReportsRoute] per la lista, è
 * l'unico composable del dettaglio che conosce il ViewModel.
 */
@Composable
fun ReportDetailRoute(
    viewModel: ReportDetailViewModel,
    onBack: () -> Unit,
    onReportRemoved: (reportId: String) -> Unit,
) {
    LaunchedEffect(Unit) { viewModel.start() }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CollectEvents(viewModel.events) { event -> onReportRemoved(event.reportId) }
    ReportDetailScreen(state = state, onBack = onBack)
}

/**
 * Raccoglie un flusso di eventi solo mentre lo schermo è visibile.
 *
 * Tre dettagli, ciascuno con la sua ragione:
 *
 * - `repeatOnLifecycle(STARTED)`: con l'app in secondo piano non si naviga.
 *   L'evento resta nel `Channel` e arriva al ritorno;
 * - `Dispatchers.Main.immediate`: fra il momento in cui l'evento esce dal
 *   canale e quello in cui viene gestito non passa un giro del ciclo
 *   principale, quindi una rotazione non può infilarsi in mezzo e perdere un
 *   evento già preso;
 * - `rememberUpdatedState`: la lambda cambia a ogni ricomposizione, e l'effetto
 *   non deve ripartire — cioè riabbonarsi — per questo.
 */
@Composable
private fun <T> CollectEvents(events: Flow<T>, onEvent: (T) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnEvent by rememberUpdatedState(onEvent)
    LaunchedEffect(events, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            withContext(Dispatchers.Main.immediate) {
                events.collect { event -> currentOnEvent(event) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportDetailScreen(
    state: ReportDetailUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    zoneId: ZoneId = ZoneId.systemDefault(),
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Rapporto") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            is ReportDetailUiState.Loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    Modifier
                        .semantics { contentDescription = "Caricamento del rapporto" }
                        .testTag("loading"),
                )
            }

            is ReportDetailUiState.NotFound -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Questo rapporto non è nella copia salvata sul dispositivo.",
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(24.dp)
                        .testTag("not-found"),
                )
            }

            is ReportDetailUiState.Ready -> ReportDetails(
                report = state.report,
                zoneId = zoneId,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun ReportDetails(report: Report, zoneId: ZoneId, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("report-detail"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text(
                "${report.id} · ${report.status.label()}",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                report.title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.testTag("detail-title"),
            )
        }
        DetailField("Cliente", report.customer.ifBlank { "Non indicato" })
        DetailField("Tecnico", report.technician.ifBlank { "Non indicato" })
        DetailField("Creato il", formatCreatedAt(report.createdAtEpochMs, zoneId))
        DetailField("Descrizione", report.description.ifBlank { "Nessuna descrizione" })
    }
}

@Composable
private fun DetailField(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * La data di creazione, o un testo che dice che manca.
 *
 * Lo zero è il valore con cui `ReportDto.toDomain()` rappresenta una data
 * assente o illeggibile. Formattarlo mostrerebbe «1 gennaio 1970»: un dato
 * falso con l'aria di uno vero, che è peggio di un dato mancante.
 */
internal fun formatCreatedAt(epochMs: Long, zoneId: ZoneId): String =
    if (epochMs == 0L) {
        "Data non disponibile"
    } else {
        createdAtFormat.withZone(zoneId).format(Instant.ofEpochMilli(epochMs))
    }

private val createdAtFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.ITALIAN)
