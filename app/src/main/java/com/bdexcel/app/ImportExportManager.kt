package com.bdexcel.app

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.opencsv.CSVReader
import com.opencsv.CSVWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.Font
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet as POISheet
import org.apache.poi.ss.usermodel.Workbook as POIWorkbook
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xssf.usermodel.XSSFColor
import org.apache.poi.xssf.usermodel.XSSFWorkbook

/**
 * বড় বড় এক্সেল/সিএসভ ফাইল ইমপোর্ট এবং এক্সপোর্ট ম্যানেজার।
 * রিয়াল এক্সেল (.xlsx/.xls) বা .csv ফাইল থেকে ওয়ার্কবুক লোড করা যাবে এবং এখান থেকে সঠিক ফরম্যাটে এক্সপোর্ট হবে।
 */
class ImportExportManager(val context: Context) {

    companion object {
        const val AUTHORITY = "com.bdexcel.app.fileprovider"
    }

    /** রিয়াল এক্সেল (.xlsx/.xls) বা .csv ফাইল থেকে ওয়ার্কবুক লোড করা। */
    fun importWorkbook(file: File): Workbook {
        return when (file.extension.lowercase()) {
            "xlsx", "xls" -> importXlsx(file)
            "csv" -> importCsv(file)
            else -> throw IllegalArgumentException("Unsupported file type: ${file.extension}")
        }
    }

    private fun importXlsx(file: File): Workbook {
        val wb = Workbook(java.util.UUID.randomUUID().toString(), file.nameWithoutExtension)
        try (fis = FileInputStream(file); poiWorkbook = WorkbookFactory.create(fis)) {
            var sheetIndex = 0
            for (poiSheet in poiWorkbook) {
                val bdxSheet = Sheet(java.util.UUID.randomUUID().toString(), poiSheet.sheetName)
                val maxRow = poiSheet.lastRowNum
                val maxCol: Int
                try {
                    val firstRow = poiSheet.rowIterator().next()
                    maxCol = firstRow?.lastCellNum ?: 26
                } catch (_: Exception) {
                    maxCol = 26
                }
                bdxSheet.columnCount = maxCol.coerceAtLeast(26)
                bdxSheet.rowCount = (maxRow + 1).coerceAtLeast(1000)
                for (r in 0 until bdxSheet.rowCount) {
                    val row: Row? = poiSheet.getRow(r)
                    if (row == null) continue
                    for (c in 0 until bdxSheet.columnCount) {
                        val cell: Cell? = row.getCell(c)
                        if (cell == null) continue
                        val cellValue = cellToString(cell)
                        if (cellValue.isNotEmpty()) {
                            bdxSheet.setCell(
                                Cell(
                                    row = r, column = c, value = cellValue,
                                    backgroundColor = cellBackgroundColor(cell),
                                    textColor = cellTextColor(cell, poiWorkbook),
                                    bold = cellFontBold(cell, poiWorkbook),
                                    italic = cellFontItalic(cell, poiWorkbook),
                                    fontSize = cellFontSize(cell, poiWorkbook),
                                    horizontalAlignment = cellAlignment(cell)
                                )
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
        val wb = XSSFWorkbook()
        try {
            for (sheet in workbook.sheets) {
                val poiSheet: POISheet = wb.createSheet(sheet.name)
                for (cell in sheet.cellList()) {
                    val row = poiSheet.createRow(cell.row)
                    val poiCell = row.createCell(cell.column)
                    poiCell.setCellValue(if (cell.formula.isNotEmpty()) cell.formula else cell.value)
                    val style = createPoiStyle(wb, cell)
                    poiCell.cellStyle = style
                }
                for (c in 0 until sheet.columnCount) {
                    poiSheet.setColumnWidth(c, (sheet.columnWidth(c) * 256).toInt())
                }
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

    /** শিটকে ছবি (পিএনজি) হিসেবে সেভ করার পথ প্রস্তুত করা। */
    fun exportSheetAsImage(workbook: Workbook, sheetIndex: Int, dest: File, width: Int, height: Int): File {
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

    /** কনটেন্ট ইউরির থেকে ফাইল পাঠ করা (FileProvider ব্যবহার করে অন্যান্য অ্যাপ থেকে ওপেন)। */
    fun uriToFile(uri: Uri): File? {
        return try {
            File(context.contentResolver.openFileDescriptor(uri, "r")?.fileDescriptor ?: return null)
        } catch (e: Exception) {
            null
        }
    }

    /** একটি ফাইলের জন্য শেয়ারযোগ্য কনটেন্ট ইউরির তৈরি করা। */
    fun fileToUri(file: File): Uri = FileProvider.getUriForFile(context, AUTHORITY, file)

    // ---------- POI সেল থেকে বৈশিষ্ট্য পাঠানো ----------

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
        return if (fill is XSSFColor) fill.argb else 0
    }

    private fun cellTextColor(cell: Cell, poiWorkbook: POIWorkbook): Int {
        val font: Font = cell.cellStyle.getFont(poiWorkbook)
        val color = font.color
        return if (color is XSSFColor) color.argb else -16777216
    }

    private fun cellFontBold(cell: Cell, poiWorkbook: POIWorkbook): Boolean {
        return cell.cellStyle.getFont(poiWorkbook).isBold
    }

    private fun cellFontItalic(cell: Cell, poiWorkbook: POIWorkbook): Boolean {
        return cell.cellStyle.getFont(poiWorkbook).isItalic
    }

    private fun cellFontSize(cell: Cell, poiWorkbook: POIWorkbook): Float {
        return cell.cellStyle.getFont(poiWorkbook).fontHeightInPoints.toFloat()
    }

    private fun cellAlignment(cell: Cell): Int {
        return when (cell.cellStyle.alignment) {
            HorizontalAlignment.CENTER -> 1
            HorizontalAlignment.RIGHT -> 2
            else -> 0
        }
    }

    private fun createPoiStyle(wb: XSSFWorkbook, cell: Cell): CellStyle {
        val style = wb.createCellStyle()
        style.fillForegroundColor = IndexedColors.WHITE.index
        style.fillPattern = FillPatternType.SOLID_FOREGROUND
        if (cell.backgroundColor != 0) {
            style.setFillForegroundColor(XSSFColor.fromArgb(cell.backgroundColor))
        }
        val font = wb.createFont()
        font.isBold = cell.bold
        font.isItalic = cell.italic
        font.fontHeightInPoints = (cell.fontSize.coerceAtLeast(8)).toShort()
        if (cell.textColor != -16777216) {
            font.setColor(XSSFColor.fromArgb(cell.textColor))
        }
        style.setFont(font)
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
}
