package com.bdexcel.app.io

import android.content.Context
import com.bdexcel.app.model.Workbook
import java.io.File

/** Manages workbook documents on local storage: one folder per workbook. */
object Docs {

    fun root(ctx: Context): File = File(ctx.filesDir, "sheets").apply { mkdirs() }

    fun all(ctx: Context): List<File> =
        root(ctx).listFiles { f -> f.isDirectory }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun create(ctx: Context): File {
        val dir = File(root(ctx), "doc_${System.currentTimeMillis()}")
        dir.mkdirs()
        imagesDir(dir)
        return dir
    }

    fun projectFile(dir: File): File = File(dir, "project.json")

    fun imagesDir(dir: File): File = File(dir, "images").apply { mkdirs() }

    fun readWorkbook(dir: File): Workbook {
        val wb = Workbook()
        val f = projectFile(dir)
        if (f.exists()) {
            try {
                wb.fromJson(f.readBytes())
            } catch (e: Exception) {
            }
        }
        if (wb.sheets.isEmpty()) wb.sheets.add(com.bdexcel.app.model.Sheet("Sheet1"))
        return wb
    }

    fun writeWorkbook(dir: File, wb: Workbook) {
        wb.modified = System.currentTimeMillis()
        try {
            projectFile(dir).writeBytes(wb.toJson())
        } catch (e: Exception) {
        }
    }

    fun delete(dir: File) {
        dir.deleteRecursively()
    }

    fun defaultName(index: Int): String = "শিট $index"
}
