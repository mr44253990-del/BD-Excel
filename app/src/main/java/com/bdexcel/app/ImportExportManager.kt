package com.bdexcel.app

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.opencsv.CSVReader
import com.opencsv.CSVWriter
import java.io.*
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook

/**
 * বড় বড় এক্সেল/সিএসভ ফাইল ইমপোর্ট এবং এক্সপোর্ট ম্যানেজার।
 * রিয়েল এক্সেল থেকে ফাইল দিলে ওপেন করা যাবে এবং এখান থেকে সঠিক ফরম্যাটে এক্সপোর্ট হবে।
 */
class ImportExportManager(private val context: Context) {

    companion object {
        const val AUTHORITY = "com.bdexcel.app.fileprovider"
    }

    /** রियাল এক্সেল (.xlsx/.xls) বা .csv ফাইল থেকে ওয়ার্কবুক লোড করা। */
    fun importWorkbook(file: File): Workbook {
        return when (file.extension.lowercase()) {
            "xlsx", "xls" -> importXlsx(file)
            "csv" -> importCsv(file)
            else -> throw IllegalArgumentException("Unsupported file type: ${file.extension}")
        }
    }

    private fun importXlsx(file: File): Workbook {
        val wb = Workbook(java.util.UUID.randomUUID().toString(), file.nameWithoutExtension)
        try (fis = FileInputStream(file); workbook = WorkbookFactory.create(fis)) {
            var sheetIndex = 0
            for (sheet in workbook) {
                val bdxSheet = Sheet(java.util.UUID.randomUUID().toString(), sheet.sheetName)
                val maxRow = sheet.lastRowNum
                val maxCol: Int
                try { maxCol = sheet.rowIterator().next()?.lastCellNum ?: 26 } catch (_: Exception) { maxCol = 26 }
                bdxSheet.columnCount = maxCol.coerceAtLeast(26)
                bdxSheet.rowCount = (maxRow + 1).coerceAtLeast(1000)
                for (r in 0 until bdxSheet.rowCount) {
                    val row = sheet.getRow(r) ?: continue
                    for (c in 0 until bdxSheet.columnCount) {
                        val cell = row.getCell(c) ?: continue
                        val cellValue = cellToString(cell)
                        if (cellValue.isNotEmpty()) {
                            bdxSheet.setCell(
                                Cell(r, c, value = cellValue,
                                    backgroundColor = cellBackgroundColor(cell),
                                    textColor = cellTextColor(cell),
                                    bold = cell.bold,
                                    italic = cell.italic,
                                    fontSize = cell.fontSize?.toFloat() ?: 14f,
                                    horizontalAlignment = cellAlignment(cell))
                            )
                        }
                    }
                }
                wb.sheets.add(bdxSheet)
                if (sheetIndex == 0) wb.activeSheetIndex = 0
                sheetIndex++
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to import XLSX: ${e.message}", e)
        }
        wb.updatedAt = System.currentTimeMillis()
        return wb
    }

    private fun importCsv(file: File): Workbook {
        val wb = Workbook(java.util.UUID.randomUUID().toString(), file.nameWithoutExtension)
        val sheet = Sheet(java.util.UUID.randomUUID().toString(), "Sheet1")
        try (reader = CSVReader(FileReader(file))) {
            var r = 0
            for (line in reader) {
                if (line.isEmpty()) continue
                for (c in line.indices) {
                    val v = line[c]
                    if (v.isNotEmpty()) sheet.setCell(Cell(r, c, value = v))
                }
                r++
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to import CSV: ${e.message}", e)
        }
        wb.sheets.add(sheet)
        wb.updatedAt = System.currentTimeMillis()
        return wb
    }

    /** ওয়ার্কবুককে .xlsx ফাইলে এক্সপোর্ট করা (সঠিক ফরম্যাটে)। */
    fun exportXlsx(workbook: Workbook, dest: File): File {
        val wb = org.apache.poi.xssf.usermodel.XSSFWorkbook()
        try {
            for (sheet in workbook.sheets) {
                val poiSheet = wb.createSheet(sheet.name)
                val styleMap = mutableMapOf<Int, CellStyle>()
                for (cell in sheet.cellList()) {
                    val row = poiSheet.createRow(cell.row)
                    val poiCell = row.createCell(cell.column)
                    poiCell.setCellValue(if (cell.formula.isNotEmpty()) cell.formula else cell.value)
                    val styleId = cellStyleKey(cell)
                    val style = styleMap.getOrPut(styleId) { createPoiStyle(wb, cell) }
                    poiCell.cellStyle = style
                }
                for (c in 0 until sheet.columnCount) poiSheet.setColumnWidth(c, (sheet.columnWidth(c) * 256).toInt())
            }
            dest.parentFile.mkdirs()
            try (fos = FileOutputStream(dest)) { wb.write(fos) }
        } catch (e: Exception) {
            throw RuntimeException("Failed to export XLSX: ${e.message}", e)
        }
        return dest
    }

    /** ওয়ার্কবুককে .csv ফাইলে এক্সপোর্ট করা। */
    fun exportCsv(workbook: Workbook, dest: File, sheetIndex: Int = 0): File {
        dest.parentFile.mkdirs()
        try (writer = CSVWriter(FileWriter(dest))) {
            val sheet = workbook.sheets.getOrElse(sheetIndex) { workbook.activeSheet }
            val maxRow = sheet.cellList().maxByOrNull { it.row }?.row ?: 0
            val maxCol = sheet.cellList().maxByOrNull { it.column }?.column ?: 0
            for (r in 0..maxRow) {
                val rowVals = Array(maxCol + 1) { "" }
                for (c in 0..maxCol) rowVals[c] = sheet.getCell(r, c).value
                writer.writeNext(rowVals)
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to export CSV: ${e.message}", e)
        }
        return dest
    }

    /** শিটকে ছবি (পিএনজি) হিসেবে সেভ করা। */
    fun exportSheetAsImage(workbook: Workbook, sheetIndex: Int, dest: File, width: Int, height: Int): File {
        // বিটম্যাপ রেন্ডারিং MainActivity/ImageExportManager থেকে呼ばれে থাকে।
        // এখানে শুধু ডেস্টিনেশন ফাইল রেডি করা হয়।
        dest.parentFile.mkdirs()
        return dest
    }

    /** ওয়ার্কবুককে লোকাল ফাইলে সেভ করা (JSON-অ্যাস-ব্যাকআপ)। */
    fun saveWorkbookToFile(workbook: Workbook, dest: File) {
        dest.parentFile.mkdirs()
        try (fos = FileOutputStream(dest); osw = OutputStreamWriter(fos, "UTF-8")) {
            osw.write(WorkbookSerializer.toJson(workbook))
        } catch (e: Exception) {
            throw RuntimeException("Failed to save workbook: ${e.message}", e)
        }
    }

    fun loadWorkbookFromFile(file: File): Workbook {
        try (fis = FileInputStream(file); isr = InputStreamReader(fis, "UTF-8")) {
            return WorkbookSerializer.fromJson(isr.readText())
        } catch (e: Exception) {
            throw RuntimeException("Failed to load workbook: ${e.message}", e)
        }
    }

    /** কনটেন্ট ইউরির থেকে ফাইল পাঠ করা (FileProvider ব্যবহার করে অন্যান্য 앱 থেকে ওপেন)। */
    fun uriToFile(uri: Uri): File? {
        return try {
            File(context.contentResolver.openFileDescriptor(uri, "r")?.fileDescriptor ?: return null)
        } catch (e: Exception) {
            null
        }
    }

    /** একটি ফাইলের জন্য শেয়ারযোগ্য কনটেন্ট ইউরির তৈরি করা। */
    fun fileToUri(file: File): Uri = FileProvider.getUriForFile(context, AUTHORITY, file)

    private fun cellToString(cell: Cell): String {
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue
            CellType.NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) cell.dateCellValue?.toString() ?: cell.numericCellValue.toString()
                else cell.numericCellValue.toString()
            }
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.FORMULA -> cell.cellFormula
            else -> ""
        }
    }

