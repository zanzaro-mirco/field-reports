package it.mircozanzaro.fieldreports.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/** Il pacchetto dell'app misurata. */
const val TARGET_PACKAGE = "it.mircozanzaro.fieldreports"

/**
 * I percorsi dell'utente, scritti una volta sola.
 *
 * Li usano sia il generatore del profilo sia i benchmark, e non è un dettaglio:
 * il profilo compila in anticipo il codice che il generatore attraversa, quindi
 * un benchmark che facesse un percorso diverso misurerebbe codice che il
 * profilo non conosce, e il «dopo» sembrerebbe peggio di quanto è.
 */
fun MacrobenchmarkScope.waitForReports() {
    check(device.wait(Until.hasObject(By.res(LIST)), TIMEOUT_MS)) {
        "La lista dei rapporti non è comparsa entro $TIMEOUT_MS ms"
    }
}

/** Scorre la lista fino in fondo e torna in cima. */
fun MacrobenchmarkScope.scrollReports() {
    val list = device.findObject(By.res(LIST))
    // Il margine tiene il gesto lontano dai bordi, dove Android lo prenderebbe
    // per il gesto di sistema che torna indietro o apre le notifiche.
    list.setGestureMargin(device.displayWidth / 5)
    repeat(3) {
        list.fling(Direction.DOWN)
        device.waitForIdle()
    }
    list.fling(Direction.UP)
    device.waitForIdle()
}

/**
 * Apre un rapporto e torna alla lista.
 *
 * Uno qualunque fra quelli a schermo, e non il primo: dopo lo scorrimento il
 * primo può non esserci più, ed è stato il primo errore del generatore.
 */
fun MacrobenchmarkScope.openAReport() {
    device.findObject(By.res(Pattern.compile("report-R-[0-9]+"))).click()
    check(device.wait(Until.hasObject(By.res("report-detail")), TIMEOUT_MS)) {
        "Il dettaglio non è comparso entro $TIMEOUT_MS ms"
    }
    device.pressBack()
    waitForReports()
}

private const val LIST = "report-list"
private const val TIMEOUT_MS = 10_000L
