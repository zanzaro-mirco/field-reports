# Architettura e scelte di progetto

## Struttura

```
FieldReportsApplication.kt     @HiltAndroidApp: il grafo vive quanto il processo
di/
  AppModules.kt                composition root: l'unico file che conosce le classi concrete
domain/                        nessuna dipendenza, nemmeno da Android
  Report.kt                    modello puro
  ReportsRepository.kt         contratto definito qui, implementato altrove
  Outcome.kt                   esito + gerarchia chiusa degli errori
data/
  remote/
    ReportDto.kt               rappresentazione di rete + mapper
    ReportsApi.kt              contratto + sorgente simulata
  local/
    ReportEntity.kt            rappresentazione su database + mapper
    SyncStateEntity.kt         quando è avvenuta l'ultima sincronizzazione
    ReportDao.kt               query, con Flow sulle letture
    FieldReportsDatabase.kt    Room, schema esportato in app/schemas/
    ReportsLocalStore.kt       contratto della cache + implementazione in memoria
    RoomReportsLocalStore.kt   implementazione su SQLite
  DefaultReportsRepository.kt  implementazione
  ErrorMapper.kt               eccezioni tecniche -> errori di dominio
  DispatcherProvider.kt        i dispatcher come dipendenza
  Clock.kt                     il tempo come dipendenza
  StalenessPolicy.kt           quando i dati in cache sono da rinfrescare
ui/
  FieldReportsNavHost.kt       grafo di navigazione, rotte tipizzate, ViewModel per destinazione
  ReportsUiState.kt            sealed interface
  ReportsViewModel.kt          StateFlow, viewModelScope
  ReportsScreen.kt             Compose, state hoisting
  ReportDetailUiState.kt       stato del dettaglio + l'unico evento dell'app
  ReportDetailViewModel.kt     l'evento su Channel
  ReportDetailScreen.kt        il dettaglio, e la raccolta dell'evento legata al ciclo di vita
  ErrorTextProvider.kt         errore di dominio -> testo per l'utente
  FieldReportsTheme.kt         il tema, e il bersaglio di tocco minimo per i guanti

baselineprofile/               modulo di test che guida l'APK di rilascio su un telefono vero
  Journeys.kt                  i percorsi dell'utente, gli stessi per profilo e benchmark
  BaselineProfileGenerator.kt  genera il Baseline Profile e il profilo di avvio
  PerformanceBenchmarks.kt     avvio a freddo e scorrimento, senza e con il profilo
app/src/release/generated/     i profili generati, versionati
```

La regola: `domain` non importa nulla da `data` né da `ui`. Le frecce puntano
verso l'interno.

## La cache locale è la sorgente unica

Con l'arrivo di Room il repository ha smesso di essere un passacarte verso la rete.
La regola vale in entrambi i versi:

- **si legge sempre dal database** — `observeReports()` restituisce un `Flow` alimentato
  dal DAO, e Room notifica da sé ogni scrittura sulla tabella;
- **si scrive nel database solo dalla rete** — `refresh()` scarica, mappa e sostituisce
  il contenuto della cache.

La conseguenza è che nella presentazione non esiste un solo ramo `if (isOnline)`: la UI
osserva una sorgente sola, che offline contiene semplicemente i dati di prima.

Da qui discendono due scelte che sono la sostanza dello sviluppo.

**Un errore di rete non cancella i dati.** `fetchAndStore()` scrive in cache solo dopo che
la chiamata è riuscita. Se fallisce, la cache resta intatta e l'errore diventa un valore da
mostrare. C'è un test che lo verifica esplicitamente, perché è la garanzia su cui si regge
tutto il resto.

**Un errore non è sempre bloccante.** `ReportsUiState.Error` copre lo schermo solo quando la
cache è vuota *e* la sincronizzazione è fallita — cioè quando davvero non c'è nulla da
mostrare. Se in cache ci sono rapporti, lo stato resta `Ready` con `refreshError`
valorizzato: dati veri, con sopra un avviso che dice da dove vengono. Nascondere all'utente
rapporti che possediamo sarebbe esattamente ciò che la cache serve a evitare.

**Leggere e sincronizzare sono due operazioni diverse.** È la ragione per cui
`suspend fun loadReports(): Outcome<List<Report>>` è stato sostituito da tre metodi: la
lettura è un flusso che vive quanto la schermata, la sincronizzazione è un'operazione
singola che può fallire. `refresh()` è l'ordine dell'utente e non si discute;
`refreshIfStale()` è la politica di avvio — riaprire l'app tre volte in un minuto non vale
tre chiamate di rete.

**Il filtro vive fuori dallo stato esposto.** Prima era un campo di `Ready` aggiornato con
una `copy`. Con una sorgente reattiva quella soluzione si romperebbe in silenzio: la prima
emissione del database dopo un aggiornamento ricostruisce lo stato da zero e cancellerebbe
la scelta dell'utente mentre sta guardando la lista. Ora il filtro è un `MutableStateFlow`
separato, combinato con i dati al momento di comporre lo stato — e c'è un test che verifica
che sopravviva a un refresh.

## Navigazione, e l'unico evento dell'app

Due destinazioni, `ReportListDestination` e `ReportDetailDestination(reportId)`, dichiarate
come classi serializzabili: sono le rotte tipizzate di Navigation 2.8. Un argomento sbagliato
è un errore di compilazione, e non una stringa con un segnaposto che si scopre storta al
primo tocco.

