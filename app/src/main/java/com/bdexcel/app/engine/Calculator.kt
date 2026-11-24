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
    var rangeRaw: List<String>? = null

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

    /** Raw (unedited) text of a cell — used by criteria functions. */
    internal fun rawAt(col: Int, row: Int): String =
        sheet.cellOrNull(col, row)?.raw ?: ""
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
            "AND", "OR", "NOT",
            "SIN", "COS", "TAN", "ASIN", "ACOS", "ATAN", "ATAN2", "LOG", "LN",
            "LOG10", "EXP", "SIGN", "CEILING", "FLOOR", "TRUNC", "FACT",
            "DEGREES", "RADIANS", "GCD", "LCM", "SUMPRODUCT", "STDEVP", "VAR",
            "VARP", "LARGE", "SMALL", "RANK", "COUNTBLANK", "SUMIF", "COUNTIF",
            "AVERAGEIF", "ISBLANK", "ISNUMBER", "ISTEXT", "ISERROR", "SUBSTITUTE",
            "FIND", "SEARCH", "REPLACE", "REPT", "VALUE", "EXACT", "DATE", "YEAR",
            "MONTH", "DAY", "HOUR", "MINUTE", "SECOND", "WEEKDAY", "DAYS"
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
                        val values = rangeValues(word, second.first).first
                        pos = second.second
                        return numValue(values)
                    }
                }
                // plain cell reference
                val ref = parseRef(word) ?: throw EvalException("unknown name '$word'")
                pos = end
                return calc.valueOf(ref.col, ref.row)
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

    private fun rangeValues(a: String, b: String): Pair<List<Value>, List<String>> {
        val ra = parseRefParts(a) ?: throw EvalException("bad range '$a'")
        val rb = parseRefParts(b) ?: throw EvalException("bad range '$b'")
        val out = ArrayList<Value>()
        val raws = ArrayList<String>()
        val r1 = minOf(ra.row, rb.row); val r2 = maxOf(ra.row, rb.row)
        val c1 = minOf(ra.col, rb.col); val c2 = maxOf(ra.col, rb.col)
        for (r in r1..r2) {
            for (c in c1..c2) {
                guard()
                out.add(calc.valueOf(c, r))
                raws.add(calc.rawAt(c, r))
            }
        }
        return Pair(out, raws)
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
                    val (values, raws) = rangeValues(word.first, second.first)
                    arg.range = values
                    arg.rangeRaw = raws
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
            // math
            "SIN" -> Value.Num(Math.sin(toNum(first())))
            "COS" -> Value.Num(Math.cos(toNum(first())))
            "TAN" -> Value.Num(Math.tan(toNum(first())))
            "ASIN" -> Value.Num(Math.asin(toNum(first())))
            "ACOS" -> Value.Num(Math.acos(toNum(first())))
            "ATAN" -> Value.Num(Math.atan(toNum(first())))
            "ATAN2" -> Value.Num(Math.atan2(toNum(first()), toNum(second())))
            "LN" -> Value.Num(Math.log(toNum(first())))
            "LOG10" -> Value.Num(Math.log10(toNum(first())))
            "LOG" -> {
                val b = if (args.size > 1 && args[1].single is Value.Num) toNum(second()) else 10.0
                Value.Num(Math.log(toNum(first())) / Math.log(b))
            }
            "EXP" -> Value.Num(Math.exp(toNum(first())))
            "SIGN" -> {
                val d = toNum(first())
                Value.Num(if (d > 0) 1.0 else if (d < 0) -1.0 else 0.0)
            }
            "CEILING" -> {
                val d = toNum(first())
                val sig = if (args.size > 1 && args[1].single is Value.Num) toNum(second()) else 1.0
                if (sig == 0.0) Value.Num(0.0) else Value.Num(Math.ceil(d / sig) * sig)
            }
            "FLOOR" -> {
                val d = toNum(first())
                val sig = if (args.size > 1 && args[1].single is Value.Num) toNum(second()) else 1.0
                if (sig == 0.0) Value.Num(0.0) else Value.Num(Math.floor(d / sig) * sig)
            }
            "TRUNC" -> {
                val d = toNum(first())
                val digits = if (args.size > 1) toNum(second()).toInt() else 0
                val f = Math.pow(10.0, digits.toDouble())
                Value.Num(kotlin.math.truncate(d * f) / f)
            }
            "FACT" -> {
                val n = toNum(first()).toInt()
                var acc = 1.0
                for (i in 2..Math.abs(n)) acc *= i
                Value.Num(acc)
            }
            "DEGREES" -> Value.Num(Math.toDegrees(toNum(first())))
            "RADIANS" -> Value.Num(Math.toRadians(toNum(first())))
            "GCD" -> {
                var g = 0L
                for (n in nums) g = gcd(g, Math.abs(n.toLong()))
                Value.Num(g.toDouble())
            }
            "LCM" -> {
                var l = 1L
                for (n in nums) l = l / gcd(l, Math.abs(n.toLong())) * Math.abs(n.toLong())
                Value.Num(l.toDouble())
            }
            "SUMPRODUCT" -> {
                if (args.size < 2) Value.Err("needs two ranges")
                else {
                    val a = args[0].range ?: return Value.Err("first arg must be a range")
                    val b = args[1].range ?: return Value.Err("second arg must be a range")
                    if (a.size != b.size) Value.Err("ranges differ in size")
                    else {
                        var sum = 0.0
                        for (i in a.indices) {
                            val x = (a[i] as? Value.Num)?.d ?: 0.0
                            val y = (b[i] as? Value.Num)?.d ?: 0.0
                            sum += x * y
                        }
                        Value.Num(sum)
                    }
                }
            }
            // statistics
            "STDEVP" -> {
                if (nums.size < 1) Value.Err("empty")
                else {
                    val m = nums.average()
                    Value.Num(Math.sqrt(nums.sumOf { (it - m) * (it - m) } / nums.size))
                }
            }
            "VAR" -> {
                if (nums.size < 2) Value.Err("sample too small")
                else {
                    val m = nums.average()
                    Value.Num(nums.sumOf { (it - m) * (it - m) } / (nums.size - 1))
                }
            }
            "VARP" -> {
                if (nums.size < 1) Value.Err("empty")
                else {
                    val m = nums.average()
                    Value.Num(nums.sumOf { (it - m) * (it - m) } / nums.size)
                }
            }
            "LARGE" -> {
                val k = toNum(second()).toInt()
                val sorted = nums.sortedDescending()
                if (k < 1 || k > sorted.size) Value.Err("k out of range") else Value.Num(sorted[k - 1])
            }
            "SMALL" -> {
                val k = toNum(second()).toInt()
                val sorted = nums.sorted()
                if (k < 1 || k > sorted.size) Value.Err("k out of range") else Value.Num(sorted[k - 1])
            }
            "RANK" -> {
                val v = toNum(first())
                val list = args.getOrNull(1)?.range?.mapNotNull { (it as? Value.Num)?.d } ?: Value.Err("needs range")
                if (list is Value.Err) list
                else {
                    val arr = list as List<Double>
                    val sorted = arr.sortedDescending()
                    val idx = sorted.indexOf(v)
                    Value.Num((idx + 1).toDouble())
                }
            }
            "COUNTBLANK" -> {
                var count = 0
                for (a in args) {
                    val raws = a.rangeRaw
                    if (raws != null) count += raws.count { it.isBlank() }
                    else if (toStr(a.single).isEmpty()) count++
                }
                Value.Num(count.toDouble())
            }
            "SUMIF" -> criteria(args) { v, _, acc -> if (v != null) acc + v else acc }
            "COUNTIF" -> criteria(args) { v, _, count -> if (v != null) count + 1.0 else count }
            "AVERAGEIF" -> {
                val matched = ArrayList<Double>()
                criteriaRaw(args) { raw, numeric -> if (matchesCriteria(raw, numeric, toStr(second()))) matched.add(numeric ?: 0.0) }
                if (matched.isEmpty()) Value.Num(0.0) else Value.Num(matched.average())
            }
            // info
            "ISBLANK" -> Value.Num(if (toStr(first()).isEmpty()) 1.0 else 0.0)
            "ISNUMBER" -> Value.Num(if (first() is Value.Num) 1.0 else 0.0)
            "ISTEXT" -> Value.Num(if (first() is Value.Str) 1.0 else 0.0)
            "ISERROR" -> Value.Num(if (first() is Value.Err) 1.0 else 0.0)
            // text
            "SUBSTITUTE" -> {
                val text = toStr(first()); val old = toStr(second()); val newS = toStr(third())
                if (old.isEmpty()) Value.Str(text) else Value.Str(text.replace(old, newS))
            }
            "FIND", "SEARCH" -> {
                val needle = toStr(first()); val hay = toStr(second())
                val startIdx = if (args.size > 2) toNum(third()).toInt() - 1 else 0
                val hayL = if (name == "SEARCH") hay.lowercase() else hay
                val needleL = if (name == "SEARCH") needle.lowercase() else needle
                val idx = if (startIdx < 0 || startIdx >= hay.length) -1 else hayL.indexOf(needleL, startIdx)
                if (idx < 0) Value.Err("not found") else Value.Num((idx + 1).toDouble())
            }
            "REPLACE" -> {
                val text = toStr(first())
                val start = toNum(second()).toInt() - 1
                val length = toNum(third()).toInt()
                val newS = if (args.size > 3) toStr(args[3].single) else ""
                if (start < 0) Value.Str(newS + text)
                else Value.Str(text.substring(0, Math.min(start, text.length)) + newS + text.substring(Math.min(start + Math.max(length, 0), text.length)))
            }
            "REPT" -> {
                val text = toStr(first()); val n = toNum(second()).toInt().coerceIn(0, 10000)
                Value.Str(text.repeat(n))
            }
            "VALUE" -> {
                val t = toStr(first()).trim()
                val d = t.toDoubleOrNull() ?: Value.Err("not a number")
                if (d is Value.Num) d else Value.Err("not a number")
            }
            "EXACT" -> Value.Num(if (toStr(first()) == toStr(second())) 1.0 else 0.0)
            // date & time (ISO yyyy-MM-dd / yyyy-MM-dd HH:mm)
            "DATE" -> {
                val y = toNum(first()).toInt(); val m = toNum(second()).toInt(); val d = toNum(third()).toInt()
                val cal = java.util.Calendar.getInstance()
                cal.set(y, m - 1, d, 0, 0, 0)
                Value.Str(java.text.SimpleDateFormat("yyyy-MM-dd").format(cal.time))
            }
            "YEAR" -> Value.Num(dateField(first(), java.util.Calendar.YEAR).toDouble())
            "MONTH" -> Value.Num((dateField(first(), java.util.Calendar.MONTH) + 1).toDouble())
            "DAY" -> Value.Num(dateField(first(), java.util.Calendar.DAY_OF_MONTH).toDouble())
            "HOUR" -> Value.Num(timeField(first(), java.util.Calendar.HOUR_OF_DAY).toDouble())
            "MINUTE" -> Value.Num(timeField(first(), java.util.Calendar.MINUTE).toDouble())
            "SECOND" -> Value.Num(timeField(first(), java.util.Calendar.SECOND).toDouble())
            "WEEKDAY" -> {
                val d = parseDateOrTime(toStr(first()))
                if (d == null) Value.Err("bad date")
                else {
                    val cal = java.util.Calendar.getInstance()
                    cal.time = d
                    val dow = cal.get(java.util.Calendar.DAY_OF_WEEK) // 1=Sunday
                    Value.Num(dow.toDouble())
                }
            }
            "DAYS" -> {
                val e = parseDateOrTime(toStr(first()))
                val s2 = parseDateOrTime(toStr(second()))
                if (e == null || s2 == null) Value.Err("bad date")
                else Value.Num(((e.time - s2.time) / 86400000.0))
            }
            else -> Value.Err("unknown function $name")
        }
    }

    private fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    /** Generic criteria accumulation: [transform] receives per-cell numeric value (null if non-numeric). */
    private fun criteria(
        args: List<Arg>,
        transform: (Double?, List<String>, Double) -> Double
    ): Value {
        var acc = 0.0
        criteriaRaw(args) { _, num -> acc = transform(num, emptyList(), acc) }
        return Value.Num(acc)
    }

    /** Iterates the first range argument against the criteria in the second argument. */
    private fun criteriaRaw(args: List<Arg>, visit: (String, Double?) -> Unit) {
        val range = args.getOrNull(0)?.range ?: return
        val raws = args.getOrNull(0)?.rangeRaw ?: List(range.size) { "" }
        val crit = args.getOrNull(1)?.let { toStr(it.single) } ?: ""
        for (i in range.indices) {
            val numeric = (range[i] as? Value.Num)?.d
            if (matchesCriteria(raws.getOrElse(i) { "" }, numeric, crit)) {
                visit(raws.getOrElse(i) { "" }, numeric)
            }
        }
    }

    /** Supports "=5", ">3", "<=2", "<>x", plain text and * / ? wildcards. */
    private fun matchesCriteria(raw: String, numeric: Double?, criteria: String): Boolean {
        val c = criteria.trim()
        if (c.isEmpty()) return raw.isBlank()
        val opMatch = Regex("^(>=|<=|<>|=|<|>)(.*)$").find(c)
        if (opMatch != null) {
            val op = opMatch.groupValues[1]
            val rest = opMatch.groupValues[2].trim()
            val num = rest.toDoubleOrNull()
            if (num != null && numeric != null) {
                return when (op) {
                    "=" -> numeric == num; "<>" -> numeric != num
                    ">" -> numeric > num; "<" -> numeric < num
                    ">=" -> numeric >= num; else -> numeric <= num
                }
            }
            val rv = raw.trim()
            return when (op) {
                "=" -> rv.equals(rest, ignoreCase = true)
                "<>" -> !rv.equals(rest, ignoreCase = true)
                else -> false
            }
        }
        if (raw.isBlank()) return false
        if (c.contains('*') || c.contains('?')) {
            val regex = Regex("^" + Regex.escape(c).replace("\\*", ".*").replace("\\?", ".") + "$", RegexOption.IGNORE_CASE)
            return regex.matches(raw.trim())
        }
        return raw.trim().equals(c, ignoreCase = true)
    }

    private fun parseDateOrTime(text: String): java.util.Date? {
        val t = text.trim()
        for (pattern in listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd")) {
            try {
                return java.text.SimpleDateFormat(pattern).parse(t) ?: continue
            } catch (e: Exception) {
            }
        }
        return null
    }

    private fun dateField(v: Value, field: Int): Int {
        val d = parseDateOrTime(toStr(v)) ?: return 0
        val cal = java.util.Calendar.getInstance()
        cal.time = d
        return cal.get(field)
    }

    private fun timeField(v: Value, field: Int): Int {
        val d = parseDateOrTime(toStr(v)) ?: return 0
        val cal = java.util.Calendar.getInstance()
        cal.time = d
        return cal.get(field)
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
