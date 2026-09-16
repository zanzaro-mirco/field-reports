package it.mircozanzaro.fieldreports

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import dagger.hilt.android.AndroidEntryPoint
import it.mircozanzaro.fieldreports.ui.FieldReportsNavHost

/**
 * `@AndroidEntryPoint` non inietta niente qui dentro: serve perché i ViewModel
 * delle destinazioni si chiedono a Hilt, e Hilt li costruisce solo sotto
 * un'Activity che conosce. L'Activity non costruisce nulla, perché viene
 * ricreata a ogni rotazione e il database no.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                FieldReportsNavHost()
            }
        }
    }
}
