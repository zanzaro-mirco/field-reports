package it.mircozanzaro.fieldreports

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import it.mircozanzaro.fieldreports.data.local.FieldReportsDatabase
import it.mircozanzaro.fieldreports.data.local.ReportsLocalStore
import it.mircozanzaro.fieldreports.data.local.RoomReportsLocalStore
import it.mircozanzaro.fieldreports.domain.Report
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Lo **stesso** contratto, verificato sul DAO Room vero.
 *
 * Gira con Robolectric su SQLite in memoria, quindi dentro `./gradlew test` e
 * quindi in CI: nessun emulatore, nessun job aggiuntivo nella pipeline. È
 * quello che rende sensato aver messo Room dietro un'interfaccia — senza questa
 * classe, lo schema, le query e la transazione sarebbero coperti solo dalla
 * prova manuale.
 *
 * Il database vero è anche l'unico posto in cui si vedono gli errori che lo
 * store in memoria non può avere: una colonna scritta con un nome e letta con
 * un altro, un `ORDER BY` sbagliato, una chiave primaria che non deduplica.
 */
@RunWith(RobolectricTestRunner::class)
class RoomReportsLocalStoreTest : ReportsLocalStoreContract() {

    private val databases = mutableListOf<FieldReportsDatabase>()

    override fun createStore(): ReportsLocalStore {
        val context: Context = ApplicationProvider.getApplicationContext()
        val database = Room
            .inMemoryDatabaseBuilder(context, FieldReportsDatabase::class.java)
            .build()
        databases += database
        return RoomReportsLocalStore(database)
    }

    @After
    fun closeDatabases() {
        databases.forEach(FieldReportsDatabase::close)
        databases.clear()
    }

    /**
     * Il criterio di fatto dello sviluppo, in forma di test.
     *
     * "Spegni la rete, chiudi e riapri l'app: i rapporti ci sono ancora." Le
     * altre prove usano un database in memoria, che sopravvive quanto il test:
     * verificano il contratto, non la persistenza. Questa scrive su file, chiude
     * la connessione e ne apre una nuova — che è ciò che succede fra due avvii
     * dell'app.
     */
    @Test
    fun `i rapporti sopravvivono alla chiusura del database`() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)

        val firstLaunch = openOnDisk(context)
        RoomReportsLocalStore(firstLaunch).replaceAll(
            listOf(report("R-1041", createdAt = 900), report("R-1042", createdAt = 100)),
            syncedAtEpochMs = 7_000,
        )
        firstLaunch.close()

        val secondLaunch = openOnDisk(context)
        val store = RoomReportsLocalStore(secondLaunch)

        assertEquals(
            listOf("R-1041", "R-1042"),
            store.observeReports().first().map(Report::id),
        )
        assertEquals(7_000L, store.lastSyncEpochMs())

        secondLaunch.close()
        context.deleteDatabase(DB_NAME)
    }

    /**
     * Il primo cambio di schema, provato su un file scritto come lo lasciava la
     * versione precedente dell'app.
     *
     * Il database si apre con `FieldReportsDatabase.open`, cioè come lo apre
     * l'app, e non con un costruttore scritto per il test: è la scelta di
     * buttare la cache che si sta verificando, e sta lì dentro.
     *
     * L'asserzione che conta è la seconda. Una cache vuota con la data di
     * sincronizzazione ancora fresca verrebbe creduta valida, e l'app
     * mostrerebbe "Nessun rapporto" fino alla scadenza invece di riscaricarli.
     */
    @Test
    fun `una cache della versione 1 si butta intera, data di sincronizzazione compresa`() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(OLD_SCHEMA_DB_NAME)
        val file = context.getDatabasePath(OLD_SCHEMA_DB_NAME).apply { parentFile?.mkdirs() }

        // Lo schema 1, copiato da `app/schemas/.../1.json`: senza la colonna
        // `description`, con un rapporto e una sincronizzazione appena fatta.
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE `reports` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                    "`customer` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                    "`created_at_epoch_ms` INTEGER NOT NULL, `technician` TEXT NOT NULL, " +
                    "PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE TABLE `sync_state` (`id` INTEGER NOT NULL, " +
                    "`lastSyncEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "INSERT INTO reports VALUES " +
                    "('R-1041', 'Sostituzione contatore', 'Acquedotto Nord', 'OPEN', 900, 'M. Rossi')",
            )
            db.execSQL("INSERT INTO sync_state VALUES (0, 7000)")
            db.version = 1
        }

        val database = FieldReportsDatabase.open(context, OLD_SCHEMA_DB_NAME)
        val store = RoomReportsLocalStore(database)

        assertEquals(emptyList<Report>(), store.observeReports().first())
        assertNull(store.lastSyncEpochMs())

        database.close()
        context.deleteDatabase(OLD_SCHEMA_DB_NAME)
    }

    private fun openOnDisk(context: Context): FieldReportsDatabase = Room
        .databaseBuilder(context, FieldReportsDatabase::class.java, DB_NAME)
        .build()

    private companion object {
        const val DB_NAME = "criterio-di-fatto.db"
        const val OLD_SCHEMA_DB_NAME = "schema-1.db"
    }
}
