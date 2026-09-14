package it.mircozanzaro.fieldreports.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Il database locale.
 *
 * `exportSchema = true` scrive lo schema in `app/schemas/` e quei file vanno
 * versionati: sono il riferimento rispetto a cui si scrive una migrazione.
 *
 * **Versione 2**: la colonna `description`, per la schermata di dettaglio. È
 * il primo cambio di schema, ed è il giorno in cui la scelta dichiarata in
 * [open] smette di essere teorica.
 */
@Database(
    entities = [ReportEntity::class, SyncStateEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class FieldReportsDatabase : RoomDatabase() {

    abstract fun reportDao(): ReportDao

    companion object {
        const val NAME: String = "field-reports.db"

        /**
         * Il database su file, come lo apre l'app.
         *
         * Sta qui e non nella composition root perché il suo comportamento a un
         * cambio di schema va provato, e un test non può costruire
         * `AppContainer` senza portarsi dietro la rete.
         *
         * `fallbackToDestructiveMigration()` è una scelta consapevole, non
         * pigrizia: questa è una cache, e tutto ciò che contiene è ricostruibile
         * con una chiamata di rete. Buttare il database a un cambio di schema
         * costa un caricamento in più e risparmia una migrazione scritta per
         * nulla. Si butta **tutto**, compresa la data dell'ultima
         * sincronizzazione — ed è ciò che rende la scelta sicura: una cache vuota
         * con una data fresca sarebbe creduta valida, e l'app mostrerebbe una
         * lista vuota fino alla scadenza. Il giorno in cui l'app permetterà di
         * scrivere rapporti, questa riga diventa un bug.
         */
        fun open(context: Context, name: String = NAME): FieldReportsDatabase = Room
            .databaseBuilder(context.applicationContext, FieldReportsDatabase::class.java, name)
            .fallbackToDestructiveMigration()
            .build()
    }
}
