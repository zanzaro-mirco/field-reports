package it.mircozanzaro.fieldreports.sync

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import it.mircozanzaro.fieldreports.data.SyncScheduler
import java.util.concurrent.TimeUnit

/**
 * Il [SyncScheduler] di produzione: un lavoro unico, che parte solo con la rete.
 *
 * Tre scelte, ognuna con il suo perché:
 * - **un lavoro solo, con un nome, e `KEEP`.** Dieci fallimenti di fila senza
 *   rete lasciano in coda un tentativo, non dieci. E vale mentre il worker sta
 *   girando: se fallisce, il repository chiede di nuovo, e la richiesta viene
 *   ignorata — decide il worker, con il suo `retry`. `REPLACE` sembrerebbe
 *   equivalente e non lo è: sostituirebbe il worker in corso con uno nuovo, il
 *   conto dei tentativi ripartirebbe da zero, e il limite di
 *   [ReportsSyncWorker.MAX_ATTEMPTS] non scatterebbe mai;
 * - **il vincolo sulla rete.** È lui che fa partire il lavoro quando la rete
 *   torna: il sistema tiene il compito fermo finché il vincolo non è
 *   soddisfatto, anche con l'app chiusa, anche dopo un riavvio del telefono;
 * - **l'attesa esponenziale da trenta secondi**, scritta anche se è il valore
 *   predefinito: chi legge non deve conoscere i default di WorkManager per
 *   sapere ogni quanto si riprova.
 *
 * Il [WorkManager] arriva come funzione e non come istanza: ottenerlo lo
 * inizializza, e nessuno deve pagarlo finché una sincronizzazione non fallisce.
 */
class WorkManagerSyncScheduler(
    private val workManager: () -> WorkManager,
) : SyncScheduler {

    override fun scheduleWhenOnline() {
        val request = OneTimeWorkRequestBuilder<ReportsSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager().enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        /** Il nome con cui WorkManager ritrova il lavoro, nell'app e nei test. */
        const val UNIQUE_WORK_NAME: String = "reports-sync"
    }
}