**L'argomento è l'id, non il rapporto.** Gli argomenti di navigazione finiscono nello stato
salvato dell'Activity, che ha un limite di dimensione; e un rapporto copiato lì sarebbe una
seconda sorgente, ferma al momento del tocco. Con l'id il dettaglio legge dalla cache con
`observeReport`, e un aggiornamento arrivato mentre lo si guarda compare da solo. È la regola
della sorgente unica, applicata anche alla navigazione.

**Il tocco non passa dal ViewModel.** Il piano chiedeva di mandare la navigazione dal
ViewModel alla UI come evento su `SharedFlow`. La guida Android corrente dice il contrario, e
qui ha ragione: il tocco su una card non ha niente da decidere, e un giro fino al ViewModel e
ritorno aggiunge soltanto un posto in cui l'evento può ripetersi o perdersi. La card chiama
`navigate` direttamente. L'unico controllo è sul doppio tocco: si naviga solo se la lista è
ancora `RESUMED`, perché il secondo tocco arriva quando la lista sta già uscendo e aprirebbe
un secondo dettaglio sopra il primo. Il pulsante indietro fa lo stesso con
`dropUnlessResumed`, altrimenti il secondo tocco toglierebbe anche la lista.

**L'evento esiste dove la decisione è del ViewModel.** Se una sincronizzazione toglie il
rapporto aperto — la issue è stata cancellata o spostata — il dettaglio deve tornare alla
lista una volta, e la lista deve dire perché. Lo sa solo chi osserva la cache, quindi nasce
in `ReportDetailViewModel`. Qui un evento serve davvero, e conta il canale su cui viaggia. Una
rotazione distrugge l'osservatore della UI e ne crea uno nuovo, mentre il ViewModel resta:

| Canale | L'osservatore riparte dopo aver gestito l'evento | L'evento nasce mentre nessuno ascolta |
|---|---|---|
| `StateFlow` | lo riceve di nuovo: si torna indietro due volte | conservato |
| `SharedFlow` senza replay | non lo riceve di nuovo | **perso**: lo schermo resta su un rapporto che non esiste |
| `Channel` | non lo riceve di nuovo | tenuto finché qualcuno lo prende |

La UI raccoglie gli eventi con `repeatOnLifecycle(STARTED)` e su `Dispatchers.Main.immediate`.
Con l'app in secondo piano l'evento aspetta nel canale; e fra il momento in cui esce dal
canale e quello in cui viene gestito non passa un giro del ciclo principale, in cui una
rotazione potrebbe infilarsi e perderlo.

**"Non trovato" è uno stato, "rimosso" è un evento.** Un id che la cache non ha mai avuto
succede quando la navigazione viene ripristinata dopo che il sistema ha chiuso l'app, su una
cache che nel frattempo è cambiata. Non c'è niente da cui tornare indietro: lo schermo resta e
spiega, e `ReportDetailUiState.NotFound` lo rappresenta. Un rapporto che sparisce mentre lo si
guarda, invece, fa andare via lo schermo, e andarsene succede una volta sola. Dopo l'evento lo
stato resta sull'ultimo rapporto visto, così durante l'animazione di uscita non lampeggia un
"non trovato".

**`distinctUntilChanged`, per Room.** Una query osservata viene rieseguita a ogni scrittura
sulla tabella, anche se il rapporto osservato non è cambiato. Senza, la sincronizzazione
successiva a una rimozione — di nuovo `null` — produrrebbe un secondo evento.

### Cosa ha detto la falsificazione

Ogni difesa è stata tolta da sola, con il resto intatto, per vedere quale test se ne accorge:

| Tolto | Test che fallisce |
|---|---|
| il `Channel`, al suo posto uno `StateFlow` | `l'evento arriva una volta sola anche quando l'osservatore riparte` |
| il `Channel`, al suo posto uno `SharedFlow` senza replay | `un evento emesso mentre nessuno ascolta non si perde` |
| `distinctUntilChanged` | `le scritture successive in cache non ripetono l'evento` |
| il controllo `RESUMED` sul tocco della card | `un doppio tocco sulla card apre un dettaglio solo` |
| `dropUnlessResumed` sul pulsante indietro | `un doppio tocco su indietro non toglie anche la lista` |
| `fallbackToDestructiveMigration` | `una cache della versione 1 si butta intera, data di sincronizzazione compresa` |
| il tocco che naviga, al suo posto uno stato nel ViewModel della lista | tutti e cinque i test di navigazione |

Ogni difesa ha un test che fallisce quando la si toglie. Tre cose però le ha dette la
falsificazione, non il codice.

**La prima versione del test sul doppio tocco non provava niente.** Mandava due tocchi come
sequenza di input, e tolto il controllo restava verde. Ora invoca l'azione di click due volte
senza fotogrammi in mezzo, e senza il controllo fallisce. Un test scritto per una difesa e
mai visto fallire resta un'ipotesi.

**Il test sul grafo vero non vede il canale dell'evento.** Con uno `StateFlow` o uno
`SharedFlow` al posto del `Channel`, `un rapporto rimosso riporta alla lista una volta sola,
anche ruotando` resta verde. Nel grafo il dettaglio viene tolto mentre gestisce l'evento, e
dopo non c'è più un osservatore che possa riceverlo di nuovo. I due errori li vedono i test
del ViewModel, che riproducono l'osservatore che riparte e quello che manca: non sono un
doppione del test sul grafo, sono gli unici che se ne accorgono.

**L'errore che il piano temeva rompe tutto, non solo la rotazione.** Con la navigazione
tenuta come stato nel ViewModel della lista falliscono anche i test senza rotazione: basta
tornare alla lista, e lo stato ancora valorizzato rimanda al dettaglio.

## Hilt, e perché è arrivato adesso

