package com.bdexcel.app

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * ওয়ার্কবুক(JSON) সিরিলাইজেশন/ডিসিরিলাইজেশন — লোকাল ব্যাকআপ/রিস্টোরের জন্য।
 * Gson লাইব্রেরি ব্যবহার করে विश्वাসযোগ্য সিরিলাইজেশন করা হয়।
 */
object WorkbookSerializer {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /** ওয়ার্কবুককে JSON স্ট্রিংতে রূপান্তর করা। */
    fun toJson(workbook: Workbook): String {
        val dto = WorkbookDto.from(workbook)
        return gson.toJson(dto)
    }

    /** JSON স্ট্রিং থেকে ওয়ার্কবুক রিকভার করা। */
    fun fromJson(json: String): Workbook {
        val dto = gson.fromJson(json, WorkbookDto::class.java)
            ?: WorkbookDto.empty()
        return dto.toWorkbook()
    }

    // ==================== ডাটা ট্রান্সফার অবজেক্ট (DTO) ====================

    /** ওয়ার্কবুক ডাটা ট্রান্সফার অবজেক্ট। */
    data class WorkbookDto(
        val id: String,
        val name: String,
        val activeSheetIndex: Int,
        val createdAt: Long,
        val updatedAt: Long,
        val sheets: List<SheetDto>
    ) {
        fun toWorkbook(): Workbook {
            val wb = Workbook(id, name, mutableListOf(), activeSheetIndex, createdAt, updatedAt)
            wb.sheets.clear()
            for (sdto in sheets) {
                wb.sheets.add(sdto.toSheet())
            }
            return wb
        }

        companion object {
            fun from(workbook: Workbook): WorkbookDto =
                WorkbookDto(
                    id = workbook.id,
                    name = workbook.name,
                    activeSheetIndex = workbook.activeSheetIndex,
                    createdAt = workbook.createdAt,
                    updatedAt = workbook.updatedAt,
                    sheets = workbook.sheets.map { SheetDto.from(it) }
                )

            fun empty(): WorkbookDto =
                WorkbookDto(
                    id = java.util.UUID.randomUUID().toString(),
                    name = "BD Workbook",
                    activeSheetIndex = 0,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    sheets = emptyList()
                )
        }
    }

    /** শিট ডাটা ট্রান্সফার অবজেক্ট। */
    data class SheetDto(
        val id: String,
        val name: String,
        val columnCount: Int,
        val rowCount: Int,
        val createdAt: Long,
        val updatedAt: Long,
        val cells: List<CellDto>
    ) {
        fun toSheet(): Sheet {
            val s = Sheet(id, name, mutableMapOf(), columnCount, rowCount, createdAt, updatedAt)
            for (cdto in cells) {
                s.setCell(cdto.toCell())
            }
            return s
        }

        companion object {
            fun from(sheet: Sheet): SheetDto =
                SheetDto(
                    id = sheet.id,
                    name = sheet.name,
                    columnCount = sheet.columnCount,
                    rowCount = sheet.rowCount,
                    createdAt = sheet.createdAt,
                    updatedAt = sheet.updatedAt,
                    cells = sheet.cellList().map { CellDto.from(it) }
                )
        }
    }

    /** সেল ডাটা ট্রান্সফার অবজেক্ট। */
    data class CellDto(
        val row: Int,
        val column: Int,
        val value: String,
        val formula: String,
        val evaluatedValue: String,
        val bold: Boolean,
        val italic: Boolean,
        val textColor: Int,
        val backgroundColor: Int,
        val fontSize: Float,
        val horizontalAlignment: Int,
        val verticalAlignment: Int,
        val wrapText: Boolean,
        val columnWidth: Float,
        val rowHeight: Float
    ) {
        fun toCell(): Cell =
            Cell(
                row = row, column = column, value = value, formula = formula,
                evaluatedValue = evaluatedValue, bold = bold, italic = italic,
                textColor = textColor, backgroundColor = backgroundColor,
                fontSize = fontSize, horizontalAlignment = horizontalAlignment,
                verticalAlignment = verticalAlignment, wrapText = wrapText,
                columnWidth = columnWidth, rowHeight = rowHeight
            )

        companion object {
            fun from(cell: Cell): CellDto =
                CellDto(
                    row = cell.row, column = cell.column, value = cell.value,
                    formula = cell.formula, evaluatedValue = cell.evaluatedValue,
                    bold = cell.bold, italic = cell.italic,
                    textColor = cell.textColor, backgroundColor = cell.backgroundColor,
                    fontSize = cell.fontSize, horizontalAlignment = cell.horizontalAlignment,
                    verticalAlignment = cell.verticalAlignment, wrapText = cell.wrapText,
                    columnWidth = cell.columnWidth, rowHeight = cell.rowHeight
                )
        }
    }
}
