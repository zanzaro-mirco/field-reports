package it.mircozanzaro.fieldreports.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Accesso SQL ai rapporti.
 *
 * [observeAll] restituisce un [Flow]: Room notifica da sé ogni scrittura sulla
 * tabella, quindi non serve alcun meccanismo di invalidazione scritto a mano.
 * È la ragione tecnica per cui la cache può fare da sorgente unica — senza
 * questa notifica, la UI resterebbe ferma dopo un aggiornamento di rete.
 *
 * L'ordinamento è in `ORDER BY` e non più in Kotlin: ordinare in memoria una
 * lista appena letta dal database significa leggerla due volte.
 */
@Dao
interface ReportDao {

    @Query("SELECT * FROM reports ORDER BY created_at_epoch_ms DESC")
    fun observeAll(): Flow<List<ReportEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(reports: List<ReportEntity>)

    @Query("DELETE FROM reports")
    suspend fun deleteAll()

    @Query("SELECT lastSyncEpochMs FROM sync_state WHERE id = :id")
    suspend fun lastSyncEpochMs(id: Int): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSyncState(state: SyncStateEntity)
}
