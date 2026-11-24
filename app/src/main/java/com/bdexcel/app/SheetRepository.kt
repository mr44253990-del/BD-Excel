package com.bdexcel.app

import android.content.Context
import java.io.*

/**
 * ওয়ার্কবুক লোকাল স্টোরেজ রিপোজিটরি। জসন আকারে আপস ফাইলে সেভ করে।
 * রিয়াল এক্স ফাইল থেকে ওপেন করে এখানে এন এবং এখান থেকে সঠিকভাবে এক্সপোর্ট করার জন্য ব্যবহার হয়।
 */
class SheetRepository(private val context: Context) {

    private val workbooksDir: File
    private val autosaveFile: File

    init {
        workbooksDir = File(context.filesDir, "workbooks")
        workbooksDir.mkdirs()
        autosaveFile = File(workbooksDir, "autosave.json")
    }

    fun saveWorkbook(workbook: Workbook): File {
        val file = File(workbooksDir, "${workbook.id}.json")
        file.parentFile.mkdirs()
        try (fos = FileOutputStream(file); osw = OutputStreamWriter(fos, "UTF-8")) {
            osw.write(WorkbookSerializer.toJson(workbook))
        } catch (e: Exception) {
            throw RuntimeException("Failed to save workbook: ${e.message}", e)
        }
        return file
    }

    fun loadWorkbook(id: String): Workbook? {
        val file = File(workbooksDir, "$id.json")
        return if (file.exists()) WorkbookSerializer.fromJson(file.readText(Charsets.UTF_8)) else null
    }

    fun deleteWorkbook(id: String): Boolean {
        return File(workbooksDir, "$id.json").delete()
    }

    fun listWorkbooks(): List<File> {
        return workbooksDir.listFiles()
            ?.filter { it.extension == "json" && it.name != "autosave.json" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun autosave(workbook: Workbook) {
        try {
            autosaveFile.parentFile.mkdirs()
            autosaveFile.writeText(WorkbookSerializer.toJson(workbook), Charsets.UTF_8)
        } catch (_: Exception) {
            // অটোসেভ ব্যর্থ হলে অ্যাপ ক্র্যাশ হবে না
        }
    }

    fun loadAutosave(): Workbook? {
        return if (autosaveFile.exists()) WorkbookSerializer.fromJson(autosaveFile.readText(Charsets.UTF_8)) else null
    }

    fun clearAutosave() {
        autosaveFile.delete()
    }

    fun exportWorkbookToExternal(workbook: Workbook, format: String = "xlsx"): File {
        val downloads = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "BD Excel")
        downloads.mkdirs()
        val out = File(downloads, "${workbook.name.replace(" ", "_").lowercase()}.$format")
        return when (format.lowercase()) {
            "xlsx" -> ImportExportManager(context).exportXlsx(workbook, out)
            "csv" -> ImportExportManager(context).exportCsv(workbook, out)
            else -> throw IllegalArgumentException("Unsupported format: $format")
        }
    }

    companion object {
        const val EXTENSION = "json"
    }
}
