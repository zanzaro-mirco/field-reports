package it.mircozanzaro.fieldreports

import android.content.Context
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

        val primoAvvio = openOnDisk(context)
        RoomReportsLocalStore(primoAvvio).replaceAll(
            listOf(report("R-1041", createdAt = 900), report("R-1042", createdAt = 100)),
            syncedAtEpochMs = 7_000,
        )
        primoAvvio.close()

        val secondoAvvio = openOnDisk(context)
        val store = RoomReportsLocalStore(secondoAvvio)

        assertEquals(
            listOf("R-1041", "R-1042"),
            store.observeReports().first().map(Report::id),
        )
        assertEquals(7_000L, store.lastSyncEpochMs())

        secondoAvvio.close()
        context.deleteDatabase(DB_NAME)
    }

    private fun openOnDisk(context: Context): FieldReportsDatabase = Room
        .databaseBuilder(context, FieldReportsDatabase::class.java, DB_NAME)
        .build()

    private companion object {
        const val DB_NAME = "criterio-di-fatto.db"
    }
}