Per le prime versioni il grafo delle dipendenze era una classe scritta a mano, `AppContainer`, e
la documentazione diceva che Hilt si giustifica quando la costruzione a mano diventa il
problema. La soglia era giusta; il conteggio che la accompagnava no. «Un grafo di quattro
oggetti» era vero con la sorgente finta, e con Retrofit e Room gli oggetti sono diventati otto
senza che la frase cambiasse. Il container non era comunque il problema: otto righe che si
leggono dall'alto in basso non chiedono un framework.

**Il problema è arrivato con la navigazione, e non stava nel container.** Ogni destinazione ha
il suo ViewModel, che vive quanto la sua voce nella pila e quindi si può costruire solo dentro
il grafo di navigazione. Il risultato era una factory scritta a mano per destinazione dentro
`FieldReportsNavHost`, e il repository passato come parametro dall'Activity al grafo solo per
arrivare a quelle factory. Quella del dettaglio doveva mettere insieme una dipendenza e un
argomento di navigazione. Il costo di scrivere il grafo a mano non si vedeva nel posto in cui
lo si scriveva, ma nella UI.

Con Hilt il grafo di navigazione non riceve niente: `hiltViewModel()` costruisce il ViewModel
e lo lega comunque alla voce della pila, e il dettaglio legge l'id dal `SavedStateHandle` con
la stessa rotta tipizzata con cui la navigazione lo ha scritto.

**Tre scelte, ciascuna con il suo perché.**

- **`@Provides` e non costruttori `@Inject`.** `data/` e `domain/` non importano niente da
  Dagger. Nel `git diff` del commit che introduce Hilt, sotto quelle due cartelle cambia una
  sola cosa: un commento che nominava `AppContainer`. Il prezzo sono le righe di un
  `@Provides` al posto di un `@Binds`.
- **I moduli in un file solo.** Il pregio di `AppContainer` era che il grafo si leggeva in un
  posto solo. Hilt permette di spargerlo, non lo chiede.
- **`@Singleton` su tre oggetti su otto.** Il database, perché riaperto a ogni rotazione
  butterebbe connessione e cache delle query; il client HTTP, perché tiene il pool delle
  connessioni; il servizio Retrofit, perché ricorda per istanza le annotazioni già lette. Il
  repository, il mapper della sorgente e lo store non hanno stato proprio, e uno scope su di
  loro dichiarerebbe una cosa falsa.

**Cosa costa, misurato.** Su questo computer, build completa di debug con
`./gradlew :app:assembleDebug --rerun-tasks --no-build-cache --profile`, tre prove per versione
alternate fra loro. I tempi dei singoli task vengono dall'ultima prova di ciascuna versione, e
le dimensioni dell'APK da due compilazioni di rilascio fatte lo stesso giorno:

| | Prima | Con Hilt |
|---|---|---|
| Tempo totale | 23,3 – 23,7 s | 23,7 – 24,2 s |
| `kspDebugKotlin` | 1,6 s | 2,6 s |
| `hiltJavaCompileDebug` | — | 1,6 s |
| APK di rilascio, dopo R8 | 1.792.983 byte | 1.803.740 byte (+10,7 KB) |

Hilt aggiunge circa tre secondi di lavoro, ma il tempo totale cresce di mezzo secondo:
i suoi task girano in parallelo con il desugaring di `java.time`, che da solo ne prende
diciannove. La prima misura, fatta a tempo di orologio e senza alternare, diceva che con Hilt
la build era più **veloce** — era il daemon che si scaldava, e il motivo per cui la tabella
viene da `--profile`.

Il costo che non si misura in secondi sta nei test. Il grafo di navigazione ora si prova con
`@HiltAndroidTest`: si toglie `RepositoryModule` e si inietta la cache in memoria con
`@BindValue`. Serve un'Activity annotata, `HiltTestActivity`, nel sorgente `debug` e non in
`test` — verificato spostandola: Robolectric non avvia un'Activity che il manifest non dichiara.

**Falsificato.** Con l'id letto dal `SavedStateHandle` alterato di un carattere falliscono
quattro test di navigazione su cinque. Il quinto, il doppio tocco su indietro, non guarda il
contenuto del dettaglio. I test sul grafo provano quindi anche che l'argomento arriva davvero
al ViewModel costruito da Hilt, e non solo che la navigazione avviene.

## Accessibilità, misurata

Un terminale da campo si usa all'aperto, in fretta, spesso con i guanti, e a volte da chi ha
alzato la dimensione del testo per leggerlo. Il criterio era che la schermata resti usabile con
il testo al 200% e che ogni elemento interattivo abbia una descrizione, **verificato in un test
e non a occhio**. «Usabile», per un test, va definito: `AccessibilityTest` fa cinque controlli
su ognuno degli otto stati delle due schermate.

| Controllo | Come si misura |
|---|---|
| Ogni elemento che si tocca dice cos'è | testo o `contentDescription` non vuoti sul nodo semantico unito |
| Bersagli di almeno 56 dp, non sovrapposti | l'area che Compose accetta come tocco, non la dimensione disegnata |
| Nessun testo tagliato | righe oltre la larghezza, righe oltre l'altezza, puntini, parole spezzate a metà, testo nascosto da un contenitore che non scorre |
| Contrasto WCAG AA, 4,5:1 | sui **pixel disegnati**: lo schermo va su una bitmap, e in ogni testo si confronta lo sfondo con il colore del testo |
| Ogni indicatore di caricamento dice cosa aspetta | `contentDescription` sui nodi con `ProgressBarRangeInfo` |

Lo schermo è 360×640 dp, quello di un palmare da 5 pollici, e la scala è **lineare**: il doppio
di tutto. Android 14 ingrandisce meno i caratteri già grandi, ma i terminali da campo restano a
lungo su versioni precedenti, dove questo aiuto non c'è. Si prova il caso peggiore.

