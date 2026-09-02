package it.mircozanzaro.fieldreports.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Una issue come la restituisce l'API di GitHub.
 *
 * Tutti i campi sono nullabili con un default, per la stessa ragione per cui lo
 * è [ReportDto]: un DTO deve poter rappresentare anche una risposta imperfetta.
 * Un campo obbligatorio che manca farebbe fallire l'intera deserializzazione
 * della lista, cioè trasformerebbe un dato mancante in una schermata di errore.
 *
 * `ignoreUnknownKeys` è attivo nel `Json` configurato nella composition root:
 * la risposta di GitHub ha una quarantina di campi e ce ne servono cinque.
 * Elencarli tutti per poi ignorarli sarebbe lavoro speso per invecchiare male.
 */
@Serializable
data class GitHubIssueDto(
    val number: Int? = null,
    val title: String? = null,
    val state: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val user: GitHubUserDto? = null,
    val labels: List<GitHubLabelDto> = emptyList(),
    @SerialName("repository_url") val repositoryUrl: String? = null,
    /**
     * Presente **solo** sulle pull request.
     *
     * L'endpoint delle issue restituisce anche le PR, e non c'è un campo che
     * dica "sono una PR": c'è questo oggetto, che sulle issue vere non esiste.
     * Il contenuto non interessa, interessa la presenza — da qui il tipo
     * minimo invece della struttura completa.
     */
    @SerialName("pull_request") val pullRequest: GitHubPullRequestDto? = null,
)

@Serializable
data class GitHubUserDto(val login: String? = null)

@Serializable
data class GitHubLabelDto(val name: String? = null)

@Serializable
class GitHubPullRequestDto
