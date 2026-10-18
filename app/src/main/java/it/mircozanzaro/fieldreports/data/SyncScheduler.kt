package it.mircozanzaro.fieldreports.data

/**
 * Chiede che la sincronizzazione venga ritentata appena torna la rete.
 *
 * Il repository decide *quando* serve — una sincronizzazione fallita per
 * mancanza di rete — e questa interfaccia decide *come*. In produzione è
 * WorkManager, che sopravvive alla chiusura dell'app e aspetta la rete da solo;
 * nei test è una lista che conta le chiamate. Il livello dati non importa
 * niente di WorkManager, come non importa niente di Dagger.
 */
fun interface SyncScheduler {

    /**
     * Idempotente: dieci fallimenti di fila mentre si è senza rete chiedono
     * dieci volte la stessa cosa, e devono produrre un solo tentativo in attesa.
     */
    fun scheduleWhenOnline()
}
