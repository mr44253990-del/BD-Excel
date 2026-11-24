package com.bdexcel.app.io

import android.util.Xml
import com.bdexcel.app.model.Cell
import com.bdexcel.app.model.CellStyle
import com.bdexcel.app.model.CellRef
import com.bdexcel.app.model.Sheet
import com.bdexcel.app.model.Workbook
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object CsvCodec {

    fun write(sheet: Sheet, out: OutputStream) {
        val sb = StringBuilder()
        var maxRow = 0
        var maxCol = 0
        for ((k, c) in sheet.cells) {
            if (c.raw.isBlank()) continue
            val p = CellRef.parseKey(k) ?: continue
            if (p.first > maxCol) maxCol = p.first
            if (p.second > maxRow) maxRow = p.second
        }
        for (r in 0..maxRow) {
            val row = ArrayList<String>()
            for (c in 0..maxCol) {
                val text = sheet.cellOrNull(c, r)?.raw ?: ""
                if (text.contains(',') || text.contains('"') || text.contains('\n')) {
                    row.add("\"" + text.replace("\"", "\"\"") + "\"")
                } else {
                    row.add(text)
                }
            }
            sb.append(row.joinToString(",")).append("\r\n")
        }
        out.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
        out.flush()
    }

    fun read(input: InputStream, sheet: Sheet) {
        sheet.cells.clear()
        val text = input.readBytes().toString(StandardCharsets.UTF_8)
        val rows = parseCsv(text)
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { c, value ->
                if (value.isNotEmpty()) sheet.cell(c, r).raw = value
            }
        }
        val maxRows = rows.size
        val maxCols = rows.maxOfOrNull { it.size } ?: 0
        if (maxRows > 0) sheet.rowCount = maxOf(100, maxRows + 2).coerceAtMost(5000)
        if (maxCols > 0) sheet.colCount = maxOf(26, maxCols + 1).coerceAtMost(254)
    }

    fun parseCsv(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var field = StringBuilder()
        var row = ArrayList<String>()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"'); i++
                    } else inQuotes = false
                } else field.append(ch)
            } else when (ch) {
                '"' -> inQuotes = true
                ',' -> { row.add(field.toString()); field = StringBuilder() }
                '\r' -> {
                    row.add(field.toString()); rows.add(row)
                    row = ArrayList(); field = StringBuilder()
                }
                '\n' -> {
                    row.add(field.toString()); rows.add(row)
                    row = ArrayList(); field = StringBuilder()
                }
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows
    }
}

/** Minimal XLSX reader/writer built on java.util.zip and XmlPullParser — no external dependencies. */
object XlsxCodec {

    private val NAR = Regex("[^0-9.\\-+eE]")

    fun write(workbook: Workbook, out: OutputStream) {
        val styles = StyleRegistry()
        ZipOutputStream(out).use { zip ->
            put(zip, "[Content_Types].xml", contentTypes(workbook).toByteArray(StandardCharsets.UTF_8))
            put(zip, "_rels/.rels", ROOT_RELS.toByteArray(StandardCharsets.UTF_8))
            put(zip, "xl/workbook.xml", workbookXml(workbook).toByteArray(StandardCharsets.UTF_8))
            put(zip, "xl/_rels/workbook.xml.rels", workbookRels(workbook).toByteArray(StandardCharsets.UTF_8))
            workbook.sheets.forEachIndexed { i, sheet ->
                put(zip, "xl/worksheets/sheet${i + 1}.xml", sheetXml(sheet, styles).toByteArray(StandardCharsets.UTF_8))
            }
            put(zip, "xl/styles.xml", styles.toXml().toByteArray(StandardCharsets.UTF_8))
            put(zip, "docProps/core.xml", CORE_PROPS.toByteArray(StandardCharsets.UTF_8))
        }
        out.flush()
    }

    private fun put(zip: ZipOutputStream, name: String, data: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(data)
        zip.closeEntry()
    }

