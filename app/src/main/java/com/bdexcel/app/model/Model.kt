package com.bdexcel.app.model

import org.json.JSONArray
import org.json.JSONObject

data class CellStyle(
    var bold: Boolean = false,
    var italic: Boolean = false,
    var textColor: Int? = null,
    var fillColor: Int? = null,
    var align: Int = ALIGN_AUTO
) {
    fun copyOf(): CellStyle = CellStyle(bold, italic, textColor, fillColor, align)

    companion object {
        const val ALIGN_AUTO = 0
        const val ALIGN_LEFT = 1
        const val ALIGN_CENTER = 2
        const val ALIGN_RIGHT = 3
    }
}

class Cell(
    var raw: String = "",
    var style: CellStyle = CellStyle()
) {
    val isEmpty: Boolean get() = raw.isBlank() && !style.bold && !style.italic && style.textColor == null && style.fillColor == null && style.align == CellStyle.ALIGN_AUTO

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("v", raw)
        if (style.bold) o.put("b", true)
        if (style.italic) o.put("i", true)
        style.textColor?.let { o.put("tc", it) }
        style.fillColor?.let { o.put("fc", it) }
        if (style.align != CellStyle.ALIGN_AUTO) o.put("al", style.align)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Cell {
            val c = Cell(o.optString("v"))
            c.style.bold = o.optBoolean("b")
            c.style.italic = o.optBoolean("i")
            if (o.has("tc")) c.style.textColor = o.getInt("tc")
            if (o.has("fc")) c.style.fillColor = o.getInt("fc")
            c.style.align = o.optInt("al", CellStyle.ALIGN_AUTO)
            return c
        }
    }
}

open class CellRef {
    companion object {
        fun colLetters(c: Int): String {
            var s = ""
            var n = c
            while (n >= 0) {
                s = "${('A' + (n % 26)).toChar()}$s"
                n = n / 26 - 1
            }
            return s
        }

        fun lettersToCol(s: String): Int {
            var n = 0
            for (ch in s.uppercase()) n = n * 26 + (ch - 'A' + 1)
            return n - 1
        }

        fun key(col: Int, row: Int): String = "${colLetters(col)}${row + 1}"

        fun parseKey(key: String): Pair<Int, Int>? {
            val m = Regex("^([A-Za-z]+)([0-9]+)$").find(key.trim()) ?: return null
            return Pair(lettersToCol(m.groupValues[1]), m.groupValues[2].toInt() - 1)
        }
    }
}

class Sheet(var name: String = "Sheet1") {
    val cells = HashMap<String, Cell>()
    var rowCount: Int = 100
    var colCount: Int = 26

    fun cell(col: Int, row: Int): Cell =
        cells.getOrPut(CellRef.key(col, row)) { Cell() }

    fun cellOrNull(col: Int, row: Int): Cell? = cells[CellRef.key(col, row)]

    fun set(col: Int, row: Int, raw: String) {
        if (raw.isBlank()) {
            val k = CellRef.key(col, row)
            val c = cells[k]
            if (c != null && raw.isBlank() && c.raw.isBlank()) {
                if (c.isEmpty) cells.remove(k)
            }
        }
        cell(col, row).raw = raw
        if (rowCount <= row) rowCount = row + 5
        if (colCount <= col) colCount = col + 2
    }

    fun snapshot(): ByteArray {
        val arr = JSONArray()
        for ((k, c) in cells) {
            if (c.isEmpty) continue
            val o = c.toJson()
            o.put("k", k)
            arr.put(o)
        }
        val root = JSONObject()
        root.put("name", name)
        root.put("cells", arr)
        root.put("rows", rowCount)
        root.put("cols", colCount)
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    fun restore(data: ByteArray) {
        cells.clear()
        val root = JSONObject(String(data, Charsets.UTF_8))
        name = root.optString("name", name)
        rowCount = root.optInt("rows", 100)
        colCount = root.optInt("cols", 26)
        val arr = root.optJSONArray("cells") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val key = o.optString("k")
            if (key.isBlank()) continue
            cells[key] = Cell.fromJson(o)
        }
    }

    fun deepCopy(): Sheet {
        val s = Sheet(name)
        s.rowCount = rowCount
        s.colCount = colCount
        for ((k, c) in cells) s.cells[k] = Cell(c.raw, c.style.copyOf())
        return s
    }
}

class Workbook {
    val sheets = ArrayList<Sheet>()
    var current: Int = 0

    fun active(): Sheet = sheets.getOrElse(current) { sheets.firstOrNull() ?: Sheet().also { sheets.add(it) } }

    fun toJson(): ByteArray {
        val arr = JSONArray()
        for (s in sheets) arr.put(JSONObject(String(s.snapshot(), Charsets.UTF_8)))
        val root = JSONObject()
        root.put("sheets", arr)
        root.put("current", current)
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    fun fromJson(data: ByteArray) {
        sheets.clear()
        val root = JSONObject(String(data, Charsets.UTF_8))
        current = root.optInt("current", 0)
        val arr = root.optJSONArray("sheets") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val s = Sheet()
            s.restore(arr.getJSONObject(i).toString().toByteArray(Charsets.UTF_8))
            sheets.add(s)
        }
        if (sheets.isEmpty()) sheets.add(Sheet())
    }
}
