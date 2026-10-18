package it.mircozanzaro.fieldreports.sync

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import it.mircozanzaro.fieldreports.domain.ReportsRepository
import javax.inject.Provider

/**
 * Costruisce i worker dell'app con le loro dipendenze.
 *
 * È ciò che `hilt-work` genererebbe da un costruttore annotato. Scritta a mano
 * per la stessa ragione per cui tutto il grafo è fatto di `@Provides`: il worker
 * resta una classe che non sa niente di Dagger, e nei test si costruisce con un
 * `new`.
 *
 * Il repository arriva come [Provider]: la factory nasce con l'Application, e il
 * repository — con il database dietro — deve nascere solo quando un worker parte
 * davvero.
 *
 * Restituire `null` per una classe sconosciuta non è un ripiego: è il contratto
 * di [WorkerFactory], che in quel caso costruisce il worker per riflessione.
 */
class ReportsWorkerFactory(
    private val repository: Provider<ReportsRepository>,
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        ReportsSyncWorker::class.java.name ->
            ReportsSyncWorker(appContext, workerParameters, repository.get())

        else -> null
    }
}
