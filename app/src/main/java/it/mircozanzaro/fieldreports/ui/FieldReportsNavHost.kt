package it.mircozanzaro.fieldreports.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import it.mircozanzaro.fieldreports.domain.ReportsRepository
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** La lista: la destinazione da cui si parte. */
@Serializable
data object ReportListDestination

/**
 * Il dettaglio di un rapporto.
 *
 * L'argomento è l'id e non il rapporto intero. Gli argomenti di navigazione
 * finiscono nello stato salvato dell'Activity, che ha un limite di dimensione;
 * e soprattutto un rapporto copiato lì sarebbe una seconda sorgente, ferma al
 * momento del tocco. Con l'id il dettaglio legge dalla cache, come la lista.
 */
@Serializable
data class ReportDetailDestination(val reportId: String)

/**
 * Il grafo di navigazione dell'app.
 *
 * Riceve il repository e costruisce i ViewModel. È la metà della composition
 * root che deve per forza stare dentro Compose: il ViewModel di una
 * destinazione vive quanto la sua voce nella pila di navigazione, non quanto
 * l'Activity, e solo qui quella voce esiste.
 */
@Composable
fun FieldReportsNavHost(
    repository: ReportsRepository,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    // Sopra il grafo e non dentro la lista, perché il messaggio nasce nel
    // dettaglio e si legge nella lista: quando compare, il dettaglio non c'è già
    // più.
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    NavHost(
        navController = navController,
        startDestination = ReportListDestination,
        modifier = modifier,
    ) {
        composable<ReportListDestination> { entry ->
            ReportsRoute(
                viewModel = viewModel(
                    factory = viewModelFactory { initializer { ReportsViewModel(repository) } },
                ),
                onReportClick = { reportId ->
                    // Il secondo tocco di un doppio tocco arriva quando la lista
                    // sta già uscendo e non è più in primo piano: senza questo
                    // controllo si aprirebbero due dettagli uno sopra l'altro, e
                    // servirebbero due "indietro" per tornare. È quello che fa
                    // `dropUnlessResumed`, scritto a mano perché qui la lambda
                    // riceve un argomento.
                    if (entry.lifecycle.currentState == Lifecycle.State.RESUMED) {
                        navController.navigate(ReportDetailDestination(reportId))
                    }
                },
                snackbarHostState = snackbarHostState,
            )
        }

        composable<ReportDetailDestination> { entry ->
            val destination: ReportDetailDestination = entry.toRoute()
            ReportDetailRoute(
                viewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { ReportDetailViewModel(repository, destination.reportId) }
                    },
                ),
                // Stessa ragione del tocco sulla card: due tocchi su "indietro"
                // toglierebbero anche la lista, e lascerebbero lo schermo vuoto.
                onBack = dropUnlessResumed { navController.popBackStack() },
                onReportRemoved = { reportId ->
                    // Si toglie il dettaglio, non "l'ultima schermata": se nel
                    // frattempo l'utente è già tornato indietro da solo, la lista
                    // resta dov'è.
                    navController.popBackStack<ReportDetailDestination>(inclusive = true)
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            "Il rapporto $reportId non esiste più: l'ultimo aggiornamento lo ha tolto.",
                        )
                    }
                },
            )
        }
    }
}