**Cosa ha trovato, sull'interfaccia di prima.** Il test è stato scritto prima delle correzioni,
e falliva in tutti gli otto stati:

- **Il titolo «Rapporti di intervento» non entrava nella barra.** Ora è «Rapporti»: il resto lo
  dice il contenuto dello schermo, e una barra più alta avrebbe rubato spazio alla lista proprio
  quando il testo grande ne lascia meno.
- **«Chiusi» veniva spezzato a metà parola.** I tre chip stavano su una riga, e il terzo
  riceveva lo spazio avanzato. Ora vanno a capo: una riga che scorre avrebbe tenuto il testo
  intero ma nascosto un filtro fuori dallo schermo.
- **Pulsanti e chip accettavano il tocco su 48 dp**, il minimo di sistema. Le card, grandi
  di loro, erano già oltre.
- **I tre indicatori di caricamento erano muti** per un lettore di schermo.

Il contrasto passava già: i colori sono quelli di Material 3, e restano.

**Cosa ha trovato, sul test.** Quattro volte il test ha detto una cosa falsa, e ogni volta lo
ha scoperto una prova fatta apposta.

1. **La prima versione dava per troncato ogni testo corto**, «Cliente» compreso. Usava
   `hasVisualOverflow`, che confronta la larghezza del testo con quella del paragrafo; ma il
   paragrafo viene impaginato su tutta la larghezza disponibile, quindi ogni testo più stretto
   dello schermo risultava fuori. Ora si guarda dove finisce ogni riga.
2. **La seconda non vedeva «Chiusi» spezzato.** Una parola divisa su due righe non esce da
   nessun bordo. Il sospetto è nato da un'assenza: due chip su tre risultavano troppo piccoli,
   il terzo no. Ora una riga che finisce fra due lettere è un testo troncato.
3. **Il bersaglio da 56 dp nel tema non bastava.** `LocalMinimumInteractiveComponentSize`
   riserva lo spazio nel layout, ma l'area che accetta il tocco la decide
   `LocalViewConfiguration.minimumTouchTargetSize`, che restava a 48. Lo schermo *sembrava*
   fatto per i guanti. Servono tutte e due, e un test a parte tocca davvero 26 dp sotto il
   centro dell'icona di aggiorna: fuori dai 48 dp del sistema, dentro i 56 del tema.
4. **Nei chip il contrasto misurava il bordo.** Con il testo grigio chiaro messo apposta, le
   etichette dei chip risultavano a 4,33:1 invece di 2,06. A testo doppio l'etichetta è alta
   quanto il chip, e il bordo del chip corre lungo i lati del suo rettangolo: era lui il colore
   più lontano dallo sfondo. Ora il rettangolo si stringe di 2 dp prima di contare i colori, e
   i chip grigi misurano 2,06.

`captureToImage()`, il modo canonico di avere i pixel in un test Compose, sotto Robolectric
aspetta un fotogramma che non arriva e va in timeout. Il test disegna la vista su una bitmap.

**Falsificato.** Ogni correzione è stata tolta da sola:

| Tolto | Cosa fallisce |
|---|---|
| `minimumTouchTargetSize` a 56 dp | tutti gli otto stati, con i bersagli a 48 dp, e il tocco sotto l'icona |
| `LocalMinimumInteractiveComponentSize` a 56 dp | le tre liste: i chip su due righe si sovrappongono |
| i chip che vanno a capo | le tre liste: «Chiusi» spezzato |
| il titolo corto | i cinque stati della lista: titolo troncato |
| la descrizione dell'indicatore del primo caricamento | `il primo caricamento resta usabile a testo doppio` |
| la descrizione dell'icona di aggiorna | i cinque stati della lista |
| il colore del testo, sostituito con un grigio chiaro | contrasti fra 1,67 e 2,06 in tutti gli stati |

**Il limite che resta nel contrasto misurato.** Il colore del testo è, fra quelli abbastanza
frequenti nel rettangolo del testo, il più lontano dallo sfondo. Stringere il rettangolo ha tolto
i bordi che lo costeggiano, non un elemento scuro che lo attraversi nel mezzo: un testo pallido
sopra un'icona scura passerebbe. Oggi nessun testo dell'app ha qualcosa sotto.

## Prestazioni: la misura, e un risultato nullo

Il piano chiedeva due numeri, prima e dopo i Baseline Profile, riproducibili da chiunque cloni
il repository. L'infrastruttura c'è e funziona. Il numero da mettere nel curriculum, su un
telefono di fascia alta, **non è uscito**, e questa sezione dice perché, invece di tacerlo.

**Come si misura.** Il modulo `:baselineprofile` guida l'APK di rilascio, R8 compreso, su un
telefono collegato:

```bash
./gradlew :app:generateBaselineProfile
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
```

Il primo comando genera i profili in `app/src/release/generated/baselineProfiles/`: il
Baseline Profile, 18.691 regole, dal percorso completo (avvio, scorrimento, un dettaglio
aperto e chiuso); il profilo di avvio, 16.068 regole, dal solo avvio. Serve Android 13 o
successivo, o un telefono con root. Il secondo misura avvio a freddo e scorrimento della
lista, dieci giri ciascuno, in due modalità:

- **senza compilazione** (`CompilationMode.None`). È l'app appena installata da un APK di una
  Release, che non riceve i profili dal cloud come quelle del Play Store. Verificato nel log:
  all'installazione `dex2oat` gira senza alcun profilo;
