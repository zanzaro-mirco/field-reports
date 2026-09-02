package it.mircozanzaro.fieldreports.data.remote

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * L'endpoint di GitHub, dichiarato invece che scritto.
 *
 * È il vantaggio di Retrofit che conta davvero: il contratto verso il server è
 * un'interfaccia leggibile in dieci secondi, e la costruzione della richiesta
 * non è codice che qualcuno deve mantenere.
 *
 * Nessun parametro ha un valore di default: Retrofit genera l'implementazione
 * dell'interfaccia e non sa cosa farsene dei default di Kotlin, che il
 * compilatore trasforma in un metodo sintetico separato. I valori stanno nel
 * chiamante, [GitHubReportsApi].
 */
interface GitHubApi {

    @GET("repos/{owner}/{repo}/issues")
    suspend fun issues(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("state") state: String,
        @Query("per_page") perPage: Int,
    ): List<GitHubIssueDto>
}
