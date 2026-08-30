package it.mircozanzaro.fieldreports.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import it.mircozanzaro.fieldreports.domain.Report
import it.mircozanzaro.fieldreports.domain.ReportStatus

/**
 * Rappresentazione su database di un rapporto.
 *
 * È una terza classe accanto a [Report] e `ReportDto`, e la ripetizione è
 * voluta: lo schema di una tabella e il contratto di un backend cambiano per
 * ragioni diverse, in momenti diversi. Far annotare il modello di dominio con
 * `@Entity` significherebbe che una migrazione del database si porta dietro il
 * dominio — e che il dominio dipende da Room.
 *
 * Lo stato è salvato come stringa e non come enum ordinale per lo stesso motivo
 * per cui il DTO lo tratta come testo: una riga scritta da una versione diversa
 * dell'app può contenere un valore che oggi non conosciamo, e leggerla non deve
 * far crashare l'app. Un ordinale, per giunta, si romperebbe in silenzio al
 * primo valore inserito in mezzo all'enum.
 */
@Entity(tableName = "reports")
data class ReportEntity(
    @PrimaryKey val id: String,
    val title: String,
    val customer: String,
    val status: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    val technician: String,
)

fun ReportEntity.toDomain(): Report = Report(
    id = id,
    title = title,
    customer = customer,
    status = ReportStatus.fromRaw(status),
    createdAtEpochMs = createdAtEpochMs,
    technician = technician,
)

fun Report.toEntity(): ReportEntity = ReportEntity(
    id = id,
    title = title,
    customer = customer,
    status = status.name,
    createdAtEpochMs = createdAtEpochMs,
    technician = technician,
)