- **con il Baseline Profile** (`Partial` con `Require`): il log mostra una seconda
  compilazione con `--profile-file-fd`. `Require` fa fallire la misura se il profilo manca,
  invece di dare in silenzio il numero di prima.

**I dati sono generati.** Le build di misura leggono cinquanta rapporti fissi invece delle
issue di GitHub, perché:

- le issue del repository sono poche, e una lista che non scorre non si misura;
- cambiano nel tempo, e un numero preso su dati che cambiano non si riproduce;
- senza token GitHub concede sessanta richieste all'ora.

Cinquanta è il massimo che l'app chiede a GitHub. La scelta sta in `BENCHMARK_DATA`, vero solo
per le due build create dal plugin, e la release vera non ne porta traccia: `BenchmarkReportsApi`
compare 14 volte nel mapping di R8 della build di misura e zero in quello della release.

**I risultati.** Samsung Galaxy S20 (SM-G980F), Android 13, 16 settembre 2026; mediana di
dieci giri.

| | Senza compilazione | Con Baseline Profile |
|---|---|---|
| Primo fotogramma, prima prova | 314 ms | 312 ms |
| Primo fotogramma, seconda prova | 307 ms | 304 ms |
| Primo fotogramma, terza prova | 275 ms | 304 ms |
| Rapporti visibili, terza prova | 380 ms | 431 ms |
| Scorrimento, CPU per fotogramma, P50 / P90 / P99 | 7,8 / 12,2 / 22,8 ms | 8,0 / 12,3 / 24,1 ms |

**Nessuna differenza oltre il rumore.** Fra una prova e l'altra la stessa modalità si sposta di
trenta millisecondi, più di qualunque distanza fra le due colonne. Nella terza prova la
versione senza profilo esce più veloce, cosa che un profilo non può causare: è rumore, ed è la
ragione per cui un numero solo non si pubblica.

**Prima di crederci, tre verifiche che non fosse un errore di misura.**

1. Le due modalità compilano davvero in modo diverso, e lo dice il log di `dex2oat`, non la
   configurazione del benchmark.
2. Il profilo nell'APK è pieno: decodificato con `profgen dumpProfile`, contiene 11.167
   metodi, 6.166 di Compose e 236 dell'app.
3. Il primo fotogramma era soltanto l'indicatore di caricamento. Con `ReportDrawnWhen` il
   benchmark misura anche quando compaiono i rapporti (`timeToFullDisplayMs`): anche lì,
   nessun guadagno.

**La spiegazione probabile, non dimostrata.** Un S20 compila al volo il poco codice che
quest'app attraversa, abbastanza in fretta da non lasciare niente da guadagnare. I Baseline
Profile valgono di più sui telefoni lenti, che sono anche i terminali da campo di questo
dominio. La misura su un telefono di fascia bassa è il passo che manca, e il modulo è pronto:
servono solo il telefono e i due comandi.

**Quattro errori trovati nei benchmark, prima dei numeri.**

- Il generatore apriva «il primo rapporto», che dopo lo scorrimento non era più a schermo.
- Il profilo di avvio usciva identico al Baseline Profile, perché era generato dal percorso
  completo. Ora ha un percorso suo.
- Lo scorrimento con `startupMode = COLD` falliva: Macrobenchmark chiude il processo *dopo*
  il blocco di preparazione e prima di quello misurato. Il processo ora si chiude a mano
  all'inizio della preparazione.
- La prima metrica si fermava all'indicatore di caricamento, come detto sopra.

**Il costo.** L'APK di rilascio passa da 1.803.740 a 1.918.486 byte, 112 KB in più fra
`profileinstaller` e i profili dentro. Su un S20 li paga senza un guadagno misurato; sui telefoni per
cui esistono è da misurare. E secondo la documentazione di Android `profileinstaller` scrive il
profilo al primo avvio, ma ART lo compila solo nell'ottimizzazione in background, a telefono
inattivo e in carica: chi installa da una Release non ne beneficia al primo avvio. Qui non è
verificato. Il benchmark forza quella compilazione, e misura quindi il caso migliore.

## MVVM, in concreto

- La View osserva `StateFlow`, non chiama il ViewModel per leggere.
- Lo stato esposto è `StateFlow`, non `MutableStateFlow`: la UI può osservare ma non
  scrivere. Il flusso è unidirezionale per costruzione, non per disciplina.
- Un solo composable (`ReportsRoute`) conosce il ViewModel; `ReportsScreen` e i
  componenti sotto ricevono dati e risalgono eventi, quindi si vedono in anteprima e
  si testano senza dipendenze.

## Pattern usati

| Pattern | Dove | Perché |
|---|---|---|
| **Repository** | `ReportsRepository` | Contratto nel dominio, implementazione nel livello dati |
| **DTO + Mapper** | `ReportDto.toDomain()` | Il dominio non conosce il formato di rete. Un campo rinominato dal backend tocca una classe sola |
| **Result / Outcome** | `Outcome<T>` | L'errore è un valore di ritorno, non un'eccezione che attraversa i livelli |
| **Sealed hierarchy** | `ReportsUiState`, `DomainError` | `when` esaustivo senza `else`: stati impossibili non rappresentabili |
| **Strategy** | `ErrorMapper`, `ErrorTextProvider`, `StalenessPolicy` | Tradurre errori, scegliere i messaggi e decidere quando i dati sono vecchi sono decisioni sostituibili |
| **Single source of truth** | `ReportsLocalStore` | La UI osserva la cache; la rete la aggiorna e basta |
| **State hoisting** | `ReportsScreen` | I componenti non possiedono stato |
| **Provider dei dispatcher** | `DispatcherProvider` | Sostituibili tutti insieme nei test |
| **Orologio iniettato** | `Clock` | "I dati sono vecchi di sei minuti" si testa senza aspettare sei minuti |
| **State hoisting verificato** | `ReportsScreenTest` | La schermata si monta su uno stato costruito a mano, senza ViewModel: è la prova che la separazione regge |
| **Rotte tipizzate** | `FieldReportsNavHost` | Un argomento di navigazione sbagliato è un errore di compilazione |
| **Evento su `Channel`** | `ReportDetailViewModel.events` | Consegnato una volta sola, e non perso se nessuno sta ascoltando |
| **Dependency Injection** | `di/AppModules.kt`, Hilt | I ViewModel delle destinazioni senza factory scritte a mano, e il grafo di test uguale a quello vero meno un modulo |

