package it.mircozanzaro.fieldreports.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Quando è avvenuta l'ultima sincronizzazione riuscita.
 *
 * Tabella a riga singola invece di una colonna su ogni rapporto. La differenza
 * conta nel caso limite: se il server risponde con una lista vuota — perché
 * davvero non ci sono rapporti — un timestamp per-riga non verrebbe scritto da
 * nessuna parte, e la cache risulterebbe "mai aggiornata" per sempre. L'app
 * richiamerebbe la rete a ogni apertura senza mai smettere.
 *
 * Il timestamp descrive la sincronizzazione, non il rapporto: sta quindi in una
 * tabella sua.
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = SINGLE_ROW_ID,
    val lastSyncEpochMs: Long,
) {
    companion object {
        /** Chiave costante: la tabella ha per costruzione una riga sola. */
        const val SINGLE_ROW_ID: Int = 0
    }
}
