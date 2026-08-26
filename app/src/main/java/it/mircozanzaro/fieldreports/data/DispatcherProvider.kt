package it.mircozanzaro.fieldreports.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * I dispatcher come dipendenza.
 *
 * Passare un solo `CoroutineDispatcher` funziona finché ne serve uno. Con un
 * provider si sostituiscono tutti insieme nei test, e non si è costretti a
 * cambiare firma ogni volta che una classe ha bisogno anche di `default`.
 */
interface DispatcherProvider {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val main: CoroutineDispatcher
}

class StandardDispatcherProvider : DispatcherProvider {
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val default: CoroutineDispatcher get() = Dispatchers.Default
    override val main: CoroutineDispatcher get() = Dispatchers.Main
}

/** Provider di test: tutte le coroutine sullo stesso dispatcher controllabile. */
class TestDispatcherProvider(private val dispatcher: CoroutineDispatcher) :
    DispatcherProvider {
    override val io: CoroutineDispatcher get() = dispatcher
    override val default: CoroutineDispatcher get() = dispatcher
    override val main: CoroutineDispatcher get() = dispatcher
}
