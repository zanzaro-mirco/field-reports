package it.mircozanzaro.fieldreports.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Il database locale.
 *
 * `exportSchema = true` scrive lo schema in `app/schemas/` e quel file va
 * versionato: è il riferimento rispetto a cui si scriverà la migrazione quando
 * esisterà una versione 2. Senza, la prima migrazione va scritta a memoria.
 */
@Database(
    entities = [ReportEntity::class, SyncStateEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class FieldReportsDatabase : RoomDatabase() {

    abstract fun reportDao(): ReportDao

    companion object {
        const val NAME: String = "field-reports.db"
    }
}
