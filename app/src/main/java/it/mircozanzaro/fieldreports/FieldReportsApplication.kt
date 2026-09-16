package it.mircozanzaro.fieldreports

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Tiene in vita il grafo delle dipendenze per tutta la durata del processo.
 *
 * Prima lo faceva con un `by lazy` su un container scritto a mano, perché
 * aprire il database prima che qualcuno lo chieda allunga l'avvio a freddo. Con
 * Hilt la proprietà resta, senza scriverla: il componente nasce qui, ma ogni
 * oggetto del grafo viene costruito la prima volta che qualcuno lo chiede — e il
 * database lo chiede il primo ViewModel, non l'Application.
 */
@HiltAndroidApp
class FieldReportsApplication : Application()
