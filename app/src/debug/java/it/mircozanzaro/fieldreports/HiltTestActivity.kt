package it.mircozanzaro.fieldreports

import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Un'Activity vuota su cui i test montano il grafo di navigazione.
 *
 * I ViewModel delle destinazioni si chiedono a Hilt, e Hilt li costruisce solo
 * sotto un'Activity annotata: la `ComponentActivity` semplice che usavano i
 * test non basta più. `MainActivity` non va bene, perché monta già il suo
 * contenuto e il test deve poterlo fare da sé.
 *
 * Sta in `debug` e non in `test` perché Robolectric avvia soltanto le Activity
 * dichiarate nel manifest; è lo stesso posto in cui la libreria di test di
 * Compose mette la sua. Nell'APK di rilascio non c'è.
 */
@AndroidEntryPoint
class HiltTestActivity : ComponentActivity()
