package com.bdexcel.app.engine

import com.bdexcel.app.model.CellRef
import com.bdexcel.app.model.Sheet
import java.text.SimpleDateFormat
import java.util.Date

sealed class Value {
    data class Num(val d: Double) : Value()
    data class Str(val s: String) : Value()
    data class Err(val msg: String) : Value()
    object Empty : Value()
}

class EvalException(message: String) : Exception(message)

/** An argument inside a function call: either a single value or a range of values. */
private class Arg {
    var single: Value = Value.Empty
    var range: List<Value>? = null

    fun flatten(): List<Value> = range ?: listOf(single)
}

class Calculator(private val sheet: Sheet) {

    private val cache = HashMap<String, Value>()
    private val visiting = HashSet<String>()

    fun formatNumber(d: Double): String {
        if (d == d.toLong().toDouble() && Math.abs(d) < 1e15) return d.toLong().toString()
        val r = Math.round(d * 1e8) / 1e8
        return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
    }

    fun displayFor(col: Int, row: Int): String {
        val raw = sheet.cellOrNull(col, row)?.raw ?: return ""
        if (raw.isBlank()) return ""
        if (!raw.startsWith("=")) return raw
        return when (val v = valueOf(col, row)) {
            is Value.Num -> formatNumber(v.d)
            is Value.Str -> v.s
            is Value.Empty -> ""
            is Value.Err -> "#ERROR: ${v.msg}"
        }
    }

    fun valueOf(col: Int, row: Int): Value {
        val key = CellRef.key(col, row)
        cache[key]?.let { return it }
        val raw = sheet.cellOrNull(col, row)?.raw ?: return Value.Empty
        if (raw.isBlank()) return Value.Empty
        if (!raw.startsWith("=")) {
            val trimmed = raw.trim()
            val d = trimmed.toDoubleOrNull()
            return if (d != null) Value.Num(d) else Value.Str(raw)
        }
        if (key in visiting) return Value.Err("circular reference")
        visiting.add(key)
        val v = try {
            val p = Parser(raw.substring(1), this)
            val result = p.parseExpression()
            if (!p.isAtEnd()) Value.Err("unexpected input")
            else result
        } catch (e: EvalException) {
            Value.Err(e.message ?: "error")
        } catch (e: Exception) {
            Value.Err(e.message ?: "error")
        }
        visiting.remove(key)
        if (v is Value.Num || v is Value.Str) cache[key] = v
        return v
    }

    fun invalidate() {
        cache.clear()
    }
}

/**
 * Recursive-descent grammar:
 *   expr   := concat (('=' | '<>' | '<' | '<=' | '>' | '>=') concat)?
 *   concat := add ('&' add)*
 *   add    := mul (('+' | '-') mul)*
 *   mul    := pow (('*' | '/') pow)*
 *   pow    := unary ('^' pow)?
 *   unary  := ('-'|'+') unary | atom
 *   atom   := number | string | '(' expr ')' | rangeOrCell | func '(' args ')'
 */
class Parser(private val src: String, private val calc: Calculator) {

    companion object {
        const val MAX_ITEMS = 65536
        private val FUNCS = setOf(
            "SUM", "AVERAGE", "AVG", "MIN", "MAX", "COUNT", "COUNTA", "IF",
            "IFERROR", "ROUND", "ABS", "SQRT", "POWER", "MOD", "INT", "PRODUCT",
            "CONCAT", "CONCATENATE", "LEN", "LEFT", "RIGHT", "MID", "UPPER",
            "LOWER", "TRIM", "TODAY", "NOW", "PI", "MEDIAN", "STDEV", "RAND",
            "AND", "OR", "NOT"
        )
        private val CELL_RE = Regex("^([A-Za-z]+)([0-9]+)$")
    }

    private var pos = 0
    private var items = 0

    private fun skipSpace() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun peek(): Char? = if (pos < src.length) src[pos] else null

    fun isAtEnd(): Boolean {
        skipSpace()
        return pos >= src.length
    }

    private fun accept(ch: Char): Boolean {
        skipSpace()
        if (pos < src.length && src[pos] == ch) {
            pos++
            return true
        }
        return false
    }

    private fun expect(ch: Char) {
        if (!accept(ch)) throw EvalException("expected '$ch'")
    }

    private fun guard() {
        if (++items > MAX_ITEMS) throw EvalException("formula too large")
    }

