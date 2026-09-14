package it.mircozanzaro.fieldreports

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import it.mircozanzaro.fieldreports.domain.ReportsRepository
import it.mircozanzaro.fieldreports.ui.FieldReportsNavHost

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Le dipendenze si prendono dal container dell'Application: l'Activity
        // non costruisce più nulla, perché viene ricreata a ogni rotazione e il
        // database no.
        val repository: ReportsRepository =
            (application as FieldReportsApplication).container.reportsRepository

        setContent {
            MaterialTheme {
                FieldReportsNavHost(repository = repository)
            }
        }
    }
}
