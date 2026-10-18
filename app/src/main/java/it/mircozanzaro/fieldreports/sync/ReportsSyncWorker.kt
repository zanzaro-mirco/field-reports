package it.mircozanzaro.fieldreports.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import it.mircozanzaro.fieldreports.domain.DomainError
import it.mircozanzaro.fieldreports.domain.Outcome
import it.mircozanzaro.fieldreports.domain.ReportsRepository

/**
 * La sincronizzazione rimasta in sospeso, eseguita quando la rete torna.
 *
 * Chiama [ReportsRepository.refreshIfStale] e non `refresh`: se nel frattempo
 * l'utente ha riaperto l'app e aggiornato a mano, la cache è fresca e il lavoro
 * finisce senza toccare la rete. Il worker non deve sapere se è arrivato primo.
 *
 * L'esito si traduce in tre risposte diverse per WorkManager, e la differenza
 * conta:
 * - riuscita → [Result.success], il lavoro è finito;
 * - rete o timeout → [Result.retry], con l'attesa che cresce a ogni giro. Il
 *   vincolo sulla rete era soddisfatto e la richiesta è fallita lo stesso: un
 *   portale di accesso del Wi-Fi, una cella al limite. Ritentare ha senso, ma
 *   non all'infinito — dopo [MAX_ATTEMPTS] si smette, e la prossima apertura
 *   dell'app riproverà comunque;
 * - errore del servizio o risposta illeggibile → [Result.failure]. Un 404 non
 *   diventa un 200 riprovando, e un worker che ritenta per ore un errore che
 *   non può cambiare consuma batteria per niente.
 *
 * Il costruttore è normale, senza annotazioni: lo chiama [ReportsWorkerFactory],
 * e nei test lo chiama il test.
 */
class ReportsSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val repository: ReportsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = when (val outcome = repository.refreshIfStale()) {
        is Outcome.Success -> Result.success()
        is Outcome.Failure -> when (outcome.error) {
            DomainError.Network, DomainError.Timeout ->
                if (runAttemptCount + 1 >= MAX_ATTEMPTS) Result.failure() else Result.retry()

            is DomainError.Server, is DomainError.Unknown -> Result.failure()
        }
    }

    companion object {
        /**
         * Cinque tentativi, con l'attesa che parte da trenta secondi e raddoppia:
         * l'ultimo arriva circa sette minuti e mezzo dopo il primo. Oltre, la
         * rete «c'è ma non funziona» da abbastanza perché insistere non serva.
         */
        const val MAX_ATTEMPTS: Int = 5
    }
}
