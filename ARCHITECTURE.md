# Architettura e scelte di progetto

## Struttura

```
domain/                        nessuna dipendenza, nemmeno da Android
  Report.kt                    modello puro
  ReportsRepository.kt         contratto definito qui, implementato altrove
  Outcome.kt                   esito + gerarchia chiusa degli errori
data/
  remote/
    ReportDto.kt               rappresentazione di rete + mapper
    ReportsApi.kt              contratto + sorgente simulata
  DefaultReportsRepository.kt  implementazione
  ErrorMapper.kt               eccezioni tecniche -> errori di dominio
  DispatcherProvider.kt        i dispatcher come dipendenza
ui/
  ReportsUiState.kt            sealed interface
  ReportsViewModel.kt          StateFlow, viewModelScope
  ReportsScreen.kt             Compose, state hoisting
  ErrorTextProvider.kt         errore di dominio -> testo per l'utente
```

La regola: `domain` non importa nulla da `data` né da `ui`. Le frecce puntano
verso l'interno.

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
| **Strategy** | `ErrorMapper`, `ErrorTextProvider` | Tradurre errori e scegliere i messaggi sono decisioni sostituibili |
| **State hoisting** | `ReportsScreen` | I componenti non possiedono stato |
| **Provider dei dispatcher** | `DispatcherProvider` | Sostituibili tutti insieme nei test |

## SOLID, punto per punto

**Single Responsibility.** Il repository faceva chiamata, mappatura, ordinamento e
gestione degli errori. La traduzione DTO sta nel mapper, quella delle eccezioni in
`ErrorMapper`: sono le due parti che cambiano per ragioni diverse dal resto.

**Open/Closed.** Aggiungere un tipo di errore significa aggiungere un caso a
`DomainError` — e il compilatore indica ogni `when` da aggiornare, invece di lasciare
un ramo scoperto.

**Interface Segregation.** `ReportsRepository` e `ReportsApi` hanno un metodo ciascuna.
`ErrorMapper` e `ErrorTextProvider` sono `fun interface`: si sostituiscono con un
lambda.

**Dependency Inversion.** Il punto corretto nella prima versione: il ViewModel
dipendeva dalla **classe concreta** `ReportsRepository`. Ora dipende dall'interfaccia
definita nel dominio, e il test sostituisce il repository invece di raggiungerlo
passando dall'API sottostante.

## Effetti sulla testabilità

| Prima | Dopo |
|---|---|
| Il doppio era dell'API: per testare il ViewModel si passava da tutto il livello dati | Il doppio è del repository: si testa il ViewModel da solo |
| Mappatura e gestione errori non testabili separatamente | `ReportsRepositoryTest` copre DTO malformati, stati sconosciuti e traduzione degli errori |
| `init { refresh() }`: il caricamento partiva dal costruttore | `start()` esplicito e idempotente: si può asserire sullo stato iniziale |

## Dove ho consapevolmente semplificato

- **Niente Hilt.** Una factory scritta a mano è sufficiente per un grafo di tre
  oggetti. Hilt si giustifica quando il grafo cresce.
- **Nessun use case fra ViewModel e repository.** Con un'unica operazione di lettura
  sarebbe cerimonia. Diventerebbe utile con logica composta fra più sorgenti.
- **Nessuna cache locale.** È la prima voce dei prossimi passi.