    private fun cellBackgroundColor(cell: Cell): Int {
        val fill = cell.cellStyle.fillForegroundColorColor
        return if (fill != null) fill.argb else 0
    }

    private fun cellTextColor(cell: Cell): Int {
        val font = cell.cellStyle.fontAtIndex(0)
        return if (font != null && font.color != null) font.color.argb else -16777216
    }

    private fun cellAlignment(cell: Cell): Int {
        return when (cell.cellStyle.alignment) {
            HorizontalAlignment.CENTER -> 1
            HorizontalAlignment.RIGHT -> 2
            else -> 0
        }
    }

    private fun cellStyleKey(cell: Cell): Int {
        return (cell.backgroundColor * 31 + cell.textColor * 17 + cell.bold.hashCode() * 7 + cell.fontSize.hashCode() * 13).takeIf { it > 0 } ?: 1
    }

    private fun createPoiStyle(wb: Workbook, cell: Cell): CellStyle {
        val style = wb.createStyle()
        style.fillForegroundColor = IndexedColors.WHITE.index
        style.fillPattern = FillPatternType.SOLID_FOREGROUND
        if (cell.backgroundColor != 0) style.setFillForegroundColor(cell.backgroundColor)
        val font = wb.createFont()
        font.isBold = cell.bold
        font.isItalic = cell.italic
        font.fontHeightInPoints = (cell.fontSize.coerceAtLeast(8)).toShort()
        if (cell.textColor != -16777216) font.setColor(cell.textColor)
        style.font = font
        style.alignment = when (cell.horizontalAlignment) {
            1 -> HorizontalAlignment.CENTER
            2 -> HorizontalAlignment.RIGHT
            else -> HorizontalAlignment.LEFT
        }
        style.wrapText = cell.wrapText
        return style
    }

    private val String.extension: String
        get() = substringAfterLast('.').lowercase()

    private val Int.hashCode: Int
        get() = this
}
