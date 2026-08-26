package it.mircozanzaro.fieldreports.domain

/**
 * Esito di un'operazione che può fallire.
 *
 * Preferito a `kotlin.Result` per due ragioni: `Result` è una value class con
 * limitazioni nei tipi di ritorno, e soprattutto un tipo nostro permette di
 * modellare l'errore come gerarchia chiusa invece che come `Throwable`
 * generico. Il `when` che lo distingue è esaustivo: se domani si aggiunge un
 * tipo di errore, il compilatore indica ogni punto da aggiornare.
 */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: DomainError) : Outcome<Nothing>
}

/** Errori del dominio, indipendenti dal trasporto usato. */
sealed interface DomainError {
    data object Network : DomainError
    data object Timeout : DomainError
    data class Server(val code: Int) : DomainError
    data class Unknown(val message: String) : DomainError
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

inline fun <T> Outcome<T>.onSuccess(action: (T) -> Unit): Outcome<T> {
    if (this is Outcome.Success) action(value)
    return this
}

inline fun <T> Outcome<T>.onFailure(action: (DomainError) -> Unit): Outcome<T> {
    if (this is Outcome.Failure) action(error)
    return this
}
