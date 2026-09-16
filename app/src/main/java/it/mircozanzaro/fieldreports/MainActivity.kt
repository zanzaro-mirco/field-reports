package it.mircozanzaro.fieldreports

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import dagger.hilt.android.AndroidEntryPoint
import it.mircozanzaro.fieldreports.ui.FieldReportsNavHost
import it.mircozanzaro.fieldreports.ui.FieldReportsTheme

/**
 * `@AndroidEntryPoint` non inietta niente qui dentro: serve perché i ViewModel
 * delle destinazioni si chiedono a Hilt, e Hilt li costruisce solo sotto
 * un'Activity che conosce. L'Activity non costruisce nulla, perché viene
 * ricreata a ogni rotazione e il database no.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            FieldReportsTheme {
                // I benchmark guidano l'app con UiAutomator, che vede le viste e
                // non la semantica di Compose: così i `testTag` diventano id di
                // risorsa, e la lista si trova con `By.res("report-list")`.
                FieldReportsNavHost(Modifier.semantics { testTagsAsResourceId = true })
            }
        }
    }
}