    fun parseExpression(): Value {
        guard()
        val left = parseConcat()
        skipSpace()
        val op: String = when {
            pos + 1 < src.length -> {
                val two = src.substring(pos, pos + 2)
                if (two == ">=" || two == "<=" || two == "<>") two
                else src[pos].toString()
            }
            pos < src.length -> src[pos].toString()
            else -> return left
        }
        val isCmp = op == "=" || op == "<" || op == ">" || op == ">=" || op == "<=" || op == "<>"
        if (!isCmp) return left
        pos += op.length
        val right = parseConcat()
        return compareValues(left, right, op)
    }

    private fun compareValues(left: Value, right: Value, op: String): Value {
        val res: Boolean = when (op) {
            "=" -> equalsValue(left, right)
            "<>" -> !equalsValue(left, right)
            else -> {
                val a = toNum(left)
                val b = toNum(right)
                when (op) {
                    "<" -> a < b; ">" -> a > b; "<=" -> a <= b; else -> a >= b
                }
            }
        }
        return numIf(res)
    }

    private fun equalsValue(a: Value, b: Value): Boolean {
        if (a is Value.Num && b is Value.Num) return a.d == b.d
        return toStr(a) == toStr(b)
    }

    private fun parseConcat(): Value {
        var acc = parseAdd()
        while (true) {
            skipSpace()
            if (peek() == '&') {
                pos++
                val r = parseAdd()
                acc = Value.Str(toStr(acc) + toStr(r))
            } else return acc
        }
    }

    private fun parseAdd(): Value {
        var acc = parseMul()
        while (true) {
            skipSpace()
            val c = peek()
            if (c == '+' || c == '-') {
                pos++
                guard()
                val r = parseMul()
                val a = toNum(acc); val b = toNum(r)
                acc = Value.Num(if (c == '+') a + b else a - b)
            } else return acc
        }
    }

    private fun parseMul(): Value {
        var acc = parsePow()
        while (true) {
            skipSpace()
            val c = peek()
            if (c == '*' || c == '/') {
                pos++
                guard()
                val r = parsePow()
                val a = toNum(acc); val b = toNum(r)
                acc = Value.Num(if (c == '*') a * b else a / b)
            } else return acc
        }
    }

    private fun parsePow(): Value {
        val base = parseUnary()
        skipSpace()
        return if (peek() == '^') {
            pos++
            val exp = parsePow()
            Value.Num(Math.pow(toNum(base), toNum(exp)))
        } else base
    }

    private fun parseUnary(): Value {
        skipSpace()
        val c = peek()
        if (c == '-' || c == '+') {
            pos++
            val v = parseUnary()
            return if (c == '-') Value.Num(-toNum(v)) else v
        }
        return parseAtom()
    }

    private fun parseAtom(): Value {
        skipSpace()
        val c = peek() ?: return Value.Empty

        if (c == '(') {
            pos++
            val v = parseExpression()
            expect(')')
            return v
        }

        if (c == '"') {
            pos++
            val sb = StringBuilder()
            while (pos < src.length && src[pos] != '"') sb.append(src[pos++])
            if (pos < src.length) pos++
            return Value.Str(sb.toString())
        }

        if (c.isDigit() || (c == '.' && pos + 1 < src.length && src[pos + 1].isDigit())) {
            val sb = StringBuilder()
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) sb.append(src[pos++])
            return Value.Num(sb.toString().toDoubleOrNull() ?: throw EvalException("bad number"))
        }

        if (c.isLetter() || c == '$' || c == '_') {
            // function call?
            val after = readWordAt(pos)
            if (after != null) {
                val (word, end) = after
                var p = end
                while (p < src.length && src[p].isWhitespace()) p++
                if (p < src.length && src[p] == '(' && word.uppercase() in FUNCS) return parseFunction(word.uppercase())
                // range?
                var p2 = p
                if (p2 < src.length && src[p2] == ':') {
                    p2++
                    while (p2 < src.length && src[p2].isWhitespace()) p2++
                    val second = readWordAt(p2)
                    if (second != null && parseRef(word) != null && parseRef(second.first) != null) {
                        val values = rangeValues(word, second.first)
                        pos = second.second
                        return numValue(values)
                    }
                }
                // plain cell reference
                if (parseRef(word) == null) throw EvalException("unknown name '$word'")
                pos = end
                return parseRef(word)!!
            }
        }

