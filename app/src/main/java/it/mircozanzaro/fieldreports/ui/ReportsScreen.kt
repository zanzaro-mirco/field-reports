package it.mircozanzaro.fieldreports.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.mircozanzaro.fieldreports.domain.DomainError
import it.mircozanzaro.fieldreports.domain.Report
import it.mircozanzaro.fieldreports.domain.ReportStatus

/**
 * Schermata collegata al ViewModel.
 *
 * È l'unico composable che conosce il ViewModel: tutto il resto riceve dati e
 * risale gli eventi (state hoisting). Così i componenti si testano e si
 * visualizzano in anteprima senza dipendenze.
 */
@Composable
fun ReportsRoute(
    viewModel: ReportsViewModel,
    errorTextProvider: ErrorTextProvider = ItalianErrorTextProvider(),
) {
    LaunchedEffect(Unit) { viewModel.start() }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ReportsScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onFilterChange = viewModel::setFilter,
        errorText = errorTextProvider::textFor,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    state: ReportsUiState,
    onRefresh: () -> Unit,
    onFilterChange: (ReportStatus?) -> Unit,
    errorText: (DomainError) -> String,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Rapporti di intervento") },
                actions = {
                    IconButton(onClick = onRefresh, modifier = Modifier.testTag("refresh")) {
                        Icon(Icons.Default.Refresh, contentDescription = "Aggiorna")
                    }
                },
            )
        },
    ) { padding: PaddingValues ->
        when (state) {
            is ReportsUiState.Loading -> CenteredBox {
                CircularProgressIndicator(Modifier.testTag("loading"))
            }

            is ReportsUiState.Error -> CenteredBox {
                Text(errorText(state.error), modifier = Modifier.testTag("error"))
            }

            is ReportsUiState.Ready -> Column(Modifier.padding(padding)) {
                if (state.isRefreshing) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("refreshing"),
                    )
                }
                state.refreshError?.let { error ->
                    StaleDataBanner(message = errorText(error))
                }
                StatusFilterRow(selected = state.filter, onSelect = onFilterChange)
                if (state.isEmpty) {
                    CenteredBox { Text("Nessun rapporto") }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.testTag("report-list"),
                    ) {
                        items(state.visibleReports, key = Report::id) { report ->
                            ReportCard(report)
                        }
                    }
                }
            }
        }
    }
}

/**
 * L'avviso che compare sopra dati validi ma non aggiornati.
 *
 * È la traduzione a schermo della scelta fatta nello stato: la
 * sincronizzazione è fallita, i rapporti che si vedono vengono dalla cache.
 * Dirlo è necessario — dati vecchi mostrati come freschi sono peggio di nessun
 * dato — ma non è una ragione per svuotare lo schermo.
 */
@Composable
private fun StaleDataBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("offline-banner"),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Stai vedendo l'ultima copia salvata sul dispositivo.",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusFilterRow(
    selected: ReportStatus?,
    onSelect: (ReportStatus?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ReportStatus.entries.forEach { status ->
            FilterChip(
                selected = selected == status,
                onClick = { onSelect(if (selected == status) null else status) },
                label = { Text(status.label()) },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

@Composable
private fun ReportCard(report: Report) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(report.title, style = MaterialTheme.typography.titleMedium)
            Text(report.customer, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${report.id} · ${report.technician} · ${report.status.label()}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

private fun ReportStatus.label(): String = when (this) {
    ReportStatus.OPEN -> "Aperti"
    ReportStatus.IN_PROGRESS -> "In corso"
    ReportStatus.CLOSED -> "Chiusi"
}