    private fun contentTypes(wb: Workbook): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        sb.append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>""")
        sb.append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        sb.append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        sb.append("""<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>""")
        wb.sheets.forEachIndexed { i, _ ->
            sb.append("""<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        sb.append("</Types>")
        return sb.toString()
    }

    private fun workbookXml(wb: Workbook): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        wb.sheets.forEachIndexed { i, s ->
            val name = s.name.ifBlank { "Sheet${i + 1}" }.replace(Regex("[\\\\/*?:\\[\\]<>&\"']"), "_")
            sb.append("""<sheet name="${esc(name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
        }
        sb.append("</sheets></workbook>")
        return sb.toString()
    }

    private fun workbookRels(wb: Workbook): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        sb.append("""<Relationship Id="rIdSty" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        wb.sheets.forEachIndexed { i, _ ->
            sb.append("""<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${i + 1}.xml"/>""")
        }
        sb.append("</Relationships>")
        return sb.toString()
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun isNumericText(raw: String): Boolean =
        raw.trim().toDoubleOrNull() != null && !raw.contains(NAR)

    private fun numericText(raw: String): String {
        val n = raw.trim().toDouble()
        return if (n == n.toLong().toDouble()) n.toLong().toString() else n.toString()
    }

    private fun sheetXml(sheet: Sheet, styles: StyleRegistry): String {
        val entries = ArrayList<Pair<Pair<Int, Int>, Cell>>()
        for ((k, cell) in sheet.cells) {
            if (cell.raw.isBlank()) continue
            val p = CellRef.parseKey(k) ?: continue
            entries.add(Pair(p, cell))
        }
        entries.sortWith(compareBy({ it.first.second }, { it.first.first }))
        var maxRow = -1
        var maxCol = -1
        for (e in entries) {
            if (e.first.second > maxRow) maxRow = e.first.second
            if (e.first.first > maxCol) maxCol = e.first.first
        }
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        val dimEnd = if (maxRow >= 0 && maxCol >= 0) CellRef.key(maxCol, maxRow) else "A1"
        sb.append("""<dimension ref="A1:$dimEnd"/>""")
        sb.append("""<sheetData>""")
        for (e in entries) {
            val c = e.first.first
            val r = e.first.second
            val cell = e.second
            val s = styles.idFor(cell)
            val ref = CellRef.key(c, r)
            val raw = cell.raw
            sb.append("""<row r="${r + 1}">""")
            if (raw.startsWith("=")) {
                val value = sheetCellDisplay(sheet, c, r)
                sb.append("""<c r="$ref" s="$s"><f>${esc(raw.removePrefix("="))}</f><v>${esc(value)}</v></c>""")
            } else if (isNumericText(raw)) {
                sb.append("""<c r="$ref" s="$s"><v>${numericText(raw)}</v></c>""")
            } else {
                sb.append("""<c r="$ref" s="$s" t="inlineStr"><is><t>${esc(raw)}</t></is></c>""")
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun sheetCellDisplay(sheet: Sheet, c: Int, r: Int): String {
        return try {
            com.bdexcel.app.engine.Calculator(sheet).displayFor(c, r)
        } catch (e: Exception) {
            ""
        }
    }

    private val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
</Relationships>"""

    private val CORE_PROPS: String
        get() {
            val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(Date())
            return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
<dc:creator>BD Excel</dc:creator>
<cp:lastModifiedBy>BD Excel</cp:lastModifiedBy>
<dcterms:created xsi:type="dcterms:W3CDTF">$now</dcterms:created>
<dcterms:modified xsi:type="dcterms:W3CDTF">$now</dcterms:modified>
</cp:coreProperties>"""
        }

    /** Builds styles.xml covering bold/italic/color/fill/alignment variants used in the workbook. */
    private class StyleRegistry {
        private val map = HashMap<String, Int>()
        private val list = ArrayList<CellStyle>()

        fun idFor(cell: Cell): Int {
            val key = with(cell.style) { "$bold|$italic|$textColor|$fillColor|$align" }
            return map.getOrPut(key) {
                list.add(cell.style.copyOf())
                list.size // xf index 0 is the default style, ours start at 1
            }
        }

        fun toXml(): String {
            val fillOrder = ArrayList<CellStyle>()
            for (st in list) if (st.fillColor != null) fillOrder.add(st)
            val sb = StringBuilder()
            sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            sb.append("""<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
            sb.append("""<fonts count="${list.size + 1}">""")
            sb.append("""<font><sz val="11"/><color theme="1"/><name val="Calibri"/><family val="2"/></font>""")
            for (st in list) {
                sb.append("<font>")
                if (st.bold) sb.append("<b/>")
                if (st.italic) sb.append("<i/>")
                sb.append("""<sz val="11"/>""")
                if (st.textColor != null) sb.append("""<color rgb="${argb(st.textColor)}"/>""") else sb.append("""<color theme="1"/>""")
                sb.append("""<name val="Calibri"/><family val="2"/></font>""")
            }
            sb.append("</fonts>")
            sb.append("""<fills count="${fillOrder.size + 2}">""")
            sb.append("""<fill><patternFill patternType="none"/></fill>""")
            sb.append("""<fill><patternFill patternType="gray125"/></fill>""")
            for (st in fillOrder) {
                val color = st.fillColor
                if (color != null) sb.append("""<fill><patternFill patternType="solid"><fgColor rgb="${argb(color)}"/><bgColor indexed="64"/></patternFill></fill>""")
            }
            sb.append("</fills>")
            sb.append("""<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""")
            sb.append("""<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""")
            sb.append("""<cellXfs count="${list.size + 1}">""")
            sb.append("""<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""")
            for (st in list) {
                val fontId = list.indexOf(st) + 1
                val fillId = st.fillColor?.let { fillOrder.indexOf(st) + 2 } ?: 0
                val alignAttr = when (st.align) {
                    CellStyle.ALIGN_CENTER -> """ horizontal="center""""
                    CellStyle.ALIGN_RIGHT -> """ horizontal="right""""
                    CellStyle.ALIGN_LEFT -> """ horizontal="left""""
                    else -> ""
                }
                sb.append("""<xf numFmtId="0" fontId="$fontId" fillId="$fillId" borderId="0" xfId="0" applyFont="1"${if (st.fillColor != null) " applyFill=\"1\"" else ""} applyAlignment="1">""")
                sb.append("""<alignment$alignAttr vertical="center"/></xf>""")
            }
            sb.append("</cellXfs>")
            sb.append("""<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""")
            sb.append("</styleSheet>")
            return sb.toString()
        }

        private fun argb(color: Int): String {
            val a = ((color ushr 24) and 0xFF)
            val alpha = if (a == 0) "FF" else "%02X".format(a)
            return "#%s%06X".format(alpha, color and 0xFFFFFF)
        }
    }

    // ------------------------------ reading ------------------------------

    class ReadStyles {
        val fontBold = HashMap<Int, Boolean>()
        val fontItalic = HashMap<Int, Boolean>()
        val fontColor = HashMap<Int, Int?>()
        val fillColor = HashMap<Int, Int?>()
        val xfFont = ArrayList<Int>()
        val xfFill = ArrayList<Int>()
    }

    fun read(input: InputStream, workbook: Workbook) {
        workbook.sheets.clear()
        var shared: List<String> = emptyList()
        var styles = ReadStyles()
        val sheetsData = LinkedHashMap<String, ByteArray>()
        var workbookRels = ""
        var workbookXmlData: ByteArray? = null

        ZipInputStream(input).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                val data = zip.readBytes()
                when {
                    name == "xl/sharedStrings.xml" -> shared = parseSharedStrings(data)
                    name == "xl/styles.xml" -> styles = parseStyles(data)
                    name.startsWith("xl/worksheets/") && name.endsWith(".xml") -> sheetsData[name] = data
                    name == "xl/_rels/workbook.xml.rels" -> workbookRels = data.toString(StandardCharsets.UTF_8)
                    name == "xl/workbook.xml" -> workbookXmlData = data
                }
                entry = zip.nextEntry
            }
        }

        val relsMap = HashMap<String, String>()
        for (m in Regex("""Id="([^"]+)"[^>]*Target="([^"]+)"""").findAll(workbookRels)) {
            relsMap[m.groupValues[1]] = m.groupValues[2]
        }

        val wbText = workbookXmlData?.toString(StandardCharsets.UTF_8) ?: ""
        val ordered = ArrayList<Pair<String, ByteArray>>()
        for (m in Regex("""<sheet[^>]*name="((?:[^"]|&quot;)*)"[^>]*r:id="([^"]*)"""").findAll(wbText)) {
            val name = m.groupValues[1].replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&apos;", "'")
            val target = relsMap[m.groupValues[2]] ?: continue
            val candidates = listOf("xl/$target", target.removePrefix("/"), "xl/worksheets/${target.substringAfterLast('/')}")
            val data = sheetsData.entries.firstOrNull { e -> candidates.any { it == e.key } }?.value
            if (data != null) ordered.add(Pair(name, data))
        }
        if (ordered.isEmpty()) {
            for ((name, data) in sheetsData) ordered.add(Pair("Sheet${ordered.size + 1}", data))
        }

        for ((name, data) in ordered) {
            val sheet = Sheet(name.ifBlank { "Sheet${workbook.sheets.size + 1}" })
            parseSheet(data, shared, styles, sheet)
            workbook.sheets.add(sheet)
        }
        if (workbook.sheets.isEmpty()) workbook.sheets.add(Sheet())
        workbook.current = 0
    }

    private fun parseSharedStrings(data: ByteArray): List<String> {
        val out = ArrayList<String>()
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(data.inputStream(), null)
            var inSi = false
            var inT = false
            var sb = StringBuilder()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "si" -> { inSi = true; sb = StringBuilder() }
                        "t" -> if (inSi) inT = true
                    }
                    XmlPullParser.TEXT -> if (inT) sb.append(parser.text)
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "t" -> inT = false
                        "si" -> { out.add(sb.toString()); inSi = false }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun parseStyles(data: ByteArray): ReadStyles {
        val s = ReadStyles()
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(data.inputStream(), null)
            var fontId = -1
            var fillId = -1
            var lastRgb: Int? = null
            var inFont = false
            var inFill = false
            var solidFill = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "font" -> { fontId++; inFont = true; lastRgb = null }
                        "fill" -> { fillId++; inFill = true; lastRgb = null; solidFill = false }
                        "patternFill" -> solidFill = parser.getAttributeValue(null, "patternType") == "solid"
                        "b" -> if (inFont) s.fontBold[fontId] = parser.getAttributeValue(null, "val") != "0"
                        "i" -> if (inFont) s.fontItalic[fontId] = parser.getAttributeValue(null, "val") != "0"
                        "color", "fgColor" -> {
                            val rgb = parser.getAttributeValue(null, "rgb")
                            lastRgb = parseRgb(rgb)
                        }
                        "xf" -> {
                            s.xfFont.add(parser.getAttributeValue(null, "fontId")?.toIntOrNull() ?: 0)
                            s.xfFill.add(parser.getAttributeValue(null, "fillId")?.toIntOrNull() ?: 0)
                        }
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "font" -> { s.fontColor[fontId] = lastRgb; inFont = false }
                        "fill" -> { if (solidFill) s.fillColor[fillId] = lastRgb; inFill = false }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
        }
        return s
    }

    private fun parseRgb(rgb: String?): Int? {
        if (rgb == null || rgb.length != 8) return null
        val v = rgb.toLongOrNull(16)?.toInt() ?: return null
        val a0 = ((v ushr 24) and 0xFF)
        val a = if (a0 == 0) 0xFF else a0
        return (a shl 24) or (v and 0xFFFFFF)
    }

    private fun parseSheet(data: ByteArray, shared: List<String>, styles: ReadStyles, sheet: Sheet) {
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(data.inputStream(), null)
            var cellType = ""
            var cellStyleId = -1
            var cellRef = ""
            var formula: String? = null
            var valueText = StringBuilder()
            var inCell = false
            var inValue = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "c" -> {
                            inCell = true
                            cellType = parser.getAttributeValue(null, "t") ?: ""
                            cellStyleId = parser.getAttributeValue(null, "s")?.toIntOrNull() ?: -1
                            cellRef = parser.getAttributeValue(null, "r") ?: ""
                            formula = null
                            valueText = StringBuilder()
                        }
                        "f" -> if (inCell) inValue = true
                        "v", "t" -> if (inCell) inValue = true
                    }
                    XmlPullParser.TEXT -> if (inCell && inValue) valueText.append(parser.text)
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "f" -> if (inCell) { formula = valueText.toString(); valueText = StringBuilder(); inValue = false }
                        "v", "t" -> if (inCell) inValue = false
                        "c" -> {
                            if (inCell) applyCell(sheet, cellRef, cellType, cellStyleId, formula, valueText.toString(), shared, styles)
                            inCell = false
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
        }
    }

    private fun applyCell(sheet: Sheet, ref: String, type: String, styleId: Int, formula: String?, value: String, shared: List<String>, styles: ReadStyles) {
        val pos = CellRef.parseKey(ref) ?: return
        val (c, r) = pos
        val cell = sheet.cell(c, r)
        if (formula != null) {
            cell.raw = "=" + formula.trim()
        } else {
            cell.raw = when (type) {
                "s" -> shared.getOrNull(value.trim().toIntOrNull() ?: -1) ?: ""
                "inlineStr", "str" -> value
                "b" -> if (value.trim() == "1") "TRUE" else "FALSE"
                else -> value
            }
        }
        if (styleId in 0 until styles.xfFont.size) {
            val f = styles.xfFont[styleId]
            cell.style.bold = styles.fontBold[f] ?: false
            cell.style.italic = styles.fontItalic[f] ?: false
            cell.style.textColor = styles.fontColor[f]
            val fl = styles.xfFill.getOrNull(styleId) ?: 0
            cell.style.fillColor = styles.fillColor[fl]
        }
        if (r + 2 > sheet.rowCount) sheet.rowCount = minOf(r + 2, 5000)
        if (c + 1 > sheet.colCount) sheet.colCount = minOf(c + 2, 254)
    }

    fun mimeTypeFor(name: String): String = when {
        name.endsWith(".xlsx", true) -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        name.endsWith(".csv", true) -> "text/csv"
        name.endsWith(".json", true) -> "application/json"
        else -> "application/octet-stream"
    }

    fun timestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
}
