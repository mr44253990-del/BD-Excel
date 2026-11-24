package com.bdexcel.app.io

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.bdexcel.app.engine.Calculator
import com.bdexcel.app.model.CellRef
import com.bdexcel.app.model.CellStyle
import com.bdexcel.app.model.Sheet
import java.io.OutputStream

/** Exports a sheet as an A4 PDF table using the platform PdfDocument. */
object PdfCodec {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 24f
    private const val ROW_H = 18f
    private const val FONT = 8f

    fun write(sheet: Sheet, out: OutputStream) {
        val doc = PdfDocument()
        val calc = Calculator(sheet)

        var maxRow = -1
        var maxCol = -1
        for ((k, cell) in sheet.cells) {
            if (cell.raw.isBlank() && cell.image == null) continue
            val p = CellRef.parseKey(k) ?: continue
            if (p.second > maxRow) maxRow = p.second
            if (p.first > maxCol) maxCol = p.first
        }
        val cols = (maxCol + 1).coerceAtLeast(1)
        val rows = (maxRow + 1).coerceAtLeast(1)
        if (cols > 40) return writePagedDoc(doc, sheet, calc, out, 40)

        val colW = (PAGE_W - 2 * MARGIN) / cols
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = FONT }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 0.5f; color = Color.LTGRAY }
        val headerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#006A4E") }
        val headerText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = FONT; color = Color.WHITE; textAlign = Paint.Align.CENTER }

        var pageNo = 0
        var canvas: Canvas? = null
        var page: PdfDocument.Page? = null

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create()
            val p = doc.startPage(info)
            page = p
            canvas = p.canvas
            canvas!!.drawColor(Color.WHITE)
            // column header band
            canvas!!.drawRect(MARGIN, MARGIN, PAGE_W - MARGIN, MARGIN + ROW_H, headerFill)
            for (c in 0 until cols) {
                val x = MARGIN + c * colW
                canvas!!.drawText(CellRef.colLetters(c), x + colW / 2, MARGIN + ROW_H - 5f, headerText)
            }
        }

        val firstBodyY = MARGIN + ROW_H
        newPage()
        var y = firstBodyY
        for (r in 0 until rows) {
            if (y + ROW_H > PAGE_H - MARGIN) {
                newPage()
                y = firstBodyY
            }
            for (c in 0 until cols) {
                val cell = sheet.cellOrNull(c, r)
                val x = MARGIN + c * colW
                cell?.style?.fillColor?.let {
                    fill.color = it
                    canvas!!.drawRect(x, y, x + colW, y + ROW_H, fill)
                }
                val display = cell?.let { calc.displayFor(c, r) } ?: ""
                if (display.isNotEmpty()) {
                    text.typeface = when {
                        cell!!.style.bold && cell.style.italic -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
                        cell.style.bold -> Typeface.DEFAULT_BOLD
                        cell.style.italic -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                        else -> Typeface.DEFAULT
                    }
                    text.color = cell.style.textColor ?: Color.BLACK
                    when (cell.style.align) {
                        CellStyle.ALIGN_CENTER -> text.textAlign = Paint.Align.CENTER
                        CellStyle.ALIGN_RIGHT -> text.textAlign = Paint.Align.RIGHT
                        CellStyle.ALIGN_LEFT -> text.textAlign = Paint.Align.LEFT
                        else -> text.textAlign = if (display.toDoubleOrNull() != null) Paint.Align.RIGHT else Paint.Align.LEFT
                    }
                    val tx = when (text.textAlign) {
                        Paint.Align.CENTER -> x + colW / 2
                        Paint.Align.RIGHT -> x + colW - 3f
                        else -> x + 3f
                    }
                    // clip long text into the cell
                    var t = display
                    val maxChars = ((colW / (FONT * 0.55f)).toInt()).coerceAtLeast(1)
                    if (t.length > maxChars) t = t.take(maxChars)
                    canvas!!.drawText(t, tx, y + ROW_H - 5f, text)
                }
                canvas!!.drawRect(x, y, x + colW, y + ROW_H, line)
            }
            y += ROW_H
        }
        page?.let { doc.finishPage(it) }
        doc.writeTo(out)
        doc.close()
        out.flush()
    }

    /** For very wide sheets: chunk columns into groups of [perPage] per PDF page set. */
    private fun writePagedDoc(doc: PdfDocument, sheet: Sheet, calc: Calculator, out: OutputStream, perPage: Int) {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = FONT; color = Color.BLACK }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 0.5f; color = Color.LTGRAY }
        val headerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#006A4E") }
        val headerText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = FONT; color = Color.WHITE; textAlign = Paint.Align.CENTER }

        var pageNo = 0
        var startCol = 0
        while (startCol < sheet.colCount) {
            val cols = minOf(perPage, sheet.colCount - startCol)
            val colW = (PAGE_W - 2 * MARGIN) / cols
            var page: PdfDocument.Page? = null
            var canvas: Canvas? = null
            var y = MARGIN
            for (r in 0 until sheet.rowCount) {
                val hasContent = (startCol until startCol + cols).any { sheet.cellOrNull(it, r)?.raw?.isNotBlank() == true }
                if (!hasContent && r > 0) continue
                if (page == null || y + ROW_H > PAGE_H - MARGIN) {
                    page?.let { doc.finishPage(it) }
                    pageNo++
                    val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create()
                    val p = doc.startPage(info)
                    page = p
                    canvas = p.canvas
                    canvas!!.drawColor(Color.WHITE)
                    canvas!!.drawRect(MARGIN, MARGIN, PAGE_W - MARGIN, MARGIN + ROW_H, headerFill)
                    for (c in 0 until cols) {
                        val x = MARGIN + c * colW
                        canvas!!.drawText(CellRef.colLetters(startCol + c), x + colW / 2, MARGIN + ROW_H - 5f, headerText)
                    }
                    y = MARGIN + ROW_H
                }
                for (c in 0 until cols) {
                    val x = MARGIN + c * colW
                    val display = calc.displayFor(startCol + c, r)
                    if (display.isNotEmpty()) canvas!!.drawText(display.take(24), x + 3f, y + ROW_H - 5f, text)
                    canvas!!.drawRect(x, y, x + colW, y + ROW_H, line)
                }
                y += ROW_H
            }
            page?.let { doc.finishPage(it) }
            startCol += perPage
        }
        doc.writeTo(out)
        doc.close()
        out.flush()
    }
}
