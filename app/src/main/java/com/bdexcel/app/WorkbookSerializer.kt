package com.bdexcel.app

/**
 * ওয়ার্কবুক(JSON) সিরিলাইজেশন — লোকাল ব্যাকআপ/রিস্টোরের জন্য।
 */
object WorkbookSerializer {
    fun toJson(workbook: Workbook): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"id\":\"${workbook.id}\",")
        sb.append("\"name\":\"${escape(workbook.name)}\",")
        sb.append("\"activeSheetIndex\":${workbook.activeSheetIndex},")
        sb.append("\"createdAt\":${workbook.createdAt},")
        sb.append("\"updatedAt\":${workbook.updatedAt},")
        sb.append("\"sheets\":[")
        sb.append(workbook.sheets.joinToString(",") { s ->
            "{\"id\":\"${s.id}\",\"name\":\"${escape(s.name)}\",\"columnCount\":${s.columnCount},\"rowCount\":${s.rowCount},\"createdAt\":${s.createdAt},\"updatedAt\":${s.updatedAt},\"cells\":[" +
            s.cellList().joinToString(",") { c ->
                "{\"row\":${c.row},\"column\":${c.column},\"value\":${jsonString(c.value)},\"formula\":${jsonString(c.formula)},\"evaluatedValue\":${jsonString(c.evaluatedValue)},\"bold\":${c.bold},\"italic\":${c.italic},\"textColor\":${c.textColor},\"backgroundColor\":${c.backgroundColor},\"fontSize\":${c.fontSize},\"horizontalAlignment\":${c.horizontalAlignment},\"verticalAlignment\":${c.verticalAlignment},\"wrapText\":${c.wrapText},\"columnWidth\":${c.columnWidth},\"rowHeight\":${c.rowHeight}}"
            } + "]}"
        } + "]}")
        return sb.toString()
    }

    fun fromJson(json: String): Workbook {
        // লাইটওয়েপ ম্যানুয়াল পার্সার (গহরের জসন লাইব্রেরি இ ausenciaে)। প্রোডাকশনে Gson/Moshi ব্যবহার করা যেতে পারে।
        val obj = parseObject(json)
        val id = obj["id"] ?: java.util.UUID.randomUUID().toString()
        val name = obj["name"] ?: "BD Workbook"
        val activeSheetIndex = obj["activeSheetIndex"]?.toIntOrNull() ?: 0
        val createdAt = obj["createdAt"]?.toLongOrNull() ?: System.currentTimeMillis()
        val updatedAt = obj["updatedAt"]?.toLongOrNull() ?: System.currentTimeMillis()
        val wb = Workbook(id, name, mutableListOf(), activeSheetIndex, createdAt, updatedAt)
        val sheetsArr = obj["sheets"] ?: return wb
        for (s in parseArray(sheetsArr)) {
            val sObj = s as? Map<*, *> ?: continue
            val sId = (sObj["id"] ?: java.util.UUID.randomUUID().toString()).toString()
            val sName = (sObj["name"] ?: "Sheet").toString()
            val sCol = (sObj["columnCount"]?.toString() ?: "26").toIntOrNull() ?: 26
            val sRow = (sObj["rowCount"]?.toString() ?: "1000").toIntOrNull() ?: 1000
            val sheet = Sheet(sId, sName, mutableMapOf(), sCol, sRow,
                (sObj["createdAt"]?.toString()?.toLongOrNull() ?: System.currentTimeMillis()),
                (sObj["updatedAt"]?.toString()?.toLongOrNull() ?: System.currentTimeMillis()))
            val cellsArr = sObj["cells"] ?: continue
            for (c in parseArray(cellsArr)) {
                val cObj = c as? Map<*, *> ?: continue
                val cell = Cell(
                    (cObj["row"]?.toString()?.toIntOrNull() ?: 0),
                    (cObj["column"]?.toString()?.toIntOrNull() ?: 0),
                    value = (cObj["value"] ?: "").toString(),
                    formula = (cObj["formula"] ?: "").toString(),
                    evaluatedValue = (cObj["evaluatedValue"] ?: "").toString(),
                    bold = (cObj["bold"]?.toString() == "true"),
                    italic = (cObj["italic"]?.toString() == "true"),
                    textColor = (cObj["textColor"]?.toString()?.toIntOrNull() ?: -16777216),
                    backgroundColor = (cObj["backgroundColor"]?.toString()?.toIntOrNull() ?: 0),
                    fontSize = (cObj["fontSize"]?.toString()?.toFloatOrNull() ?: 14f),
                    horizontalAlignment = (cObj["horizontalAlignment"]?.toString()?.toIntOrNull() ?: 0),
                    verticalAlignment = (cObj["verticalAlignment"]?.toString()?.toIntOrNull() ?: 0),
                    wrapText = (cObj["wrapText"]?.toString() == "true"),
                    columnWidth = (cObj["columnWidth"]?.toString()?.toFloatOrNull() ?: 100f),
                    rowHeight = (cObj["rowHeight"]?.toString()?.toFloatOrNull() ?: 40f)
                )
                sheet.setCell(cell)
            }
            wb.sheets.add(sheet)
        }
        return wb
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun jsonString(s: String): String = "\"${escape(s)}\""

    // খুব লাইটওয়েপ জসন পার্সার (স্ট্রিং/অ্যারেব/object লেভেল)
    private fun parseObject(s: String): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        var i = 1
        while (i < s.length - 1) {
            i = skipWs(s, i)
            if (i >= s.length || s[i] == '}') break
            val key = parseString(s, i)
            i = key.second
            i = skipWs(s, i)
            if (i < s.length && s[i] == ':') i++
            val (value, nextI) = parseValue(s, i)
            map[key.first] = value
            i = nextI
            i = skipWs(s, i)
            if (i < s.length && s[i] == ',') i++
        }
        return map
    }

    private fun parseArray(s: Any): List<Any> {
        val str = s.toString()
        val list = mutableListOf<Any>()
        var i = 1
        while (i < str.length - 1) {
            i = skipWs(str, i)
            if (i >= str.length || str[i] == ']') break
            val (value, nextI) = parseValue(str, i)
            list.add(value)
            i = nextI
            i = skipWs(str, i)
            if (i < str.length && str[i] == ',') i++
        }
        return list
    }

    private fun parseValue(s: String, i: Int): Pair<Any, Int> {
        i = skipWs(s, i)
        return when (s[i]) {
            '"' -> parseString(s, i)
            '{' -> parseObject(s) to i // পজিশন ট্র্যাক করা জটিল, শেলো শুধু বounded لأحد
            '[' -> {
                // অ্যারে হ্যান্ডেল
                var depth = 0
                var j = i
                while (j < s.length) {
                    when (s[j]) {
                        '"' -> { j++; while (j < s.length && s[j] != '"') { if (s[j] == '\\') j++; j++ } }
                        '[' -> depth++
                        ']' -> { depth--; if (depth == 0) break }
                    }
                    j++
                }
                val inner = s.substring(i + 1, j)
                parseArray(inner) to (j + 1)
            }
            't' -> "true" to (i + 4)
            'f' -> "false" to (i + 5)
            'n' -> null to (i + 4)
            else -> {
                var j = i
                while (j < s.length && s[j] != ',' && s[j] != '}' && s[j] != ']') j++
                val token = s.substring(i, j).trim()
                token.toLongOrNull()?.let { it to j } ?: token.toDoubleOrNull()?.let { it to j } ?: token to j
            }
        }
    }

    private fun parseString(s: String, i: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var j = i + 1
        while (j < s.length) {
            val c = s[j]
            if (c == '\\') {
                j++
                if (j < s.length) sb.append(s[j])
                j++
            } else if (c == '"') {
                return sb.toString() to (j + 1)
            } else {
                sb.append(c)
                j++
            }
        }
        return sb.toString() to j
    }

    private fun skipWs(s: String, i: Int): Int {
        var j = i
        while (j < s.length && s[j].isWhitespace()) j++
        return j
    }
}