## SOLID, punto per punto

**Single Responsibility.** Il repository faceva chiamata, mappatura, ordinamento e
gestione degli errori. La traduzione DTO sta nel mapper, quella delle eccezioni in
`ErrorMapper`: sono le due parti che cambiano per ragioni diverse dal resto. L'ordinamento
è finito nell'`ORDER BY` del DAO — ordinare in memoria una lista appena letta dal database
significa leggerla due volte.

**Open/Closed.** Aggiungere un tipo di errore significa aggiungere un caso a
`DomainError` — e il compilatore indica ogni `when` da aggiornare, invece di lasciare
un ramo scoperto.

**Interface Segregation.** `ReportsRepository` e `ReportsApi` hanno un metodo ciascuna.
`ErrorMapper` e `ErrorTextProvider` sono `fun interface`: si sostituiscono con un
lambda.

**Dependency Inversion.** Il punto corretto nella prima versione: il ViewModel
dipendeva dalla **classe concreta** `ReportsRepository`. Ora dipende dall'interfaccia
definita nel dominio, e il test sostituisce il repository invece di raggiungerlo
passando dall'API sottostante. Lo stesso vale un livello più sotto: il repository dipende
da `ReportsLocalStore` e non da Room, quindi si testa senza database.

## Il client HTTP, e perché la sorgente è mappata invece che inventata

Nessuna API pubblica restituisce rapporti di intervento tecnico. La scelta non era «quale
backend uso» ma «quale sorgente reale si mappa sul dominio senza fabbricare dati», perché dati
inventati dentro un mapper sono un segnale peggiore di nessun dato.

Le issue di un repository GitHub corrispondono quasi campo per campo: `title` al titolo,
`body` alla descrizione, `state` allo stato, `user.login` al tecnico, `created_at` alla data,
il nome del repository al cliente. L'API è pubblica, senza chiave, e il repository puntato è quello del progetto stesso
— quindi non dipende da un servizio di terzi che può sparire.

Il valore vero sta però nelle **imperfezioni**, che sono la ragione per cui un'API reale
insegna più di un file JSON con lo schema perfetto. `GitHubReportsApi` ne assorbe quattro:

1. **L'endpoint restituisce anche le pull request**, che non sono rapporti e non hanno un
   campo che le dichiari: si riconoscono dalla presenza di un oggetto `pull_request` che sulle
   issue non esiste.
2. **`IN_PROGRESS` non esiste su GitHub**, che conosce solo `open` e `closed`. Si deduce da
   un'etichetta `in progress`, e solo su una issue aperta — una chiusa è chiusa a prescindere
   da come era etichettata mentre ci si lavorava. È una regola di dominio applicata a dati che
   non la conoscono: il lavoro tipico di un mapper.
3. **Le date arrivano in ISO-8601** e il dominio le vuole in millisecondi.
4. **Il corpo di una issue scritta dal sito arriva con gli a capo `\r\n`.** Sono di GitHub, e
   si normalizzano nel mapper di GitHub: `ReportDto` non deve sapere da che sorgente viene il
   testo.

Il mapper si ferma a `ReportDto` invece di produrre direttamente un `Report`: così le difese
già scritte e già testate in `ReportDto.toDomain()` — id vuoti, titoli mancanti, stati
sconosciuti — continuano a valere identiche. Una seconda strada verso il dominio avrebbe
significato una seconda copia di quelle difese, cioè due copie che prima o poi divergono.

**Il criterio, verificabile nel `git diff`.** Sostituire la sorgente finta con quella vera non
ha toccato una riga sotto `domain/` né sotto `ui/`, e `ReportsViewModelTest` è uscito dal
commit senza modifiche. Le uniche due classi già esistenti coinvolte sono `ErrorMapper`, che
ha guadagnato due rami, e `AppContainer`, dove si sceglie l'implementazione concreta — che è
letteralmente il suo mestiere. Oggi quel posto è `di/AppModules.kt`.

**Core library desugaring.** `Instant.parse` richiede la API 26 e il `minSdk` è 24.
L'alternativa era un parser ISO-8601 scritto a mano: codice fragile, da testare, per risolvere
un problema che la libreria standard risolve già. Il desugaring costa un flag e una
dipendenza.

## Tre rappresentazioni dello stesso rapporto

`Report` (dominio), `ReportDto` (rete), `ReportEntity` (database). La ripetizione è voluta:
lo schema di una tabella e il contratto di un backend cambiano per ragioni diverse, in
momenti diversi. Annotare il modello di dominio con `@Entity` significherebbe che una
migrazione del database si porta dietro il dominio — e che il dominio dipende da Room.

Entrambi gli adattatori trattano lo stato come testo e lo interpretano con
`ReportStatus.fromRaw`, che degrada i valori sconosciuti invece di lanciare. Per il DTO
il motivo è il backend; per l'entity è una riga scritta da una versione diversa dell'app.
Un ordinale, per giunta, si romperebbe in silenzio al primo valore inserito in mezzo
all'enum.

