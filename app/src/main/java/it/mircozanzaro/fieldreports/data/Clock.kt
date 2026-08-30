package it.mircozanzaro.fieldreports.data

/**
 * Il tempo come dipendenza.
 *
 * Stessa ragione dei dispatcher: `System.currentTimeMillis()` chiamato dentro
 * la logica rende impossibile scrivere un test su "i dati sono vecchi di sei
 * minuti" senza aspettare sei minuti o accettare un'asserzione approssimativa.
 * Iniettato, il tempo diventa un parametro come un altro.
 */
fun interface Clock {
    fun nowEpochMs(): Long
}

class SystemClock : Clock {
    override fun nowEpochMs(): Long = System.currentTimeMillis()
}

/** Orologio fermo, spostabile a mano: è quello che usano i test. */
class FixedClock(var now: Long = 0L) : Clock {
    override fun nowEpochMs(): Long = now
}
