package it.mircozanzaro.fieldreports.data.remote

/**
 * La sorgente delle build su cui si misurano le prestazioni.
 *
 * Cinquanta rapporti, come il massimo che l'app chiede a GitHub: è la lista più
 * lunga che un utente vero può vedere, quindi il caso peggiore dello
 * scorrimento. Sono generati e sempre uguali, perché una misura presa su dati
 * che cambiano fra un giro e l'altro non si confronta con niente.
 *
 * Nessuna latenza: si misura l'app, non una rete finta. Descrizioni e titoli
 * hanno lunghezze diverse, perché righe tutte alte uguali renderebbero lo
 * scorrimento più facile di quanto sia.
 */
class BenchmarkReportsApi(private val count: Int = 50) : ReportsApi {

    override suspend fun fetchReports(): List<ReportDto> = List(count) { index ->
        val number = 2000 + index
        ReportDto(
            id = "R-$number",
            title = titles[index % titles.size],
            customer = customers[index % customers.size],
            status = statuses[index % statuses.size],
            createdAt = 1_753_600_000_000 - index * 3_600_000L,
            technician = technicians[index % technicians.size],
            description = "Intervento numero $number. ".repeat(1 + index % 4),
        )
    }

    private companion object {
        val titles = listOf(
            "Sostituzione contatore trifase",
            "Verifica lettore RFID al varco carraio dei mezzi pesanti",
            "Taratura stampante fiscale",
            "Cablaggio armadio di rete al secondo piano del magazzino",
            "Aggiornamento firmware terminale",
        )
        val customers = listOf("Acquedotto Nord", "Logistica Veneta", "Supermercati Est")
        val statuses = listOf("OPEN", "IN_PROGRESS", "CLOSED")
        val technicians = listOf("M. Rossi", "L. Bianchi", "G. Verdi", "A. Neri")
    }
}