        throw EvalException("unexpected character '$c'")
    }

    /** Reads an identifier (letters/digits/underscore/$) starting at [start]; returns word + end index. */
    private fun readWordAt(start: Int): Pair<String, Int>? {
        var p = start
        if (p < src.length && src[p] == '$') p++
        if (p >= src.length || !src[p].isLetter()) return null
        while (p < src.length && (src[p].isLetterOrDigit() || src[p] == '_' || src[p] == '$')) p++
        return Pair(src.substring(start, p), p)
    }

    private data class Ref(val col: Int, val row: Int)

    private fun parseRefParts(word: String): Ref? {
        val w = word.replace("$", "")
        val m = CELL_RE.find(w) ?: return null
        val col = CellRef.lettersToCol(m.groupValues[1].uppercase())
        val row = m.groupValues[2].toInt() - 1
        if (row < 0) return null
        return Ref(col, row)
    }

    private fun parseRef(word: String): Ref? = parseRefParts(word)

    private fun rangeValues(a: String, b: String): List<Value> {
        val ra = parseRefParts(a) ?: throw EvalException("bad range '$a'")
        val rb = parseRefParts(b) ?: throw EvalException("bad range '$b'")
        val out = ArrayList<Value>()
        val r1 = minOf(ra.row, rb.row); val r2 = maxOf(ra.row, rb.row)
        val c1 = minOf(ra.col, rb.col); val c2 = maxOf(ra.col, rb.col)
        for (r in r1..r2) {
            for (c in c1..c2) {
                guard()
                out.add(calc.valueOf(c, r))
            }
        }
        return out
    }

    private fun numValue(values: List<Value>): Value {
        val nums = values.filterIsInstance<Value.Num>()
        return if (nums.isEmpty()) Value.Num(0.0) else Value.Num(nums.sumOf { it.d })
    }

    private fun parseFunction(name: String): Value {
        expect('(')
        val args = ArrayList<Arg>()
        skipSpace()
        if (!accept(')')) {
            while (true) {
                guard()
                args.add(parseArg())
                if (accept(',')) continue
                expect(')')
                break
            }
        }
        return applyFunction(name, args)
    }

    /** One function argument — a single value or a range. */
    private fun parseArg(): Arg {
        skipSpace()
        val arg = Arg()
        val word = readWordAt(pos)
        if (word != null) {
            var p = word.second
            while (p < src.length && src[p].isWhitespace()) p++
            // skip a nested function name so its '(' isn't mistaken for a range's colon case
            if (p < src.length && src[p] == ':') {
                p++
                while (p < src.length && src[p].isWhitespace()) p++
                val second = readWordAt(p)
                if (second != null && parseRefParts(word.first) != null && parseRefParts(second.first) != null) {
                    arg.range = rangeValues(word.first, second.first)
                    pos = second.second
                    return arg
                }
            }
        }
        arg.single = parseExpression()
        return arg
    }

    private fun flatten(args: List<Arg>): List<Value> {
        val out = ArrayList<Value>()
        for (a in args) out.addAll(a.flatten())
        return out
    }

    private fun argNums(args: List<Arg>): List<Double> {
        val out = ArrayList<Double>()
        for (a in args) {
            for (v in a.flatten()) {
                if (v is Value.Num) out.add(v.d)
            }
        }
        return out
    }

    private fun applyFunction(name: String, args: List<Arg>): Value {
        val flat = flatten(args)
        val nums = argNums(args)
        fun first(): Value = if (args.isNotEmpty()) args[0].single else Value.Empty
        fun second(): Value = args.getOrNull(1)?.single ?: Value.Empty
        fun third(): Value = args.getOrNull(2)?.single ?: Value.Empty
        return when (name) {
            "SUM" -> Value.Num(nums.sum())
            "PRODUCT" -> Value.Num(if (nums.isEmpty()) 0.0 else nums.reduce { a, b -> a * b })
            "AVERAGE", "AVG" -> if (nums.isEmpty()) Value.Err("no numeric values") else Value.Num(nums.average())
            "MIN" -> if (nums.isEmpty()) Value.Num(0.0) else Value.Num(nums.min())
            "MAX" -> if (nums.isEmpty()) Value.Num(0.0) else Value.Num(nums.max())
            "COUNT" -> Value.Num(nums.size.toDouble())
            "COUNTA" -> Value.Num(flat.count { it !is Value.Empty && toStr(it).isNotEmpty() }.toDouble())
            "MEDIAN" -> {
                val s = nums.sorted()
                if (s.isEmpty()) Value.Err("empty")
                else Value.Num(if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2)
            }
            "STDEV" -> {
                if (nums.size < 2) Value.Err("sample too small")
                else {
                    val m = nums.average()
                    Value.Num(Math.sqrt(nums.sumOf { (it - m) * (it - m) } / (nums.size - 1)))
                }
            }
            "IF" -> {
                if (args.size < 2) Value.Err("IF needs at least 2 arguments")
                else if (truthy(args[0].single)) second() else if (args.size >= 3) third() else Value.Empty
            }
            "IFERROR" -> {
                val a = if (args.isEmpty()) Value.Empty else args[0].single
                if (a is Value.Err) second() else a
            }
            "ROUND" -> {
                val d = if (args.isEmpty()) 0.0 else toNum(first())
                val digits = if (args.size > 1) toNum(second()).toInt() else 0
                val f = Math.pow(10.0, digits.toDouble())
                Value.Num(Math.round(d * f) / f)
            }
            "ABS" -> Value.Num(Math.abs(toNum(first())))
            "SQRT" -> Value.Num(Math.sqrt(toNum(first())))
            "INT" -> Value.Num(Math.floor(toNum(first())))
            "MOD" -> {
                val a = toNum(first()); val b = toNum(second())
                if (b == 0.0) Value.Num(0.0) else Value.Num(a % b)
            }
            "POWER" -> Value.Num(Math.pow(toNum(first()), toNum(second())))
            "CONCAT", "CONCATENATE" -> Value.Str(flat.joinToString("") { toStr(it) })
            "LEN" -> Value.Num(toStr(first()).length.toDouble())
            "LEFT" -> {
                val s = toStr(first()); val n = if (args.size > 1) toNum(second()).toInt() else 1
                Value.Str(if (n <= 0) "" else if (n >= s.length) s else s.substring(0, n))
            }
            "RIGHT" -> {
                val s = toStr(first()); val n = if (args.size > 1) toNum(second()).toInt() else 1
                Value.Str(if (n <= 0) "" else if (n >= s.length) s else s.substring(s.length - n))
            }
            "MID" -> {
                val s = toStr(first())
                val start = (if (args.size > 1) toNum(second()).toInt() else 1) - 1
                val length = if (args.size > 2) toNum(third()).toInt() else s.length
                if (start < 0 || start >= s.length || length <= 0) Value.Str("")
                else Value.Str(s.substring(start, Math.min(s.length, start + length)))
            }
            "UPPER" -> Value.Str(toStr(first()).uppercase())
            "LOWER" -> Value.Str(toStr(first()).lowercase())
            "TRIM" -> Value.Str(toStr(first()).trim())
            "AND" -> numIf(flat.all { truthy(it) })
            "OR" -> numIf(flat.any { truthy(it) })
            "NOT" -> numIf(!truthy(first()))
            "TODAY" -> Value.Str(SimpleDateFormat("yyyy-MM-dd").format(Date()))
            "NOW" -> Value.Str(SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date()))
            "PI" -> Value.Num(Math.PI)
            "RAND" -> Value.Num(Math.random())
            else -> Value.Err("unknown function $name")
        }
    }

    private fun truthy(v: Value): Boolean = when (v) {
        is Value.Num -> v.d != 0.0
        is Value.Str -> v.s.equals("TRUE", true) || (v.s.toDoubleOrNull() ?: 0.0) != 0.0
        else -> false
    }

    private fun numIf(b: Boolean): Value = Value.Num(if (b) 1.0 else 0.0)

    private fun toNum(v: Value): Double = when (v) {
        is Value.Num -> v.d
        is Value.Str -> v.s.trim().toDoubleOrNull() ?: throw EvalException("expected number but got '${v.s}'")
        is Value.Err -> throw EvalException(v.msg)
        Value.Empty -> 0.0
    }

    private fun toStr(v: Value): String = when (v) {
        is Value.Num -> calc.formatNumber(v.d)
        is Value.Str -> v.s
        is Value.Err -> "#ERROR"
        Value.Empty -> ""
    }
}
