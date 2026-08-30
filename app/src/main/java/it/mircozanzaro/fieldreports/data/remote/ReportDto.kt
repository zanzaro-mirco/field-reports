package it.mircozanzaro.fieldreports.data.remote

import it.mircozanzaro.fieldreports.domain.Report
import it.mircozanzaro.fieldreports.domain.ReportStatus

/**
 * Rappresentazione di rete di un rapporto.
 *
 * Volutamente più permissiva del modello di dominio: lo stato arriva come
 * stringa perché un backend può mandare un valore che non conosciamo, e questo
 * non deve far crashare l'app.
 */
data class ReportDto(
    val id: String?,
    val title: String?,
    val customer: String?,
    val status: String?,
    val createdAt: Long?,
    val technician: String?,
)

/**
 * Traduzione da DTO a dominio.
 *
 * È il punto unico in cui si assorbono le imperfezioni del backend: campi
 * mancanti, stati sconosciuti, valori nulli. Tutto ciò che passa di qui è
 * valido per definizione, quindi il resto dell'app non ha bisogno di
 * controlli difensivi sparsi.
 */
fun ReportDto.toDomain(): Report? {
    val safeId = id?.takeIf(String::isNotBlank) ?: return null
    return Report(
        id = safeId,
        title = title.orEmpty().ifBlank { "Senza titolo" },
        customer = customer.orEmpty(),
        status = ReportStatus.fromRaw(status),
        createdAtEpochMs = createdAt ?: 0L,
        technician = technician.orEmpty(),
    )
}
