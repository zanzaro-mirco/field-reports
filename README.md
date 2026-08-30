# field-reports

App Android in **Kotlin + Jetpack Compose** per la consultazione di rapporti di intervento
tecnico: lista, filtri per stato, cache locale consultabile offline, gestione esplicita degli
stati di caricamento ed errore.

[![CI](https://github.com/zanzaro-mirco/field-reports/actions/workflows/ci.yml/badge.svg)](https://github.com/zanzaro-mirco/field-reports/actions/workflows/ci.yml)

## Perché questo progetto

È un'app volutamente piccola, costruita per mostrare **come** si scrive Android oggi:
Compose al posto degli XML, coroutine e `StateFlow` al posto di callback e `LiveData`,
stato modellato come gerarchia chiusa invece che come una manciata di booleani.

Il dominio — rapporti di intervento sul campo, con clienti, tecnici e stati di lavorazione —
viene dall'ambito in cui ho lavorato più a lungo: software per operatori che stanno fuori
sede, spesso su dispositivi lenti e con connettività incerta.

## Le scelte che vale la pena guardare

**Lo stato è una `sealed interface`.**
`Loading`, `Ready`, `Error`. Il `when` che le distingue è esaustivo senza `else`: se domani
si aggiunge uno stato, il compilatore segnala ogni punto da aggiornare invece di lasciare
un ramo scoperto in silenzio. È l'alternativa al classico trio
`isLoading` / `data` / `errorMessage`, che permette stati impossibili come "sto caricando
e ho anche un errore".

**La cache locale è la sorgente unica, non un ripiego.**
Room sta sotto tutto: la UI legge sempre dal database, e la rete si limita ad aggiornarlo.
Non c'è un solo ramo `if (isOnline)` nella presentazione — offline la sorgente contiene
semplicemente i dati di prima. La conseguenza che conta: **se la sincronizzazione fallisce, la
cache non viene toccata**, e un errore di rete su dati già scaricati diventa un avviso sopra
la lista invece di una schermata vuota. `ReportsUiState.Error` copre lo schermo solo quando
non c'è davvero nulla da mostrare.

**Il filtro non rifà la chiamata di rete.**
`setFilter` trasforma lo stato già in memoria. Sembra un dettaglio, ma è la differenza tra
un'app che risponde istantaneamente e una che riscarica tutto a ogni tocco. C'è un test
che verifica proprio che il contatore delle chiamate non aumenti.

**I dispatcher sono iniettati.**
`ReportsRepository` riceve il dispatcher invece di usare `Dispatchers.IO` cablato. Nei test
si passa un `StandardTestDispatcher` e la suite diventa deterministica: gira in millisecondi
e non dipende dalla velocità della macchina di CI.

**Un solo composable conosce il ViewModel.**
`ReportsRoute` fa da ponte; `ReportsScreen` e i componenti sotto ricevono dati e risalgono
eventi. Sono quindi visualizzabili in anteprima e testabili senza dipendenze — è lo state
hoisting applicato sul serio, non solo citato.

**Il ViewModel dipende da un'interfaccia, non da una classe.**
`ReportsRepository` è definita nel dominio e implementata nel livello dati. Sembra un
dettaglio, ma è ciò che permette al test di sostituire il repository invece di
raggiungerlo passando dall'API sottostante — un livello troppo in basso.

**Gli errori sono valori, non eccezioni.**
`Outcome<T>` con una gerarchia chiusa di `DomainError`: fuori dal livello dati non
circola mai una `IOException`. Cambiare client HTTP tocca una classe sola.

**Fake, non mock.**
`FakeApi` nei test è un'implementazione semplificata ma funzionante dell'interfaccia. Non si
rompe quando cambia la firma di un metodo, a differenza dei mock configurati chiamata per
chiamata.

**Un contratto solo, due implementazioni, la stessa suite su entrambe.**
`ReportsLocalStore` è implementato da Room e da uno store in memoria, e i test sono scritti
una volta sola in una classe astratta che le due sottoclassi si limitano a istanziare. È il
modo per sapere che sono davvero intercambiabili invece di sperarlo. La versione Room gira con
Robolectric su SQLite in memoria: dentro `./gradlew test`, quindi in CI, senza emulatore.

## Struttura

```
app/src/main/java/it/mircozanzaro/fieldreports/
  MainActivity.kt              la schermata
  AppContainer.kt              composition root
  FieldReportsApplication.kt   tiene in vita il grafo quanto il processo
  domain/                      nessuna dipendenza, nemmeno da Android
    Report.kt  ReportsRepository.kt  Outcome.kt
  data/
    remote/ReportDto.kt        rappresentazione di rete + mapper
    remote/ReportsApi.kt       contratto + sorgente simulata
    local/                     Room: entity, DAO, database
    local/ReportsLocalStore.kt contratto della cache + implementazione in memoria
    DefaultReportsRepository.kt
    ErrorMapper.kt             eccezioni tecniche -> errori di dominio
    DispatcherProvider.kt
    Clock.kt  StalenessPolicy.kt   il tempo e quando i dati sono vecchi
  ui/
    ReportsUiState.kt          sealed interface
    ReportsViewModel.kt        StateFlow, viewModelScope
    ReportsScreen.kt           Compose, state hoisting
    ErrorTextProvider.kt       errore di dominio -> testo per l'utente
app/src/test/                  test del ViewModel e del livello dati, senza Android
```

Le scelte architetturali e i principi applicati sono in [ARCHITECTURE.md](ARCHITECTURE.md).

## Test

```bash
./gradlew test
```

| Test | Cosa verifica |
|---|---|
| `lo stato iniziale e Loading` | Nulla parte dal costruttore: si può asserire sullo stato di partenza |
| `start carica una sola volta` | L'avvio è idempotente |
| `un errore di rete arriva come DomainError` | Fuori dal livello dati non circolano eccezioni tecniche |
| `il filtro non rifa la chiamata` | Il contatore delle chiamate resta a 1 |
| `rimuovere il filtro mostra tutti` | Il filtro non distrugge i dati |
| `refresh ripete la chiamata` | Il pull-to-refresh funziona davvero |
| `scarta i record senza id` | Un JSON malformato non fa crashare l'app |
| `uno stato sconosciuto si degrada` | Un valore nuovo dal backend non rompe il client |
| `IOException -> Network`, `timeout -> Timeout` | La traduzione degli errori è testata da sola |
| **`un refresh fallito non svuota la cache`** | La garanzia su cui si regge la consultazione offline |
| `con dati in cache un errore di rete non nasconde i rapporti` | Offline si vede la lista, non una schermata di errore |
| `refreshIfStale non tocca la rete se i dati sono freschi` | Riaprire l'app tre volte in un minuto non vale tre chiamate |
| `il filtro sopravvive a un aggiornamento della cache` | Una scrittura dal database non cancella la scelta dell'utente |
| `ReportsLocalStoreContract` (×2) | Store in memoria e Room rispettano lo stesso contratto |
| **`i rapporti sopravvivono alla chiusura del database`** | Il criterio della consultazione offline, verificato su file e non in memoria |
| `un orologio che torna indietro non congela la cache` | Il caso limite che nessuno prova finché non capita |

Gli stati si osservano con **Turbine**, che permette di asserire su un flusso di emissioni
invece che su un singolo valore finale.

## Requisiti

- JDK 17
- Android Studio recente (o solo Gradle da riga di comando)
- `minSdk` 24, `compileSdk` 34

> Le versioni delle dipendenze stanno in `gradle/libs.versions.toml`. Prima di pubblicare
> il repository conviene allinearle agli aggiornamenti correnti.

## Stato e prossimi passi

- [x] Cache locale con Room, per la consultazione offline
- [ ] Client HTTP reale (Retrofit o Ktor) al posto di `FakeReportsApi`
- [ ] Sincronizzazione in background quando la rete torna
- [ ] Hilt al posto della factory scritta a mano, quando i grafi cresceranno
- [ ] Compose UI test sulla schermata (i `testTag` sono già in posizione)
- [ ] Schermata di dettaglio con navigazione

## Licenza

MIT
