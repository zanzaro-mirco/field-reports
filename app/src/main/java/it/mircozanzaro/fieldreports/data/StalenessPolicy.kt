package it.mircozanzaro.fieldreports.data

/**
 * Decide se i dati in cache vanno rinfrescati.
 *
 * È una strategia sostituibile come [ErrorMapper]: "quando i dati sono vecchi"
 * è una decisione di prodotto, non una verità tecnica. Su un terminale da campo
 * con connettività a consumo la soglia potrebbe essere un'ora; su una
 * postazione fissa, un minuto. La logica che la usa non deve cambiare.
 */
fun interface StalenessPolicy {
    fun isStale(lastSyncEpochMs: Long?, nowEpochMs: Long): Boolean
}

/**
 * Scadenza a tempo.
 *
 * Due casi limite trattati esplicitamente:
 * - cache mai popolata (`null`): sempre da aggiornare, altrimenti la prima
 *   apertura dell'app non scaricherebbe nulla;
 * - età negativa: succede quando l'orologio del dispositivo torna indietro
 *   (fuso corretto a mano, sincronizzazione NTP). Senza questo caso la cache
 *   resterebbe "fresca" fino a che l'orologio non recupera il salto, cioè
 *   potenzialmente per ore.
 */
class TimeBasedStalenessPolicy(
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
) : StalenessPolicy {

    override fun isStale(lastSyncEpochMs: Long?, nowEpochMs: Long): Boolean {
        val lastSync = lastSyncEpochMs ?: return true
        val age = nowEpochMs - lastSync
        return age < 0 || age >= maxAgeMs
    }

    companion object {
        const val DEFAULT_MAX_AGE_MS: Long = 5 * 60 * 1000L
    }
}
