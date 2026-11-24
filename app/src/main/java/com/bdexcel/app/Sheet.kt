package com.bdexcel.app

import android.os.Parcel
import android.os.Parcelable

/**
 * একটা শিট (worksheet) মডেল। এক्सেলের একটা ট্যাব এর মত।
 */
data class Sheet(
    val id: String,
    val name: String,
    val cells: MutableMap<CellCoordinate, Cell> = mutableMapOf(),
    var columnCount: Int = 26,
    var rowCount: Int = 1000,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) : Parcelable {

    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readString() ?: "Sheet1",
        mutableMapOf(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readLong(),
        parcel.readLong()
    )

    fun getCell(row: Int, column: Int): Cell {
        val key = CellCoordinate(row, column)
        return cells.getOrPut(key) { Cell(row, column) }
    }

    fun setCell(cell: Cell) {
        cells[CellCoordinate(cell.row, cell.column)] = cell
        updatedAt = System.currentTimeMillis()
    }

    fun removeCell(row: Int, column: Int) {
        cells.remove(CellCoordinate(row, column))
        updatedAt = System.currentTimeMillis()
    }

    fun clear() {
        cells.clear()
        updatedAt = System.currentTimeMillis()
    }

    fun cellList(): List<Cell> = cells.values.toList()

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
        parcel.writeString(name)
        parcel.writeInt(columnCount)
        parcel.writeInt(rowCount)
        parcel.writeLong(createdAt)
        parcel.writeLong(updatedAt)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<Sheet> {
        override fun createFromParcel(parcel: Parcel): Sheet = Sheet(parcel)
        override fun newArray(size: Int): Array<Sheet?> = arrayOfNulls(size)
    }
}

/**
 * বই ওয়ার্কবুক মডেল। একাধিক শিট থাকতে পারে।
 */
data class Workbook(
    val id: String,
    val name: String,
    val sheets: MutableList<Sheet> = mutableListOf(),
    var activeSheetIndex: Int = 0,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) {
    val activeSheet: Sheet
        get() = sheets.getOrElse(activeSheetIndex) { Sheet("s1", "Sheet1") }

    fun addSheet(name: String = "Sheet${sheets.size + 1}") {
        sheets.add(Sheet(java.util.UUID.randomUUID().toString(), name))
        updatedAt = System.currentTimeMillis()
    }

    fun removeSheet(index: Int) {
        if (sheets.size > 1) sheets.removeAt(index)
        if (activeSheetIndex >= sheets.size) activeSheetIndex = sheets.lastIndex
        updatedAt = System.currentTimeMillis()
    }
}
