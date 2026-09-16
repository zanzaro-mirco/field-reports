package it.mircozanzaro.fieldreports.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import it.mircozanzaro.fieldreports.BuildConfig
import it.mircozanzaro.fieldreports.data.DefaultReportsRepository
import it.mircozanzaro.fieldreports.data.DispatcherProvider
import it.mircozanzaro.fieldreports.data.StandardDispatcherProvider
import it.mircozanzaro.fieldreports.data.local.FieldReportsDatabase
import it.mircozanzaro.fieldreports.data.local.ReportsLocalStore
import it.mircozanzaro.fieldreports.data.local.RoomReportsLocalStore
import it.mircozanzaro.fieldreports.data.remote.BenchmarkReportsApi
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
import javax.inject.Provider
import javax.inject.Singleton

/*
 * La composition root: l'unico file dell'app che conosce le classi concrete.
 *
 * Era `AppContainer`, una classe scritta a mano. I tre moduli stanno in un file
 * solo di proposito, perché il pregio di quella classe era che il grafo si
 * leggeva dall'alto in basso in un posto solo, e Hilt non è una ragione per
 * perderlo.
 *
 * Tutti i collegamenti sono `@Provides` e nessuno è un costruttore `@Inject`:
 * così `data/` e `domain/` non importano niente da Dagger, e Hilt è entrato
 * senza toccarli. Costa qualche riga in più di un `@Binds`, e in cambio il
 * livello dati resta compilabile e testabile senza sapere come viene montato.
 *
 * `@Singleton` sta su tre oggetti su otto, e non è avarizia. Vuol dire «questo
 * oggetto ha uno stato che deve essere uno solo»: il database, il client HTTP e
 * il servizio Retrofit ce l'hanno, e ognuno dice quale accanto al suo
 * `@Provides`. Metterlo anche su oggetti che non ne hanno sarebbe dichiarare una
 * cosa falsa, e tenerli in memoria per tutta la vita del processo anche quando
 * nessuno li usa più.
 */

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * `ignoreUnknownKeys` non è pigrizia: la risposta di GitHub ha una
     * quarantina di campi per issue e ne servono cinque. Senza, l'aggiunta di
     * un campo da parte di GitHub — cosa che succede, e senza preavviso —
     * farebbe fallire la deserializzazione di tutta la lista.
     */
    @Provides
    fun json(): Json = Json { ignoreUnknownKeys = true }

    /**
     * Uno solo per processo: tiene il pool delle connessioni e i suoi thread, e
     * due client vorrebbero dire due pool.
     *
     * I timeout sono espliciti perché i default di OkHttp sono dieci secondi
     * per fase, che su una rete mobile lenta significa un'attesa lunga prima di
     * poter dire qualcosa all'utente. Su un terminale da campo la risposta
     * "non ci arrivo" data presto è più utile del dato ottenuto tardi — e la
     * cache locale ha comunque qualcosa da mostrare nel frattempo.
     */
    @Provides
    @Singleton
    fun httpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Uno solo anche questo: Retrofit legge le annotazioni del servizio la
     * prima volta che si chiama un metodo, e le ricorda per istanza. Ricrearlo
     * a ogni ViewModel ripeterebbe quel lavoro a ogni schermata aperta.
     */
    @Provides
    @Singleton
    fun gitHubApi(client: OkHttpClient, json: Json): GitHubApi = Retrofit.Builder()
        .baseUrl("https://api.github.com/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(GitHubApi::class.java)

    /**
     * La sorgente reale.
     *
     * Il repository puntato è quello del progetto stesso: esiste per
     * definizione e non dipende da un servizio di terzi che può sparire.
     *
     * L'unica eccezione sono le build su cui si misurano le prestazioni, e la
     * ragione è scritta dove `BENCHMARK_DATA` viene definito, in
     * `app/build.gradle.kts`. Il `Provider` e non il `GitHubApi` diretto: così
     * quelle build non costruiscono nemmeno il client HTTP.
     */
    @Provides
    fun reportsApi(api: Provider<GitHubApi>): ReportsApi =
        if (BuildConfig.BENCHMARK_DATA) {
            BenchmarkReportsApi()
        } else {
            GitHubReportsApi(
                api = api.get(),
                owner = "zanzaro-mirco",
                repo = "field-reports",
            )
        }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Il database deve vivere quanto il processo, non quanto l'Activity: aperto
     * in `onCreate`, verrebbe ricostruito a ogni rotazione, con una connessione
     * nuova e la cache delle query di quella precedente buttata. È la ragione
     * per cui la composition root era uscita da `MainActivity`, e ora è la
     * ragione di questo `@Singleton`.
     *
     * Cosa succede a un cambio di schema è scritto, e provato, in `open`.
     */
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): FieldReportsDatabase =
        FieldReportsDatabase.open(context)

    @Provides
    fun localStore(database: FieldReportsDatabase): ReportsLocalStore =
        RoomReportsLocalStore(database)
}

/**
 * Il repository in un modulo a sé, perché è quello che i test del grafo di
 * navigazione tolgono e sostituiscono con una cache in memoria.
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    fun dispatchers(): DispatcherProvider = StandardDispatcherProvider()

    /**
     * Senza scope: il repository non tiene stato suo, tutto quello che ricorda
     * sta nella cache. Due istanze leggono e scrivono lo stesso database, e il
     * database è uno.
     */
    @Provides
    fun reportsRepository(
        api: ReportsApi,
        local: ReportsLocalStore,
        dispatchers: DispatcherProvider,
    ): ReportsRepository = DefaultReportsRepository(
        api = api,
        local = local,
        dispatchers = dispatchers,
    )
}
