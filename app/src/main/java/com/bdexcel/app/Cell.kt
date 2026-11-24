package com.bdexcel.app

import android.os.Parcel
import android.os.Parcelable

/**
 * একটা সেলের ডেটা মডেল। এক্সেলের প্রত্যেক സെल এর চharia প্রপার্টি একে একটা করে রাখে।
 */
data class Cell(
    val row: Int,
    val column: Int,
    var value: String = "",
    var formula: String = "",
    var evaluatedValue: String = "",
    var bold: Boolean = false,
    var italic: Boolean = false,
    var textColor: Int = -16777216, // default black
    var backgroundColor: Int = 0, // default transparent
    var fontSize: Float = 14f,
    var horizontalAlignment: Int = 0, // 0=left,1=center,2=right
    var verticalAlignment: Int = 0, // 0=top,1=center,2=bottom
    var wrapText: Boolean = false,
    var columnWidth: Float = 100f,
    var rowHeight: Float = 40f
) : Parcelable {

    constructor(parcel: Parcel) : this(
        parcel.readInt(),
        parcel.readInt(),
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readInt() != 0,
        parcel.readInt() != 0,
        parcel.readInt(),
        parcel.readInt(),
        parcel.readFloat(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readInt() != 0,
        parcel.readFloat(),
        parcel.readFloat()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(row)
        parcel.writeInt(column)
        parcel.writeString(value)
        parcel.writeString(formula)
        parcel.writeString(evaluatedValue)
        parcel.writeInt(if (bold) 1 else 0)
        parcel.writeInt(if (italic) 1 else 0)
        parcel.writeInt(textColor)
        parcel.writeInt(backgroundColor)
        parcel.writeFloat(fontSize)
        parcel.writeInt(horizontalAlignment)
        parcel.writeInt(verticalAlignment)
        parcel.writeInt(if (wrapText) 1 else 0)
        parcel.writeFloat(columnWidth)
        parcel.writeFloat(rowHeight)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<Cell> {
        override fun createFromParcel(parcel: Parcel): Cell = Cell(parcel)
        override fun newArray(size: Int): Array<Cell?> = arrayOfNulls(size)
    }

    fun copyWith(
        row: Int = this.row,
        column: Int = this.column,
        value: String = this.value,
        formula: String = this.formula,
        evaluatedValue: String = this.evaluatedValue,
        bold: Boolean = this.bold,
        italic: Boolean = this.italic,
        textColor: Int = this.textColor,
        backgroundColor: Int = this.backgroundColor,
        fontSize: Float = this.fontSize,
        horizontalAlignment: Int = this.horizontalAlignment,
        verticalAlignment: Int = this.verticalAlignment,
        wrapText: Boolean = this.wrapText,
        columnWidth: Float = this.columnWidth,
        rowHeight: Float = this.rowHeight
    ): Cell =
        Cell(
            row, column, value, formula, evaluatedValue, bold, italic,
            textColor, backgroundColor, fontSize, horizontalAlignment,
            verticalAlignment, wrapText, columnWidth, rowHeight
        )
}

/**
 * সেলের ছোট স্ট.cellের রেফারেন্স (কোঅর্ডিনেট)।
 */
data class CellCoordinate(val row: Int, val column: Int) {
    fun toAlpha(): String = "${columnToLetter(column)}$row"
    companion object {
        fun fromAlpha(ref: String): CellCoordinate {
            val m = "\\A([A-Za-z]+)(\\d+)\\z".toRegex().find(ref) ?: throw IllegalArgumentException("Invalid cell ref: $ref")
            return CellCoordinate(m.groupValues[2].toInt(), letterToColumn(m.groupValues[1]))
        }
        private fun columnToLetter(col: Int): String {
            var n = col
            val sb = StringBuilder()
            while (n > 0) {
                n--
                sb.insert(0, ('A'.code + n % 26).toChar())
                n /= 26
            }
            return sb.toString()
        }
        private fun letterToColumn(letters: String): Int {
            var col = 0
            for (c in letters) col = col * 26 + (c.uppercaseChar().code - 'A'.code + 1)
            return col
        }
    }
}

/**
 * এখানের সেলে রেফারেন্স রেজাল্ট Cache , ফরমুলা রিক্যালকুলেশনের সময় ব্যবহার হয়।
 */
data class SheetSnapshot(
    val sheetName: String,
    val lastModified: Long,
    val cellCount: Int,
    val cells: List<Cell>
)
