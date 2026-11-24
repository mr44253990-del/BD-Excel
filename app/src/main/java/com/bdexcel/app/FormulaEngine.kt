package com.bdexcel.app

import java.text.DecimalFormat

/**
 * এক্সেল ফরমুলা ইঞ্জিন। SUM, AVERAGE, MIN, MAX, COUNT, IF ইত্যাদি সাপোর্ট করে।
 * সেল রেফারেন্স (A1, B2) এবং রেঞ্জ (A1:A10) উভয়ই সাপোর্ট করে।
 */
class FormulaEngine(private val sheet: Sheet) {

    private val df = DecimalFormat("#.##########")

    /**
     * একটি ফরমুলা স্ট্রিং dá registered evaluation।
     */
    fun evaluate(formula: String): String {
        val f = formula.trim()
        if (f.isEmpty() || !f.startsWith("=")) return f
        val body = f.substring(1).trim().uppercase()
        return try {
            when {
                body.startsWith("SUM(")      -> evalRange(body, ::sumRange)
                body.startsWith("AVERAGE(")  -> evalRange(body, ::avgRange)
                body.startsWith("MIN(")      -> evalRange(body, ::minRange)
                body.startsWith("MAX(")      -> evalRange(body, ::maxRange)
                body.startsWith("COUNT(")    -> evalRange(body, ::countRange)
                body.contains("IF(")         -> evalIf(body)
                body.contains("+")           -> evalBinary(body, { a, b -> a + b })
                body.contains("-")           -> evalBinary(body, { a, b -> a - b })
                body.contains("*")           -> evalBinary(body, { a, b -> a * b })
                body.contains("/")           -> evalBinary(body, { a, b -> if (b == 0.0) "ERROR" else (a / b).toString() })
                else                         -> parseCellOrNumber(body)
            }
        } catch (e: Exception) {
            "ERROR"
        }
    }

    private fun evalRange(body: String, op: (List<Double>) -> Double): String {
        val range = extractRange(body) ?: return "ERROR"
        val nums = range.mapNotNull { cellValueToDouble(it) }
        return if (nums.isEmpty()) "0" else df.format(op(nums))
    }

    private fun evalBinary(body: String, op: (Double, Double) -> Double): String {
        val parts = body.split(Regex("[\\+\\-*/]")).map { it.trim() }
        if (parts.size != 2) return "ERROR"
        val a = cellValueToDouble(parts[0]) ?: return "ERROR"
        val b = cellValueToDouble(parts[1]) ?: return "ERROR"
        return df.format(op(a, b))
    }

    private fun evalIf(body: String): String {
        // সাদা IF(condition,true,false) সাপোর্ট
        val inner = body.substringAfter("IF(").substringBeforeLast(")")
        val parts = splitTopLevel(inner)
        if (parts.size != 3) return "ERROR"
        val cond = evaluateCondition(parts[0].trim())
        return if (cond) parts[1].trim().trimStart('=').let { evaluate("=$it") } else parts[2].trim().trimStart('=').let { evaluate("=$it") }
    }

    private fun evaluateCondition(expr: String): Boolean {
        val m = Regex("(.+?)(=|<>|<|>|<=|>=)(.+)").find(expr) ?: return false
        val a = cellValueToDouble(m.groupValues[1].trim())
        val b = cellValueToDouble(m.groupValues[3].trim())
        if (a == null || b == null) return false
        return when (m.groupValues[2]) {
            "=" -> a == b
            "<>" -> a != b
            "<" -> a < b
            ">" -> a > b
            "<=" -> a <= b
            ">=" -> a >= b
            else -> false
        }
    }

    private fun extractRange(body: String): List<String>? {
        // SUM(A1:A10) -> A1:A10
        val m = Regex("\\(([^)]+)\\)").find(body) ?: return null
        val inside = m.groupValues[1].trim()
        return if (inside.contains(":")) {
            val range = inside.split(":")
            if (range.size != 2) null else expandRange(range[0].trim(), range[1].trim())
        } else listOf(inside)
    }

    private fun expandRange(startRef: String, endRef: String): List<String> {
        val start = CellCoordinate.fromAlpha(startRef)
        val end = CellCoordinate.fromAlpha(endRef)
        val cells = mutableListOf<String>()
        for (r in start.row.coerceAtLeast(1)..end.row) {
            for (c in start.column.coerceAtLeast(1)..end.column) {
                cells.add(CellCoordinate(r, c).toAlpha())
            }
        }
        return cells
    }

    private fun cellValueToDouble(ref: String): Double? {
        val trimmed = ref.trim()
        return try {
            trimmed.toDouble()
        } catch (e: NumberFormatException) {
            val coord = if (Regex("[A-Za-z]+\\d+").matches(trimmed)) CellCoordinate.fromAlpha(trimmed) else null
            val str = coord?.let { sheet.getCell(it.row, it.column).value } ?: trimmed
            str.toDoubleOrNull()
        }
    }

    private fun parseCellOrNumber(body: String): String {
        return cellValueToDouble(body)?.let { df.format(it) } ?: body
    }

    /**
     * উল্টো লেভেলে কোমা স্প্লিট (নেস্টেড ব্রাকেট ভема)। সাদা ইনপ্লিমেন্টেশন।
     */
    private fun splitTopLevel(s: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        val cur = StringBuilder()
        for (c in s) {
            when (c) {
                '(' -> { depth++; cur.append(c) }
                ')' -> { depth--; cur.append(c) }
                ',' -> if (depth == 0) { parts.add(cur.toString()); cur.setLength(0) } else cur.append(c)
                else -> cur.append(c)
            }
        }
        parts.add(cur.toString())
        return parts
    }

    /**
     * পুরো শিটের সব ফরমুলা রিক্যালকুলেট করে।
     */
    fun recalculateAll(): Map<CellCoordinate, String> {
        val results = mutableMapOf<CellCoordinate, String>()
        for (cell in sheet.cellList()) {
            if (cell.formula.isNotEmpty()) {
                results[CellCoordinate(cell.row, cell.column)] = evaluate(cell.formula)
            }
        }
        return results
    }
}
