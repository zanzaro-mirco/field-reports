package it.mircozanzaro.fieldreports

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import it.mircozanzaro.fieldreports.domain.ReportsRepository
import it.mircozanzaro.fieldreports.ui.ReportsRoute
import it.mircozanzaro.fieldreports.ui.ReportsViewModel

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
                ReportsRoute(
                    viewModel = viewModel(factory = ReportsViewModelFactory(repository)),
                )
            }
        }
    }
}

class ReportsViewModelFactory(
    private val repository: ReportsRepository,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ReportsViewModel::class.java)) {
            "ViewModel non gestito: ${modelClass.name}"
        }
        return ReportsViewModel(repository) as T
    }
}
