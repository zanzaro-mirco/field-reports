# Architettura e scelte di progetto

## Struttura

```
AppContainer.kt                composition root: l'unico punto che conosce le classi concrete
FieldReportsApplication.kt     tiene in vita il grafo quanto il processo
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
  ReportsUiState.kt            sealed interface
  ReportsViewModel.kt          StateFlow, viewModelScope
  ReportsScreen.kt             Compose, state hoisting
  ErrorTextProvider.kt         errore di dominio -> testo per l'utente
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

## Effetti sulla testabilità

| Prima | Dopo |
|---|---|
| Il doppio era dell'API: per testare il ViewModel si passava da tutto il livello dati | Il doppio è del repository: si testa il ViewModel da solo |
| Mappatura e gestione errori non testabili separatamente | `ReportsRepositoryTest` copre DTO malformati, stati sconosciuti e traduzione degli errori |
| `init { refresh() }`: il caricamento partiva dal costruttore | `start()` esplicito e idempotente: si può asserire sullo stato iniziale |
| Il tempo si leggeva dove serviva | `Clock` iniettato: la scadenza della cache si verifica senza aspettare |
| Il livello di persistenza non esisteva | Un contratto solo, due implementazioni, la stessa suite su entrambe |

## Dove ho consapevolmente semplificato

- **Niente Hilt.** `AppContainer` scritto a mano è sufficiente per un grafo di quattro
  oggetti. Hilt si giustifica quando la costruzione a mano diventa il problema, non prima.
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
- **Nessuna sincronizzazione in background.** La cache si aggiorna all'apertura della
  schermata e sul gesto dell'utente. Un `WorkManager` che drena quando la rete torna è il
  passo successivo, ed è lo stesso problema già risolto in pos_sync.
