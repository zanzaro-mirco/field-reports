package it.mircozanzaro.fieldreports

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import it.mircozanzaro.fieldreports.domain.DomainError
import it.mircozanzaro.fieldreports.domain.ReportStatus
import it.mircozanzaro.fieldreports.ui.FieldReportsTheme
import it.mircozanzaro.fieldreports.ui.ItalianErrorTextProvider
import it.mircozanzaro.fieldreports.ui.ReportDetailScreen
import it.mircozanzaro.fieldreports.ui.ReportDetailUiState
import it.mircozanzaro.fieldreports.ui.ReportsScreen
import it.mircozanzaro.fieldreports.ui.ReportsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Le schermate con il testo al 200%, su un palmare da campo.
 *
 * «Usabile» qui non è un'impressione, sono cinque controlli su ogni stato:
 *
 * 1. ogni elemento che si tocca dice cos'è, a un lettore di schermo;
 * 2. ogni elemento che si tocca è grande almeno [MIN_TOUCH_TARGET], e non si
 *    sovrappone a un altro;
 * 3. nessun testo è tagliato: né troncato dentro il suo spazio, né nascosto da
 *    un contenitore che non scorre;
 * 4. ogni testo si legge sul suo sfondo, con il contrasto misurato sui pixel
 *    disegnati e non sulle coppie di colori del tema;
 * 5. ogni indicatore di caricamento dice cosa sta aspettando.
 *
 * Lo schermo è 360×640 dp, quello di un palmare Android da 5 pollici. La scala
 * del testo è **lineare**: Android 14 ingrandisce meno i caratteri già grandi,
 * ma i terminali da campo restano a lungo su versioni precedenti, dove il 200%
 * è il doppio di tutto. È il caso peggiore, ed è quello che si prova.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class AccessibilityTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val errorText = ItalianErrorTextProvider()::textFor

    private val reports = listOf(
        sampleReport(
            "R-1041",
            title = "Sostituzione contatore acqua fredda",
            status = ReportStatus.OPEN,
        ),
        sampleReport(
            "R-1042",
            title = "Verifica lettore RFID al varco carraio",
            status = ReportStatus.IN_PROGRESS,
        ),
        sampleReport(
            "R-1043",
            title = "Taratura della pesa a ponte",
            status = ReportStatus.CLOSED,
        ),
    )

    private fun showWithDoubleText(content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = FONT_SCALE),
            ) {
                FieldReportsTheme(content)
            }
        }
        compose.waitForIdle()
    }

    private fun showList(state: ReportsUiState) = showWithDoubleText {
        ReportsScreen(state = state, onRefresh = {}, onFilterChange = {}, errorText = errorText)
    }

    private fun showDetail(state: ReportDetailUiState) = showWithDoubleText {
        ReportDetailScreen(state = state, onBack = {}, zoneId = ZoneId.of("Europe/Rome"))
    }

    @Test
    fun `la lista con avviso e aggiornamento in corso resta usabile a testo doppio`() {
        // Lo stato più affollato: barra di avanzamento, avviso offline, filtri e
        // lista insieme, cioè meno spazio possibile per i rapporti.
        showList(
            ReportsUiState.Ready(
                reports = reports,
                isRefreshing = true,
                refreshError = DomainError.Network,
            ),
        )

        assertUsable()
        assertFullyVisible("report-R-1041")
    }

    @Test
    fun `la lista filtrata resta usabile a testo doppio`() {
        // Il chip selezionato ha colori suoi: il contrasto va misurato anche lì.
        showList(ReportsUiState.Ready(reports = reports, filter = ReportStatus.CLOSED))

        assertUsable()
        assertFullyVisible("report-R-1043")
    }

    @Test
    fun `una lista vuota resta usabile a testo doppio`() {
        showList(ReportsUiState.Ready(reports = emptyList()))

        assertUsable()
    }

    @Test
    fun `l errore senza dati resta usabile a testo doppio`() {
        showList(ReportsUiState.Error(DomainError.Network))

        assertUsable()
    }

    @Test
    fun `il primo caricamento resta usabile a testo doppio`() {
        showList(ReportsUiState.Loading)

        assertUsable()
    }

    @Test
    fun `il dettaglio resta usabile a testo doppio`() {
        showDetail(
            ReportDetailUiState.Ready(
                sampleReport(
                    "R-1042",
                    title = "Verifica lettore RFID al varco carraio",
                    description = "Il lettore non riconosce i badge dei mezzi pesanti. " +
                        "Controllare distanza dell'antenna e alimentazione.",
                    createdAtEpochMs = 1_753_600_000_000,
                    status = ReportStatus.IN_PROGRESS,
                ),
            ),
        )

        assertUsable()
    }

    @Test
    fun `un rapporto non trovato resta usabile a testo doppio`() {
        showDetail(ReportDetailUiState.NotFound)

        assertUsable()
    }

    @Test
    fun `il caricamento del dettaglio resta usabile a testo doppio`() {
        showDetail(ReportDetailUiState.Loading)

        assertUsable()
    }

    @Test
    fun `un tocco appena fuori dall icona raggiunge ancora il pulsante`() {
        // I controlli sopra leggono l'area di tocco che Compose dichiara. Questo
        // tocca davvero: l'icona di aggiorna è 40 dp, il sistema accetta il tocco
        // fino a 48 e il tema fino a 56. Ventisei dp sotto il centro stanno fra i
        // due, e arrivano al pulsante solo se il tema ha effetto sull'input vero.
        var refreshes = 0
        showWithDoubleText {
            ReportsScreen(
                state = ReportsUiState.Ready(reports),
                onRefresh = { refreshes++ },
                onFilterChange = {},
                errorText = errorText,
            )
        }

        compose.onNodeWithTag("refresh").performTouchInput {
            click(center + Offset(0f, 26.dp.toPx()))
        }

        assertEquals(1, refreshes)
    }

    // --- i controlli -----------------------------------------------------------

    /**
     * Raccoglie tutti i problemi prima di fallire: una schermata con tre difetti
     * li mostra tutti e tre, invece di farli scoprire uno per esecuzione.
     */
    private fun assertUsable() {
        val problems = buildList {
            addAll(undescribedInteractiveElements())
            addAll(smallOrOverlappingTouchTargets())
            addAll(cutTexts())
            addAll(lowContrastTexts())
            addAll(undescribedProgressIndicators())
        }
        assertTrue(problems.joinToString(separator = "\n", prefix = "\n"), problems.isEmpty())
    }

    private fun assertFullyVisible(tag: String) {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        assertTrue("$tag non si vede intero", !node.isClipped())
    }

    private fun interactiveNodes(): List<SemanticsNode> =
        compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()

    private fun undescribedInteractiveElements(): List<String> =
        interactiveNodes()
            .filter { node -> node.spokenText().isBlank() }
            .map { node -> "senza descrizione: ${node.name()}" }

    private fun smallOrOverlappingTouchTargets(): List<String> {
        val nodes = interactiveNodes()
        val minPx = with(compose.density) { MIN_TOUCH_TARGET.toPx() }
        val small = nodes
            .filter { node ->
                val bounds = node.touchBoundsInRoot
                bounds.width < minPx - 1 || bounds.height < minPx - 1
            }
            .map { node ->
                val bounds = node.touchBoundsInRoot
                with(compose.density) {
                    "bersaglio piccolo: ${node.name()} " +
                        "(${bounds.width.toDp()} × ${bounds.height.toDp()})"
                }
            }
        val overlapping = nodes.flatMapIndexed { index, node ->
            nodes.drop(index + 1)
                .filter { other -> node.touchBoundsInRoot.overlapsWithArea(other.touchBoundsInRoot) }
                .map { other -> "bersagli sovrapposti: ${node.name()} e ${other.name()}" }
        }
        return small + overlapping
    }

    private fun cutTexts(): List<String> =
        textNodes().mapNotNull { node ->
            val layout = node.textLayout() ?: return@mapNotNull null
            val hidden = !node.isInsideScrollable() && node.isClipped()
            when {
                layout.isTruncated() -> "testo troncato: ${node.name()}"
                hidden -> "testo nascosto dal contenitore: ${node.name()}"
                else -> null
            }
        }

    /**
     * Un testo è troncato se una riga esce dalla larghezza del testo, se le righe
     * non stanno nell'altezza, se qualcosa è stato sostituito dai puntini, o se
     * una parola è stata spezzata a metà per andare a capo: «Chiu» e «si» su due
     * righe non sono un testo che si legge.
     *
     * Non `hasVisualOverflow`, che è stata la prima versione e mentiva:
     * confronta la larghezza del testo con quella del paragrafo, e il paragrafo
     * viene impaginato su tutta la larghezza disponibile. Ogni testo più corto
     * dello schermo risultava troncato — «Cliente» compreso.
     */
    private fun TextLayoutResult.isTruncated(): Boolean {
        val width = size.width
        val text = layoutInput.text.text
        return didOverflowHeight ||
            multiParagraph.didExceedMaxLines ||
            (0 until lineCount).any { line ->
                val end = getLineEnd(line)
                val splitsWord = end in 1 until text.length &&
                    text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()
                isLineEllipsized(line) || splitsWord ||
                    getLineRight(line) > width + 1 || getLineLeft(line) < -1
            }
    }

    private fun lowContrastTexts(): List<String> {
        val image: Bitmap = drawScreen()
        // Il rettangolo del testo si stringe prima di contare i colori. A testo
        // doppio l'etichetta di un chip è alta quanto il chip, e il bordo del
        // chip corre lungo i suoi lati: senza, era il bordo il colore più
        // lontano dallo sfondo, e un'etichetta grigio chiaro misurava 4,33:1.
        val inset = with(compose.density) { 2.dp.toPx() }
        return textNodes()
            .filter { node -> !node.isClipped() }
            .mapNotNull { node ->
                val ratio = contrastInside(image, node.boundsInRoot.deflate(inset))
                    ?: return@mapNotNull null
                if (ratio >= MIN_CONTRAST) {
                    null
                } else {
                    "contrasto %.2f:1: %s".format(ratio, node.name())
                }
            }
    }

    private fun undescribedProgressIndicators(): List<String> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .fetchSemanticsNodes()
            .filter { node -> node.spokenText().isBlank() }
            .map { node -> "indicatore senza descrizione: ${node.name()}" }

    // --- strumenti -------------------------------------------------------------

    /**
     * Lo schermo disegnato su una bitmap. `captureToImage()` aspetta un
     * fotogramma dal sistema, che sotto Robolectric non arriva mai; disegnare la
     * vista direttamente dà gli stessi pixel senza aspettare.
     */
    private fun drawScreen(): Bitmap {
        val content = compose.activity.findViewById<ViewGroup>(android.R.id.content)
        val composeView: View = content.getChildAt(0)
        val bitmap = Bitmap.createBitmap(composeView.width, composeView.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { composeView.draw(Canvas(bitmap)) }
        return bitmap
    }

    private fun textNodes(): List<SemanticsNode> =
        compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()

    private fun SemanticsNode.textLayout(): TextLayoutResult? {
        val results = mutableListOf<TextLayoutResult>()
        config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.firstOrNull()
    }

    private fun SemanticsNode.spokenText(): String =
        (
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            ).joinToString(" ")

    private fun SemanticsNode.name(): String =
        config.getOrNull(SemanticsProperties.TestTag)
            ?: spokenText().ifBlank { "nodo $id" }

    /** Una parte dell'elemento è fuori dallo spazio che il genitore gli concede. */
    private fun SemanticsNode.isClipped(): Boolean =
        boundsInRoot.width < size.width - 1 || boundsInRoot.height < size.height - 1

    /** Dentro qualcosa che scorre, un elemento tagliato al bordo si raggiunge. */
    private fun SemanticsNode.isInsideScrollable(): Boolean =
        generateSequence(parent) { it.parent }.any { ancestor ->
            SemanticsProperties.VerticalScrollAxisRange in ancestor.config ||
                SemanticsProperties.HorizontalScrollAxisRange in ancestor.config
        }

    private fun Rect.overlapsWithArea(other: Rect): Boolean {
        val intersection = intersect(other)
        return intersection.width > 1 && intersection.height > 1
    }

    companion object {
        private const val FONT_SCALE = 2f

        /**
         * Lo stesso valore di `MinTouchTarget` nel tema, scritto di nuovo e non
         * importato: se il tema lo abbassa, questo test deve accorgersene.
         */
        private val MIN_TOUCH_TARGET = 56.dp

        /** WCAG AA per il testo normale. */
        private const val MIN_CONTRAST = 4.5

        /**
         * Il contrasto di un testo, misurato sui pixel.
         *
         * Il colore più frequente nel rettangolo del testo è lo sfondo. Il
         * colore del testo è, fra quelli abbastanza frequenti da non essere
         * rumore, il più lontano dallo sfondo: i pixel di bordo dei glifi,
         * sfumati dall'antialiasing, stanno per costruzione fra i due.
         */
        fun contrastInside(image: Bitmap, bounds: Rect): Double? {
            val left = bounds.left.toInt().coerceIn(0, image.width)
            val top = bounds.top.toInt().coerceIn(0, image.height)
            val right = bounds.right.toInt().coerceIn(0, image.width)
            val bottom = bounds.bottom.toInt().coerceIn(0, image.height)
            if (right - left < 2 || bottom - top < 2) return null

            val counts = HashMap<Int, Int>()
            for (y in top until bottom) {
                for (x in left until right) {
                    val pixel = image.getPixel(x, y)
                    counts[pixel] = (counts[pixel] ?: 0) + 1
                }
            }
            val total = (right - left) * (bottom - top)
            val background = counts.maxBy { it.value }.key
            val frequentEnough = counts.filter { (color, count) ->
                color != background && count >= max(3, total / 100)
            }
            if (frequentEnough.isEmpty()) return null
            return frequentEnough.keys.maxOf { color -> contrast(color, background) }
        }

        fun contrast(first: Int, second: Int): Double {
            val a = luminance(first)
            val b = luminance(second)
            return (max(a, b) + 0.05) / (min(a, b) + 0.05)
        }

        private fun luminance(argb: Int): Double {
            fun channel(shift: Int): Double {
                val value = ((argb shr shift) and 0xFF) / 255.0
                return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
    }
}
