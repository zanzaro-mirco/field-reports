package it.mircozanzaro.fieldreports

import android.app.Application
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import it.mircozanzaro.fieldreports.sync.ReportsWorkerFactory
import javax.inject.Inject

/**
 * Tiene in vita il grafo delle dipendenze per tutta la durata del processo.
 *
 * Prima lo faceva con un `by lazy` su un container scritto a mano, perché
 * aprire il database prima che qualcuno lo chieda allunga l'avvio a freddo. Con
 * Hilt la proprietà resta, senza scriverla: il componente nasce qui, ma ogni
 * oggetto del grafo viene costruito la prima volta che qualcuno lo chiede — e il
 * database lo chiede il primo ViewModel, non l'Application.
 *
 * È anche il posto dove WorkManager prende la sua configurazione, perché è
 * l'unico oggetto che esiste sia quando l'app è aperta sia quando il sistema la
 * sveglia solo per far girare un worker. L'inizializzazione automatica di
 * WorkManager è tolta nel manifest: partirebbe prima di Hilt, con la factory
 * predefinita, e il worker non saprebbe come costruirsi. La factory qui dentro
 * non apre il database: lo fa il primo worker che parte.
 */
@HiltAndroidApp
class FieldReportsApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: ReportsWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
