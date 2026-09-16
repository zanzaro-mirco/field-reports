package it.mircozanzaro.fieldreports

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import it.mircozanzaro.fieldreports.data.local.InMemoryReportsLocalStore
import it.mircozanzaro.fieldreports.di.RepositoryModule
import it.mircozanzaro.fieldreports.domain.ReportsRepository
import it.mircozanzaro.fieldreports.ui.FieldReportsNavHost
import it.mircozanzaro.fieldreports.ui.FieldReportsTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Il grafo di navigazione vero, con i ViewModel veri, su una cache in memoria.
 *
 * La rotazione è `StateRestorationTester`: distrugge la composizione e la
 * ricrea dallo stato salvato, mentre i ViewModel sopravvivono — che è
 * esattamente ciò che fa Android quando si ruota lo schermo. Gira con
 * Robolectric, quindi in CI e senza emulatore.
 *
 * I ViewModel li costruisce Hilt, con il grafo vero dell'app meno un modulo:
 * `RepositoryModule` è tolto, e al suo posto c'è la cache in memoria di
 * [cache]. È anche la prova che la rotta arriva davvero al `SavedStateHandle`
 * del dettaglio: se l'id non ci fosse, il dettaglio non troverebbe il rapporto.
 */
@HiltAndroidTest
@UninstallModules(RepositoryModule::class)
@Config(application = HiltTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class NavigationTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    private val cache = CacheOnlyReportsRepository(
        InMemoryReportsLocalStore(
            initialReports = listOf(
                sampleReport("R-1", title = "Sostituzione contatore", createdAtEpochMs = 200),
                sampleReport("R-2", title = "Verifica lettore RFID", createdAtEpochMs = 100),
            ),
            initialSyncEpochMs = 1,
        ),
    )

    /** Ciò che Hilt inietta al posto del repository vero. */
    @BindValue
    @JvmField
    val repository: ReportsRepository = cache

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun assertOnList() {
        compose.onNodeWithTag("report-list").assertIsDisplayed()
        compose.onNodeWithTag("report-detail").assertDoesNotExist()
    }

    @Test
    fun `toccare un rapporto apre il suo dettaglio, e indietro torna alla lista`() {
        compose.setContent { FieldReportsTheme { FieldReportsNavHost() } }

        compose.onNodeWithTag("report-R-2").performClick()
        compose.onNodeWithTag("detail-title").assertTextEquals("Verifica lettore RFID")

        pressBack()
        assertOnList()
    }

    @Test
    fun `ruotare sul dettaglio non ripete la navigazione`() {
        // Il criterio di fatto della voce. Se la rotazione ripetesse la
        // navigazione, sotto il dettaglio ce ne sarebbe un secondo, e un solo
        // "indietro" non basterebbe per tornare alla lista.
        val restoration = StateRestorationTester(compose)
        restoration.setContent { FieldReportsTheme { FieldReportsNavHost() } }

        compose.onNodeWithTag("report-R-2").performClick()
        compose.onNodeWithTag("report-detail").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag("detail-title").assertTextEquals("Verifica lettore RFID")
        pressBack()
        assertOnList()
    }

    @Test
    fun `un doppio tocco sulla card apre un dettaglio solo`() {
        compose.setContent { FieldReportsTheme { FieldReportsNavHost() } }

        // La prima versione mandava due tocchi come sequenza di input, e non
        // provava niente: tolto il controllo sullo stato della lista, restava
        // verde. Qui l'azione di click della card si invoca due volte senza
        // fotogrammi in mezzo, che è il caso peggiore di un dito vero — e senza
        // il controllo il test fallisce.
        val click = compose.onNodeWithTag("report-R-2")
            .fetchSemanticsNode()
            .config[SemanticsActions.OnClick]
            .action!!
        compose.runOnUiThread {
            click()
            click()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("report-detail").assertIsDisplayed()

        pressBack()
        assertOnList()
    }

    @Test
    fun `un doppio tocco su indietro non toglie anche la lista`() {
        // Il secondo "indietro" arriverebbe con la lista già in cima alla pila:
        // la toglierebbe, e lo schermo resterebbe vuoto.
        compose.setContent { FieldReportsTheme { FieldReportsNavHost() } }
        compose.onNodeWithTag("report-R-2").performClick()

        compose.onNodeWithTag("back").performTouchInput {
            click()
            advanceEventTime(100)
            click()
        }

        assertOnList()
    }

    @Test
    fun `un rapporto rimosso riporta alla lista una volta sola, anche ruotando`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { FieldReportsTheme { FieldReportsNavHost() } }
        compose.onNodeWithTag("report-R-2").performClick()
        compose.onNodeWithTag("report-detail").assertIsDisplayed()

        // Una sincronizzazione arriva e R-2 non c'è più.
        runBlocking {
            cache.store.replaceAll(
                listOf(sampleReport("R-1", title = "Sostituzione contatore", createdAtEpochMs = 200)),
                syncedAtEpochMs = 2,
            )
        }
        compose.waitForIdle()

        assertOnList()
        compose.onNodeWithTag("report-R-2").assertDoesNotExist()
        compose.onNodeWithText("Il rapporto R-2 non esiste più", substring = true)
            .assertIsDisplayed()

        // Dopo la rotazione si resta sulla lista. Questo test non distingue il
        // canale su cui viaggia l'evento: il dettaglio viene tolto mentre lo
        // gestisce, e dopo non c'è più nessuno che possa riceverlo di nuovo.
        // Verificato mettendo al posto del Channel uno StateFlow e uno
        // SharedFlow, con il test rimasto verde. Quella differenza la vedono i
        // test del ViewModel.
        restoration.emulateSavedInstanceStateRestore()
        assertOnList()
    }
}
