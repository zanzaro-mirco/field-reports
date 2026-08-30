package it.mircozanzaro.fieldreports

import android.app.Application

/**
 * Tiene in vita il grafo delle dipendenze per tutta la durata del processo.
 *
 * `by lazy` e non un'inizializzazione diretta in `onCreate`: aprire il database
 * è lavoro su disco, e farlo prima che qualcuno lo chieda allunga l'avvio a
 * freddo — che è proprio il numero da misurare al punto 3.5 della roadmap.
 */
class FieldReportsApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
