package it.mircozanzaro.fieldreports

import android.content.Context
import androidx.room.Room
import it.mircozanzaro.fieldreports.data.DefaultReportsRepository
import it.mircozanzaro.fieldreports.data.StandardDispatcherProvider
import it.mircozanzaro.fieldreports.data.local.FieldReportsDatabase
import it.mircozanzaro.fieldreports.data.local.RoomReportsLocalStore
import it.mircozanzaro.fieldreports.data.remote.FakeReportsApi
import it.mircozanzaro.fieldreports.domain.ReportsRepository

/**
 * Composition root: l'unico punto dell'app che conosce le classi concrete.
 *
 * È uscito da `MainActivity` quando è arrivato il database, per una ragione
 * concreta e non stilistica: `Room.databaseBuilder` in `onCreate` verrebbe
 * eseguito a ogni rotazione dello schermo, aprendo una connessione nuova e
 * buttando la cache delle query di quella precedente. Il database deve vivere
 * quanto il processo, non quanto l'Activity.
 *
 * Resta una classe scritta a mano invece di Hilt: il grafo è di quattro
 * oggetti. Hilt si giustifica quando la costruzione a mano diventa il problema,
 * e non prima.
 */
class AppContainer(context: Context) {

    private val database: FieldReportsDatabase = Room
        .databaseBuilder(
            context.applicationContext,
            FieldReportsDatabase::class.java,
            FieldReportsDatabase.NAME,
        )
        // Scelta consapevole, non pigrizia: questa è una cache, e tutto ciò che
        // contiene è ricostruibile con una chiamata di rete. Finché non
        // esisteranno dati creati sul dispositivo e non ancora sincronizzati,
        // buttare il database a un cambio di schema costa un caricamento in
        // più e risparmia una migrazione scritta per nulla. Il giorno in cui
        // l'app permetterà di scrivere rapporti, questa riga diventa un bug.
        .fallbackToDestructiveMigration()
        .build()

    val reportsRepository: ReportsRepository = DefaultReportsRepository(
        api = FakeReportsApi(),
        local = RoomReportsLocalStore(database),
        dispatchers = StandardDispatcherProvider(),
    )
}