## Il contratto della cache, verificato su entrambe le implementazioni

`ReportsLocalStore` ha due implementazioni — `RoomReportsLocalStore` e
`InMemoryReportsLocalStore` — e **una sola suite di test**: `ReportsLocalStoreContract`,
una classe astratta che le sottoclassi si limitano a istanziare.

È il modo per sapere che sono davvero intercambiabili invece di sperarlo: un test scritto
due volte diverge alla prima modifica, e a quel punto "implementano la stessa interfaccia"
resta vero solo per il compilatore. La versione Room gira con **Robolectric** su SQLite in
memoria, quindi dentro `./gradlew test` e quindi in CI — nessun emulatore, nessun job
aggiuntivo nella pipeline.

## I test di interfaccia, e perché stanno in `test` e non in `androidTest`

`androidTest` è il posto canonico per i test di UI, e sarebbe stata la scelta sbagliata: la
pipeline non ha un emulatore, quindi quei test sarebbero esistiti senza essere mai eseguiti.
Robolectric li fa girare sulla JVM, dove girano già tutti gli altri.

`ReportsScreenTest` monta `ReportsScreen` su uno stato costruito a mano — niente ViewModel,
niente repository, niente coroutine — e verifica cosa compare e quali eventi risalgono. È lo
state hoisting messo alla prova: se un domani un composable cominciasse a procurarsi i dati
da solo, questi test smetterebbero di compilare.

`ReportCardScreenshotTest` copre ciò che nessuna asserzione testuale vede: spaziature,
gerarchia tipografica, una riga che scivola sotto il bordo. Le immagini di riferimento sono
versionate in `app/src/test/screenshots/`.

**La trappola, e come è chiusa.** Roborazzi di default *scrive* l'immagine invece di
confrontarla: un normale `./gradlew test` avrebbe rigenerato i riferimenti a ogni esecuzione,
e la verifica successiva avrebbe confrontato un file con se stesso — sempre verde, sempre
inutile. `roborazzi.test.verify=true` in `gradle.properties` inverte il default: si confronta
sempre, e per aggiornare i riferimenti serve il comando esplicito
`./gradlew recordRoborazziDebug`. La CI esegue `verifyRoborazziDebug`, che comprende l'intera
suite di unit test, e carica come artefatto le immagini di differenza quando fallisce.

**La tolleranza è misurata, non indovinata.** I riferimenti si registrano su Windows e si
verificano su Linux, e l'antialiasing dei glifi arrotonda in modo leggermente diverso fra le
due piattaforme: con il confronto esatto la pipeline falliva sempre, cioè aveva smesso di
dire qualcosa. Prima di scegliere un numero ho misurato i due estremi sulle immagini vere:

| | Pixel diversi | Differenza massima |
|---|---|---|
| Rumore fra Windows e Linux | 0,08% | 2-4 livelli su 255 |
| Modifica reale più piccola (due glifi separatori) | 1,4% | 201 su 255 |

Sedici volte di distanza sul conteggio, cinquanta sull'intensità. La soglia è **0,3%**: circa
tre volte sopra il rumore e quattro volte sotto la più piccola regressione reale. Una
modifica che altera le dimensioni dell'immagine — spostare un padding di 2dp, per dire —
fallisce comunque, perché è una differenza strutturale che la soglia non riguarda.

Abbassare una soglia finché la build torna verde è il modo classico di trasformare un test in
un ornamento. Averla scelta fra due misure, e aver verificato che una regressione reale
continui a fallire, è ciò che la distingue da quel gesto.

## Effetti sulla testabilità

| Prima | Dopo |
|---|---|
| Il doppio era dell'API: per testare il ViewModel si passava da tutto il livello dati | Il doppio è del repository: si testa il ViewModel da solo |
| Mappatura e gestione errori non testabili separatamente | `ReportsRepositoryTest` copre DTO malformati, stati sconosciuti e traduzione degli errori |
| `init { refresh() }`: il caricamento partiva dal costruttore | `start()` esplicito e idempotente: si può asserire sullo stato iniziale |
| Il tempo si leggeva dove serviva | `Clock` iniettato: la scadenza della cache si verifica senza aspettare |
| Il livello di persistenza non esisteva | Un contratto solo, due implementazioni, la stessa suite su entrambe |
| Il grafo di navigazione si provava passandogli il repository come parametro | Si toglie un modulo Hilt e si inietta la cache in memoria: il grafo provato è quello dell'app |

## Dove ho consapevolmente semplificato

- **La firma di rilascio ripiega su quella di debug quando la chiave non c'è.**
  L'alternativa era far fallire la compilazione, e avrebbe reso il repository compilabile
  in rilascio solo da me. Il prezzo del ripiego è che `BUILD SUCCESSFUL` non significa più
  «APK distribuibile»: lo paga la pipeline di rilascio, che legge il certificato dell'APK e
  si ferma su `CN=Android Debug`. Verificato togliendo la chiave e ricompilando — l'APK
  esce firmato di debug senza un avviso.
- **La chiave è autofirmata e vale per entrambi i progetti dimostrativi.** Non è una
  identità verificata da nessuno: dice solo che due APK con lo stesso nome di pacchetto
  vengono dalla stessa mano. Per il Play Store servirebbe altro, e non è dove questi
  progetti vanno.
- **Il ViewModel del dettaglio ha due costruttori.** Hilt usa quello con il
  `SavedStateHandle`; i test del ViewModel usano quello con l'id esplicito, perché provano
  l'evento, e un `SavedStateHandle` costruito a mano sarebbe solo un modo più lungo di scrivere
  `"R-1"`. Il costruttore di Hilt non ha un test suo: lo provano i test sul grafo vero, che
  falliscono se l'id non arriva.
