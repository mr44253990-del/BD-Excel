package com.bdexcel.app

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

/**
 * BD Excel — মাইক্রোসফট এক্সেলের মতো অ্যান্ড্রয়েড স্প্রেডশীট অ্যাপ্লিকেশন।
 * রিয়েল এক্সেল (.xlsx/.csv) ফাইল ওপেন, এডিট, ফরমুলা, কালার, জুম, এক্সপোর্ট (এক্সেল/সিএসভ/ফটো) সব সাপোর্ট করে।
 */
class MainActivity : AppCompatActivity() {

    private lateinit var sheetView: SheetView
    private lateinit var formulaBar: EditText
    private lateinit var sheetNameTv: TextView
    private lateinit var zoomSeek: SeekBar
    private lateinit var repo: SheetRepository
    private lateinit var exportManager: ImportExportManager
    private lateinit var imageExport: ImageExportManager

    private var workbook: Workbook = Workbook(java.util.UUID.randomUUID().toString(), "BD Workbook")
    private var formulaEngine: FormulaEngine = FormulaEngine(workbook.activeSheet)
    private var currentUri: Uri? = null

    private val AUTOSAVE_INTERVAL_MS = 30_000L
    private var autosaveHandler: Handler = Handler(Looper.getMainLooper())
    private var autosaveRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repo = SheetRepository(this)
        exportManager = ImportExportManager(this)
        imageExport = ImageExportManager(this)

        // ইন্টেন্ট থেকে ফাইল ওপেন (রিয়াল এক্সেল/সিএ瑟夫)
        handleIntent(intent)

        sheetView = findViewById(R.id.sheetView)
        formulaBar = findViewById(R.id.formulaBar)
        sheetNameTv = findViewById(R.id.sheetNameTv)
        zoomSeek = findViewById(R.id.zoomSeek)

        sheetNameTv.text = workbook.activeSheet.name
        refreshSheet()

        // সেল ক্লিক → ফর্মুলা বারে ভ্যালু দেখানো
        sheetView.onCellClick = { coord ->
            val cell = workbook.activeSheet.getCell(coord.row, coord.column)
            formulaBar.setText(if (cell.formula.isNotEmpty()) cell.formula else cell.value)
            formulaBar.setSelection(formulaBar.text.length)
            sheetView.setSelectedCell(coord)
        }

        // সেল দীর্ঘ ক্লিক → কপি/পেস্ট/ডিলিট/কালার মেনু
        sheetView.onCellLongClick = { coord ->
            showCellContextMenu(coord)
            true
        }

