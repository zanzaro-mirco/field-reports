package it.mircozanzaro.fieldreports.ui

import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Il bersaglio di tocco minimo dell'app.
 *
 * Il pavimento di Android è 48 dp, pensato per un dito nudo. Un terminale da
 * campo si usa spesso con i guanti, dove la punta del dito è più larga e meno
 * precisa, e un bersaglio al limite diventa un tocco sull'elemento accanto. 56
 * dp è una scelta di questo progetto, non una norma, e sta in un posto solo.
 */
val MinTouchTarget: Dp = 56.dp

/**
 * Il tema dell'app, lo stesso per l'Activity e per i test.
 *
 * I colori sono quelli di Material 3, e non per pigrizia: il contrasto di ogni
 * testo che l'app disegna è misurato sui pixel in `AccessibilityTest`, e passa.
 *
 * Il bersaglio minimo non si impone componente per componente, ma servono **due**
 * impostazioni, e la prima versione ne aveva una sola:
 *
 * - [LocalMinimumInteractiveComponentSize] riserva lo spazio: pulsanti, chip e
 *   card di Material si impaginano grandi almeno così, e due bersagli vicini
 *   non si sovrappongono;
 * - `minimumTouchTargetSize` di [LocalViewConfiguration] decide dove il tocco
 *   viene accettato. Un'icona da 40 dp dentro uno spazio da 56 risponde solo su
 *   48, il valore di sistema, finché non si cambia anche questo.
 *
 * Con la sola prima, lo schermo sembrava fatto per i guanti e non lo era: il
 * test misura l'area che risponde al tocco, e diceva 48.
 */
@Composable
fun FieldReportsTheme(content: @Composable () -> Unit) {
    val system = LocalViewConfiguration.current
    val gloved = remember(system) { GlovedViewConfiguration(system) }
    MaterialTheme {
        CompositionLocalProvider(
            LocalMinimumInteractiveComponentSize provides MinTouchTarget,
            LocalViewConfiguration provides gloved,
            content = content,
        )
    }
}

/** La configurazione di sistema, con un solo valore cambiato. */
private class GlovedViewConfiguration(system: ViewConfiguration) : ViewConfiguration by system {
    override val minimumTouchTargetSize: DpSize = DpSize(MinTouchTarget, MinTouchTarget)
}
