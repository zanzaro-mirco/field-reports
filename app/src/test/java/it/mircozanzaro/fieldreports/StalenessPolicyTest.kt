package it.mircozanzaro.fieldreports

import it.mircozanzaro.fieldreports.data.TimeBasedStalenessPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La politica di scadenza della cache, isolata dal resto.
 *
 * Si testa da sola perché è una decisione, non un effetto: quattro casi, nessun
 * database, nessuna coroutine.
 */
class StalenessPolicyTest {

    private val policy = TimeBasedStalenessPolicy(maxAgeMs = 60_000)

    @Test
    fun `una cache mai popolata e sempre da aggiornare`() {
        assertTrue(policy.isStale(lastSyncEpochMs = null, nowEpochMs = 1_000))
    }

    @Test
    fun `dentro la finestra non si aggiorna`() {
        assertFalse(policy.isStale(lastSyncEpochMs = 1_000, nowEpochMs = 1_000 + 59_999))
    }

    @Test
    fun `oltre la finestra si aggiorna`() {
        assertTrue(policy.isStale(lastSyncEpochMs = 1_000, nowEpochMs = 1_000 + 60_000))
    }

    @Test
    fun `un orologio che torna indietro non congela la cache`() {
        // Fuso corretto a mano o risincronizzazione NTP: senza questo caso la
        // cache resterebbe "fresca" finché l'orologio non recupera il salto.
        assertTrue(policy.isStale(lastSyncEpochMs = 10_000, nowEpochMs = 5_000))
    }
}