        // ফর্মুলা বারে টেক্সট পরিবর্তন → রিয়াল টাইম প্রিভিউ এবং এন্টার দিলে সেভ
        formulaBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveCurrentCell(formulaBar.text.toString())
                true
            } else false
        }

        formulaBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {}
        })

        // জুম সਿੱਕবাার
        zoomSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) applyZoom(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        // অটোসেভ শুরু করুন
        startAutosave()

        // টুলবার মেনু
        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val action = intent?.action
        val data = intent?.data
        val clip = intent?.clipData
        if (action == Intent.ACTION_VIEW || action == Intent.ACTION_EDIT || action == Intent.ACTION_SEND) {
            val uri = data ?: clip?.getItemAt(0)?.uri
            if (uri != null) openUri(uri)
        }
    }

    private fun openUri(uri: Uri) {
        currentUri = uri
        val file = exportManager.uriToFile(uri)
        if (file == null || !file.exists()) {
            // কনটেন্ট provenire-> কপি করাExternalCacheDir এ
            val copied = copyUriToCache(uri)
            if (copied != null) {
                loadWorkbookFile(copied)
            } else {
                Snackbar.make(findViewById(android.R.id.content), "ফাইল ওপেন করা যায়নি", Snackbar.LENGTH_LONG).show()
            }
        } else {
            loadWorkbookFile(file)
        }
    }

    private fun copyUriToCache(uri: Uri): File? {
        return try {
            val cache = File(externalCacheDir, "import_${System.currentTimeMillis()}")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(cache).use { output -> input.copyTo(output) }
            }
            cache
        } catch (e: Exception) {
            null
        }
    }

    private fun loadWorkbookFile(file: File) {
        try {
            workbook = when (file.extension.lowercase()) {
                "xlsx", "xls" -> exportManager.importWorkbook(file)
                "csv" -> exportManager.importWorkbook(file)
                else -> repo.loadWorkbook(file.nameWithoutExtension) ?: Workbook(java.util.UUID.randomUUID().toString(), file.nameWithoutExtension)
            }
            currentUri = exportManager.fileToUri(file)
            formulaEngine = FormulaEngine(workbook.activeSheet)
            sheetNameTv.text = workbook.activeSheet.name
            refreshSheet()
            Snackbar.make(findViewById(android.R.id.content), "ওপেন করা হয়েছে: ${file.name}", Snackbar.LENGTH_LONG).show()
        } catch (e: Exception) {
            Snackbar.make(findViewById(android.R.id.content), "ইমপোর্ট ব্যর্থ: ${e.message}", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun refreshSheet() {
        formulaEngine = FormulaEngine(workbook.activeSheet)
        // রিক্যালকুলেট সকল ফরমুলা
        val results = formulaEngine.recalculateAll()
        for ((coord, value) in results) {
            val cell = workbook.activeSheet.getCell(coord.row, coord.column)
            workbook.activeSheet.setCell(cell.copyWith(evaluatedValue = value))
        }
        sheetView.setSheet(workbook.activeSheet)
        sheetView.invalidate()
        repo.autosave(workbook)
    }

    private fun saveCurrentCell(newValue: String) {
        val coord = sheetView.getSelectedCell() ?: return
        val cell = workbook.activeSheet.getCell(coord.row, coord.column)
        val isFormula = newValue.trimStart().startsWith("=")
        val updated = cell.copyWith(
            value = if (isFormula) "" else newValue,
            formula = if (isFormula) newValue else cell.formula,
            evaluatedValue = if (isFormula) formulaEngine.evaluate(newValue) else cell.evaluatedValue
        )
        workbook.activeSheet.setCell(updated)
        formulaBar.setText(if (updated.formula.isNotEmpty()) updated.formula else updated.value)
        refreshSheet()
    }

    private fun applyZoom(progress: Int) {
        // প্রোগ্রেস 0-100 → স্কেল 0.5x - 2.5x
        val scale = 0.5f + (progress / 100f) * 2.0f
        sheetView.scaleFactor = scale.coerceIn(0.5f, 4.0f)
        sheetView.invalidate()
    }

    // ==================== টুলবার মেনু ====================

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_new -> { newWorkbook(); true }
            R.id.action_open -> { pickFile(); true }
            R.id.action_save -> { saveWorkbook(); true }
            R.id.action_export_xlsx -> { exportWorkbook("xlsx"); true }
            R.id.action_export_csv -> { exportWorkbook("csv"); true }
            R.id.action_export_image -> { exportSheetImage(); true }
            R.id.action_add_sheet -> { addSheet(); true }
            R.id.action_clear -> { clearSheet(); true }
            R.id.action_about -> { showAbout(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ==================== অপারেশন ====================

    private fun newWorkbook() {
        workbook = Workbook(java.util.UUID.randomUUID().toString(), "New Workbook")
        workbook.sheets.clear()
        workbook.addSheet("Sheet1")
        currentUri = null
        formulaEngine = FormulaEngine(workbook.activeSheet)
        sheetNameTv.text = workbook.activeSheet.name
        refreshSheet()
        Snackbar.make(findViewById(android.R.id.content), "নতুন ওয়ার্কবুক তৈরি", Snackbar.LENGTH_SHORT).show()
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "text/csv",
                "application/json"
            ))
        }
        startActivityForResult(intent, REQUEST_OPEN_FILE)
    }

    private fun saveWorkbook() {
        if (workbook.name.isBlank()) workbook.name = "BD Workbook"
        try {
            val file = repo.saveWorkbook(workbook)
            currentUri = exportManager.fileToUri(file)
            Snackbar.make(findViewById(android.R.id.content), "সেভ করা হলো: ${file.name}", Snackbar.LENGTH_LONG).show()
        } catch (e: Exception) {
            Snackbar.make(findViewById(android.R.id.content), "সেভ ব্যর্থ: ${e.message}", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun exportWorkbook(format: String) {
        try {
            val out = repo.exportWorkbookToExternal(workbook, format)
            Snackbar.make(findViewById(android.R.id.content), "এক্সপোর্ট করা হলো: ${out.absolutePath}", Snackbar.LENGTH_LONG).show()
            shareFile(out)
        } catch (e: Exception) {
            Snackbar.make(findViewById(android.R.id.content), "এক্সপোর্ট ব্যর্থ: ${e.message}", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun exportSheetImage() {
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        imageExport.exportSheetAsImage(workbook.activeSheet, width, height) { uri ->
            if (uri != null) {
                Snackbar.make(findViewById(android.R.id.content), "শিট ফটো হিসেবে সেভ করা হলো (গ্যালারি)", Snackbar.LENGTH_LONG).show()
            } else {
                Snackbar.make(findViewById(android.R.id.content), "ফটো এক্সপোর্ট ব্যর্থ", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun addSheet() {
        val input = TextInputEditText(this)
        input.hint = "শিটের নাম"
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("নতুন শিট যোগ করুন")
            .setView(input)
            .setPositiveButton("ন پیدা করি") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "Sheet${workbook.sheets.size + 1}" }
                workbook.addSheet(name)
                sheetNameTv.text = workbook.activeSheet.name
                refreshSheet()
            }
            .setNegativeButton("বাতিল") { _, _ -> }
            .create()
        dlg.show()
    }

    private fun clearSheet() {
        MaterialAlertDialogBuilder(this)
            .setTitle("শিট পরিষ্কার করবেন?")
            .setMessage("এই শিটের সব সেল মুছে ফেলা হবে।")
            .setPositiveButton("হ্যাঁ") { _, _ ->
                workbook.activeSheet.clear()
                refreshSheet()
            }
            .setNegativeButton("না") { _, _ -> }
            .show()
    }

    private fun showCellContextMenu(coord: CellCoordinate) {
        val cell = workbook.activeSheet.getCell(coord.row, coord.column)
        val items = arrayOf(
            "সেল এডিট করুন",
            "ফরমুলা লাগুন (=SUM(A1:A10) 등)",
            "প年起 বাক্স কালার দিন",
            "টেক্সট কালার দিন",
            "কপি",
            "পেস্ট",
            "সেল ডিলিট করুন"
        )
        AlertDialog.Builder(this)
            .setTitle("সেল: ${coord.toAlpha()}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> editCellDialog(coord, cell)
                    1 -> formulaDialog(coord, cell)
                    2 -> pickColorDialog(coord, isBackground = true)
                    3 -> pickColorDialog(coord, isBackground = false)
                    4 -> copyCell(coord)
                    5 -> pasteCell(coord)
                    6 -> workbook.activeSheet.removeCell(coord.row, coord.column); refreshSheet()
                }
            }
            .show()
    }

    private var clipboardCell: Cell? = null

    private fun copyCell(coord: CellCoordinate) {
        clipboardCell = workbook.activeSheet.getCell(coord.row, coord.column)
        Snackbar.make(findViewById(android.R.id.content), "কপি করা হয়েছে", Snackbar.LENGTH_SHORT).show()
    }

    private fun pasteCell(coord: CellCoordinate) {
        clipboardCell?.let {
            workbook.activeSheet.setCell(it.copyWith(row = coord.row, column = coord.column))
            refreshSheet()
        } ?: Snackbar.make(findViewById(android.R.id.content), "কোন সেল কপি করা নেই", Snackbar.LENGTH_SHORT).show()
    }

    private fun editCellDialog(coord: CellCoordinate, cell: Cell) {
        val input = TextInputEditText(this)
        input.setText(if (cell.formula.isNotEmpty()) cell.formula else cell.value)
        input.setSelection(input.text?.length ?: 0)
        MaterialAlertDialogBuilder(this)
            .setTitle("সেল এডিট: ${coord.toAlpha()}")
            .setView(input)
            .setPositiveButton("সেভ") { _, _ ->
                saveCurrentCell(input.text.toString())
            }
            .setNegativeButton("বাতিল") { _, _ -> }
            .show()
    }

    private fun formulaDialog(coord: CellCoordinate, cell: Cell) {
        val input = TextInputEditText(this)
        input.setText(cell.formula)
        input.hint = "예: =SUM(A1:A10)"
        MaterialAlertDialogBuilder(this)
            .setTitle("ফরমুলা")
            .setView(input)
            .setPositiveButton("অ্যাপ্লাই") { _, _ ->
                val f = input.text.toString().trim()
                val updated = cell.copyWith(formula = f, value = "", evaluatedValue = if (f.isNotEmpty()) formulaEngine.evaluate(f) else "")
                workbook.activeSheet.setCell(updated)
                refreshSheet()
            }
            .setNegativeButton("বাতিল") { _, _ -> }
            .show()
    }

    private fun pickColorDialog(coord: CellCoordinate, isBackground: Boolean) {
        val colors = mapOf(
            "নiral/সাদা" to 0,
            "কালো" to Color.BLACK,
            "লাল" to Color.RED,
            "সবুজ" to Color.GREEN,
            "নীল" to Color.BLUE,
            "হলুদ" to Color.YELLOW,
            "সি ফ্ল্যাগ লাল" to ColorPalette.accent,
            "সি ফ্ল্যাগ সবুজ" to ColorPalette.primary
        )
        val items = colors.keys.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (isBackground) "ব্যাকগ্রাউন্ড কালার" else "টেক্সট কালার")
            .setItems(items) { _, which ->
                val color = colors[items[which]]!!
                val cell = workbook.activeSheet.getCell(coord.row, coord.column)
                val updated = if (isBackground) cell.copyWith(backgroundColor = color) else cell.copyWith(textColor = color)
                workbook.activeSheet.setCell(updated)
                refreshSheet()
            }
            .show()
    }

    private fun shareFile(file: File) {
        val uri = exportManager.fileToUri(file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = exportManager.context.getContentResolver().getType(uri) ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "শেয়ার করুন"))
    }

    // ==================== অটোসেভ ====================

    private fun startAutosave() {
        stopAutosave()
        autosaveRunnable = object : Runnable {
            override fun run() {
                repo.autosave(workbook)
                autosaveHandler.postDelayed(this, AUTOSAVE_INTERVAL_MS)
            }
        }
        autosaveHandler.postDelayed(autosaveRunnable!!, AUTOSAVE_INTERVAL_MS)
    }

    private fun stopAutosave() {
        autosaveRunnable?.let { autosaveHandler.removeCallbacks(it) }
        autosaveRunnable = null
    }

    override fun onDestroy() {
        stopAutosave()
        imageExport.shutdown()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OPEN_FILE && resultCode == RESULT_OK && data?.data != null) {
            openUri(data.data)
        }
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage("বাংলাদেশের মতো এক্সেল অ্যাপ। ইমপোর্ট/এক্সপোর্ট, ফরমুলা, কালার, জুম, ফটো সেভ — সবকিছু সাপোর্ট করে।")
            .setPositiveButton("ঠিক আছে") { _, _ -> }
            .show()
    }

    companion object {
        private const val REQUEST_OPEN_FILE = 1001
    }
}

/**
 * টেক্সট watcher সংক্ষিপ্ত ক্লাস (অতিরিক্ত ফাইলে না রাখার জন্য MainActivity-এ অন্তর্ভুক্ত)।
 */
private class TextWatcher : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: Editable?) {}
}