- **Nessun use case fra ViewModel e repository.** Con un'unica operazione di lettura
  sarebbe cerimonia. Diventerebbe utile con logica composta fra più sorgenti.
- **`replaceAll` sostituisce tutto invece di confrontare riga per riga.** È la scelta
  giusta finché l'app è in sola lettura: la lista arriva intera dal server, e un diff
  costerebbe complessità senza risolvere alcun problema. Diventerà sbagliata nel momento in
  cui esisteranno modifiche locali non ancora sincronizzate — quel giorno servirà una coda
  di scritture, non un merge improvvisato dentro lo store.
- **`fallbackToDestructiveMigration()`.** Questa è una cache, e tutto ciò che contiene è
  ricostruibile con una chiamata di rete: buttare il database a un cambio di schema costa un
  caricamento in più e risparmia una migrazione scritta per nulla. Lo schema è comunque
  esportato in `app/schemas/` e versionato, perché il giorno in cui l'app permetterà di
  scrivere rapporti quella riga diventa un bug e la migrazione va scritta rispetto a
  qualcosa.
  Con la colonna `description` lo schema è passato alla versione 2, ed è la prima volta che
  la scelta si esercita davvero. Un test apre con il codice nuovo un file scritto con lo
  schema 1 e verifica che si butti tutto, **data di sincronizzazione compresa**: una cache
  vuota con una data fresca sarebbe creduta valida, e l'app mostrerebbe «Nessun rapporto»
  fino alla scadenza invece di riscaricarli.
- **Nessun form di modifica.** Il piano lo chiedeva insieme al dettaglio, ma l'app legge le
  issue di GitHub senza autenticazione e non può scriverle. Le alternative erano due, e
  nessuna valeva le ore: una modifica solo locale richiede una coda di scritture, che è il
  tema di pos_sync e là è già dimostrato meglio; l'autenticazione apre la gestione dei
  segreti, che questo progetto ha già dichiarato fuori portata.
- **La descrizione è testo semplice.** Le issue sono scritte in Markdown, e nel dettaglio si
  vedono asterischi e cancelletti. Mostrarlo formattato vuol dire una libreria o un parser,
  per un campo che in un rapporto di intervento vero sarebbe testo semplice.
- **L'avviso sulla rimozione non sopravvive a una rotazione.** Lo snackbar vive nella
  composizione, e se si ruota mentre è visibile sparisce. L'evento non si ripete, che è il
  punto; il messaggio perso è il prezzo, ed è piccolo, perché la lista mostra comunque che il
  rapporto non c'è più.
- **La navigazione nell'APK offuscato non è provata su un dispositivo.** La pipeline di
  rilascio installa l'APK e controlla che l'app parta, ma non tocca una card. Le rotte
  tipizzate si risolvono con serializzatori generati a compilazione e non per riflessione,
  che è la ragione per cui R8 non dovrebbe toglierli; e l'output di R8 conferma che
  `ReportDetailDestination` e il suo serializzatore restano nell'APK. È una verifica sul codice
  prodotto, non sull'app che gira.
- **Le build di misura non leggono GitHub.** Cinquanta rapporti generati, per le ragioni scritte
  nella sezione sulle prestazioni. Si misura l'app, non la rete: il tempo di una chiamata vera
  non entra nei numeri.
- **Le prestazioni sono misurate su un telefono solo, e di fascia alta.** Il risultato vale
  per quel telefono.
- **L'accessibilità è provata con Robolectric, non con TalkBack su un telefono.** Il test
  verifica che ogni elemento abbia qualcosa da far leggere, non come suona letto. La card, per
  dire, si legge «R-1041 punto M. Rossi punto Aperto»: i separatori sono per l'occhio.
- **Solo il tema chiaro.** Il contrasto è misurato su quello, e l'app non segue il tema scuro
  del sistema: all'aperto, sotto il sole, è il chiaro che si legge.
- **56 dp è una scelta, non una misura.** Nessuno ha provato con i guanti quale sia il bersaglio
  giusto. Il valore sta in `MinTouchTarget`, e il test ne tiene uno suo: abbassarlo nel tema
  senza toccare il test fa fallire la build.
- **Una data a zero significa "assente".** È il valore con cui `ReportDto.toDomain()`
  rappresenta una data mancante o illeggibile, e il dettaglio lo mostra come «Data non
  disponibile». Un `Long?` nel dominio sarebbe più onesto; cambiarlo ora toccherebbe
  l'ordinamento della cache per un caso che la lista mostra già senza problemi.
- **Nessuna sincronizzazione in background.** La cache si aggiorna all'apertura della
  schermata e sul gesto dell'utente. Un `WorkManager` che drena quando la rete torna è il
  passo successivo, ed è lo stesso problema già risolto in pos_sync.
- **Nessuna autenticazione verso GitHub.** Senza token il limite è 60 richieste all'ora, che
  per una demo basta — e produce un `403` vero, cioè un errore reale da mostrare invece di uno
  simulato. Un token andrebbe conservato fuori dal repository, e la gestione dei segreti è un
  argomento a sé che questo progetto non ha ragione di aprire.
- **Nessuna paginazione.** Si chiedono le prime cinquanta issue e basta. Con una lista che
  cresce servirebbe la paginazione a scorrimento, che è però un lavoro di presentazione oltre
  che di rete: vale quando esiste il problema.
- **`FakeReportsApi` non è stata cancellata.** Continua a servire ai test e a far girare l'app
  senza rete. Due implementazioni dello stesso contratto sono il motivo per cui il contratto
  esisteva.
