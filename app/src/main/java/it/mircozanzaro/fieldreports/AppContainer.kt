package it.mircozanzaro.fieldreports

import android.content.Context
import androidx.room.Room
import it.mircozanzaro.fieldreports.data.DefaultReportsRepository
import it.mircozanzaro.fieldreports.data.StandardDispatcherProvider
import it.mircozanzaro.fieldreports.data.local.FieldReportsDatabase
import it.mircozanzaro.fieldreports.data.local.RoomReportsLocalStore
import it.mircozanzaro.fieldreports.data.remote.GitHubApi
import it.mircozanzaro.fieldreports.data.remote.GitHubReportsApi
import it.mircozanzaro.fieldreports.data.remote.ReportsApi
import it.mircozanzaro.fieldreports.domain.ReportsRepository
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

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

    /**
     * `ignoreUnknownKeys` non è pigrizia: la risposta di GitHub ha una
     * quarantina di campi per issue e ne servono cinque. Senza, l'aggiunta di
     * un campo da parte di GitHub — cosa che succede, e senza preavviso —
     * farebbe fallire la deserializzazione di tutta la lista.
     */
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * I timeout sono espliciti perché i default di OkHttp sono dieci secondi
     * per fase, che su una rete mobile lenta significa un'attesa lunga prima di
     * poter dire qualcosa all'utente. Su un terminale da campo la risposta
     * "non ci arrivo" data presto è più utile del dato ottenuto tardi — e la
     * cache locale ha comunque qualcosa da mostrare nel frattempo.
     */
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gitHubApi: GitHubApi = Retrofit.Builder()
        .baseUrl("https://api.github.com/")
        .client(httpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(GitHubApi::class.java)

    /**
     * La sorgente reale.
     *
     * Qui c'era `FakeReportsApi()`, e la sostituzione è **questa riga**: il
     * repository, il mapper, il ViewModel e la schermata non sanno che è
     * cambiato qualcosa. È il senso di avere una composition root — il punto in
     * cui si sceglie l'implementazione concreta è uno solo, e si vede.
     *
     * Il repository puntato è quello del progetto stesso: esiste per
     * definizione e non dipende da un servizio di terzi che può sparire.
     */
    private val reportsApi: ReportsApi = GitHubReportsApi(
        api = gitHubApi,
        owner = "zanzaro-mirco",
        repo = "field-reports",
    )

    val reportsRepository: ReportsRepository = DefaultReportsRepository(
        api = reportsApi,
        local = RoomReportsLocalStore(database),
        dispatchers = StandardDispatcherProvider(),
    )
}
