package com.bdexcel.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.bdexcel.app.io.CsvCodec
import com.bdexcel.app.io.Docs
import com.bdexcel.app.io.PdfCodec
import com.bdexcel.app.io.XlsxCodec
import com.bdexcel.app.model.CellRef
import com.bdexcel.app.model.CellStyle
import com.bdexcel.app.model.Sheet
import com.bdexcel.app.model.Workbook
import com.bdexcel.app.view.SpreadsheetView
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import androidx.core.content.FileProvider

class MainActivity : AppCompatActivity(), SpreadsheetView.Listener {

    companion object {
        const val EXTRA_DIR = "dir"
    }

    private lateinit var grid: SpreadsheetView
    private lateinit var formulaEdit: EditText
    private lateinit var cellRefView: TextView
    private lateinit var sheetTabs:LinearLayout

    private val workbook = Workbook()
    private val undoStack = ArrayList<Sheet>()
    private val redoStack = ArrayList<Sheet>()

    private val palette = listOf(
        Color.BLACK, Color.DKGRAY, Color.GRAY, Color.LTGRAY, Color.WHITE,
        Color.parseColor("#E53935"), Color.parseColor("#FF7043"), Color.parseColor("#FBC02D"),
        Color.parseColor("#7CB342"), Color.parseColor("#006A4E"), Color.parseColor("#00ACC1"),
        Color.parseColor("#1E88E5"), Color.parseColor("#3949AB"), Color.parseColor("#8E24AA"),
        Color.parseColor("#F48FB1"), Color.parseColor("#795548"), Color.parseColor("#FFFFFF"),
        Color.parseColor("#FFF3CD")
    )
    private var pendingColorTargetIsText = true

    private lateinit var docDir: File
    private var selectionAtLongPress: Pair<Int, Int> = Pair(0, 0)

    // ------------------------- activity results -------------------------

    private val openLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importFrom(uri)
        }

    private val exportCsvLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) exportSheetTo(uri, "csv")
        }

    private val exportXlsxLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(XlsxCodec.mimeTypeFor("a.xlsx"))) { uri ->
            if (uri != null) exportSheetTo(uri, "xlsx")
        }

    private val saveProjectLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) exportSheetTo(uri, "json")
        }

    private val exportPngLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
            if (uri != null) exportPngTo(uri)
        }

    private val exportPdfLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri != null) exportPdfTo(uri)
        }

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) insertImageAt(uri, selectionAtLongPress.first, selectionAtLongPress.second)
        }

    // ------------------------- lifecycle -------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        grid = findViewById(R.id.grid)
        formulaEdit = findViewById(R.id.formulaEdit)
        cellRefView = findViewById(R.id.cellRefView)
        sheetTabs = findViewById(R.id.sheetTabs)
        grid.listener = this

        docDir = intent.getStringExtra(EXTRA_DIR)?.let { File(it) } ?: Docs.create(this)
        loadProject()
        if (workbook.sheets.isEmpty()) workbook.sheets.add(Sheet("Sheet1"))
        workbook.current = workbook.current.coerceIn(0, workbook.sheets.size - 1)
        grid.imageDir = Docs.imagesDir(docDir)
        grid.sheet = workbook.active()
        supportActionBar?.title = "📓 " + workbook.name

        wireFormatBar()
        wireFormulaEditor()
        renderTabs()

        formulaEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitFormulaBar()
                true
            } else false
        }

        handleViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        importFrom(uri)
    }

    override fun onPause() {
        Docs.writeWorkbook(docDir, workbook)
        super.onPause()
    }

    // ------------------------- persistence -------------------------

    private fun loadProject() {
        try {
            workbook.fromJson(Docs.projectFile(docDir).readBytes())
        } catch (e: Exception) {
            // new document
        }
    }

    private fun saveNow() {
        Docs.writeWorkbook(docDir, workbook)
    }

    // ------------------------- menu -------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "New").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 2, 1, "Open file (CSV/XLSX/JSON)").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 3, 2, "Save project (JSON)").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 4, 3, "Export CSV").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 5, 4, "Export XLSX").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 6, 5, "Zoom +").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 7, 6, "Zoom −").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 8, 7, "Zoom reset").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 9, 8, "About").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 10, 9, "Save as image (PNG)").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 11, 10, "Export PDF").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 12, 11, "Rename workbook").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 13, 12, "Share XLSX").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 14, 13, "Share CSV").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 15, 14, "Share PDF").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 16, 15, "Formula library (100+)").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> confirmNew()
            2 -> openLauncher.launch(arrayOf("*/*"))
            3 -> saveProjectLauncher.launch("bdexcel_${XlsxCodec.timestamp()}.json")
            4 -> exportCsvLauncher.launch("bdexcel_${XlsxCodec.timestamp()}.csv")
            5 -> exportXlsxLauncher.launch("bdexcel_${XlsxCodec.timestamp()}.xlsx")
            6 -> grid.zoomTo(grid.zoom * 1.25f)
            7 -> grid.zoomTo(grid.zoom / 1.25f)
            8 -> grid.zoomTo(1f)
            9 -> showAbout()
            10 -> exportPngLauncher.launch("bdexcel_${XlsxCodec.timestamp()}.png")
            11 -> exportPdfLauncher.launch("${workbook.name.take(24).ifBlank { "bdexcel" }}_${XlsxCodec.timestamp()}.pdf")
            12 -> renameWorkbook()
            13 -> shareFile("xlsx")
            14 -> shareFile("csv")
            15 -> shareFile("pdf")
            16 -> startActivity(Intent(this, FormulaLibraryActivity::class.java))
        }
        return super.onOptionsItemSelected(item)
    }

    private fun confirmNew() {
        AlertDialog.Builder(this)
            .setTitle("New workbook")
            .setMessage("Clear all sheets and start fresh?")
            .setPositiveButton("Yes") { _, _ ->
                pushUndo()
                workbook.sheets.clear()
                workbook.sheets.add(Sheet("Sheet1"))
                workbook.current = 0
                grid.sheet = workbook.active()
                renderTabs()
                grid.refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("BD Excel 🇧🇩")
            .setMessage("ডেভেলপার: রাকিবুল\n\nআমি অ্যাপ্লিকেশন ডেভেলপ করতে ভালোবাসি ❤️\n\nBD Excel — বাংলাদেশের জন্য তৈরি একটি সম্পূর্ণ অফলাইন স্প্রেডশিট অ্যাপ।\n\n• ১০০+ এক্সেল ফর্মুলা\n• XLSX / CSV / PDF / PNG ইমপোর্ট-এক্সপোর্ট\n• ছবি সেলে যোগ করা যায়\n• পিঞ্চ-জুম, স্মুথ স্ক্রলিং\n• আপনার সব ডেটা ১০০% প্রাইভেট, অফলাইনেই থাকে")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun renameWorkbook() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setText(workbook.name)
        val container = FrameLayout(this)
        val pad = (20 * resources.displayMetrics.density).toInt()
        container.setPadding(pad, pad, pad, pad)
        container.addView(input)
        AlertDialog.Builder(this)
            .setTitle("Rename workbook")
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    workbook.name = name
                    supportActionBar?.title = "📓 $name"
                    saveNow()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------- import / export -------------------------

    private fun importFrom(uri: Uri) {
        try {
            pushUndo()
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw Exception("empty file")
            val name = uri.lastPathSegment ?: ""
            val isXlsx = name.endsWith(".xlsx") || bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
            if (isXlsx) {
                XlsxCodec.read(ByteArrayInputStream(bytes), workbook)
            } else if (name.endsWith(".json") || bytes.firstOrNull { it == '{'.code.toByte() } != null) {
                workbook.fromJson(bytes)
            } else {
                val sheet = workbook.active()
                CsvCodec.read(ByteArrayInputStream(bytes), sheet)
            }
            workbook.current = 0
            grid.sheet = workbook.active()
            renderTabs()
            grid.refresh()
            syncFormulaBar()
            Toast.makeText(this, "Imported successfully", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportSheetTo(uri: Uri, kind: String) {
        try {
            contentResolver.openOutputStream(uri, "w")?.use { out ->
                when (kind) {
                    "csv" -> CsvCodec.write(workbook.active(), out)
                    "xlsx" -> XlsxCodec.write(workbook, out)
                    else -> out.write(workbook.toJson())
                }
            }
            Toast.makeText(this, "Saved successfully", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Saves the visible grid view as a PNG photo. */
    private fun exportPngTo(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri, "w")?.use { out ->
                grid.captureBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            Toast.makeText(this, "ছবি PNG হিসেবে সেভ হয়েছে", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Image save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportPdfTo(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri, "w")?.use { out ->
                PdfCodec.write(workbook.active(), out)
            }
            Toast.makeText(this, "PDF তৈরি হয়েছে", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "PDF save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Writes the chosen export format to the shared cache folder and fires a share intent. */
    private fun shareFile(kind: String) {
        try {
            val safeName = workbook.name.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "BDExcel" }
            val ext = when (kind) { "csv" -> "csv"; "pdf" -> "pdf"; else -> "xlsx" }
            val mime = when (kind) {
                "csv" -> "text/csv"
                "pdf" -> "application/pdf"
                else -> XlsxCodec.mimeTypeFor("a.xlsx")
            }
            val dir = File(cacheDir, "share").apply { mkdirs() }
            val sharedFile = File(dir, "${safeName}_${XlsxCodec.timestamp()}.$ext")
            FileOutputStream(sharedFile).use { out ->
                when (kind) {
                    "csv" -> CsvCodec.write(workbook.active(), out)
                    "pdf" -> PdfCodec.write(workbook.active(), out)
                    else -> XlsxCodec.write(workbook, out)
                }
            }
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", sharedFile)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "BD Excel শেয়ার করুন"))
        } catch (e: Exception) {
            Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------- cell images -------------------------

    override fun onCellLongPress(col: Int, row: Int) {
        selectionAtLongPress = Pair(col, row)
        val cell = workbook.active().cell(col, row)
        val options = if (cell.image != null) {
            arrayOf("📷 ছবি বদলান", "🗑 ছবি মুছুন", "🧹 সেল খালি করুন")
        } else {
            arrayOf("📷 ছবি যোগ করুন", "🧹 সেল খালি করুন")
        }
        AlertDialog.Builder(this)
            .setTitle(CellRef.key(col, row))
            .setItems(options) { _, which ->
                when (options[which]) {
                    "📷 ছবি যোগ করুন", "📷 ছবি বদলান" -> pickImageLauncher.launch(arrayOf("image/*"))
                    "🗑 ছবি মুছুন" -> {
                        pushUndo()
                        cell.image = null
                        grid.clearImageCache()
                        grid.refresh()
                    }
                    else -> {
                        pushUndo()
                        cell.raw = ""
                        grid.refresh()
                        syncFormulaBar()
                    }
                }
            }
            .show()
    }

    /** Copies the picked image inside the workbook folder and attaches it to the cell. */
    private fun insertImageAt(uri: Uri, col: Int, row: Int) {
        try {
            val imgs = Docs.imagesDir(docDir)
            val name = "img_${System.currentTimeMillis()}.jpg"
            val target = File(imgs, name)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            pushUndo()
            workbook.active().cell(col, row).image = name
            grid.clearImageCache()
            grid.refresh()
            Toast.makeText(this, "ছবি যোগ হয়েছে", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "ছবি যোগ করা যায়নি: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------- editing -------------------------

    private fun wireFormulaEditor() {
        formulaEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitFormulaBar()
        }
    }

    private fun commitFormulaBar() {
        pushUndo()
        val sheet = workbook.active()
        sheet.set(grid.selectedCol, grid.selectedRow, formulaEdit.text.toString())
        grid.refresh()
        hideKeyboard()
        saveNow()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(formulaEdit.windowToken, 0)
    }

    override fun onCellTapped(col: Int, row: Int) {}

    override fun onCellEditRequested(col: Int, row: Int) {
        formulaEdit.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(formulaEdit, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onSelectionChanged(col: Int, row: Int) {
        syncFormulaBar()
    }

    private fun syncFormulaBar() {
        cellRefView.text = CellRef.key(grid.selectedCol, grid.selectedRow)
        val raw = workbook.active().cellOrNull(grid.selectedCol, grid.selectedRow)?.raw ?: ""
        if (formulaEdit.text.toString() != raw) {
            formulaEdit.setText(raw)
            formulaEdit.setSelection(raw.length)
        }
    }

    private fun selectedCellStyle(): CellStyle = workbook.active().cell(grid.selectedCol, grid.selectedRow).style

    private fun applyStyle(action: (CellStyle) -> Unit) {
        pushUndo()
        action(selectedCellStyle())
        grid.refresh()
    }

    private fun wireFormatBar() {
        findViewById<ImageButton>(R.id.btnUndo).setOnClickListener { undo() }
        findViewById<ImageButton>(R.id.btnRedo).setOnClickListener { redo() }
        findViewById<TextView>(R.id.btnBold).setOnClickListener {
            applyStyle { it.bold = !it.bold }
        }
        findViewById<TextView>(R.id.btnItalic).setOnClickListener {
            applyStyle { it.italic = !it.italic }
        }
        findViewById<TextView>(R.id.btnAlign).setOnClickListener {
            applyStyle { st ->
                when (st.align) {
                    CellStyle.ALIGN_AUTO -> st.align = CellStyle.ALIGN_LEFT
                    CellStyle.ALIGN_LEFT -> st.align = CellStyle.ALIGN_CENTER
                    CellStyle.ALIGN_CENTER -> st.align = CellStyle.ALIGN_RIGHT
                    else -> st.align = CellStyle.ALIGN_AUTO
                }
            }
        }
        findViewById<View>(R.id.btnTextColor).setOnClickListener {
            pendingColorTargetIsText = true
            showColorPicker("Text color")
        }
        findViewById<View>(R.id.btnFillColor).setOnClickListener {
            pendingColorTargetIsText = false
            showColorPicker("Fill color")
        }
        findViewById<ImageButton>(R.id.btnInsertRow).setOnClickListener { insertRow() }
        findViewById<ImageButton>(R.id.btnDeleteRow).setOnClickListener { deleteRow() }
        findViewById<ImageButton>(R.id.btnInsertCol).setOnClickListener { insertCol() }
        findViewById<ImageButton>(R.id.btnDeleteCol).setOnClickListener { deleteCol() }
    }

    // ------------------------- row / column operations -------------------------

    private fun insertRow() {
        pushUndo()
        val sheet = workbook.active()
        val at = grid.selectedRow
        val newCells = HashMap<String, com.bdexcel.app.model.Cell>()
        for ((k, cell) in sheet.cells) {
            val p = CellRef.parseKey(k) ?: continue
            val (c, r) = p
            if (r >= at) newCells[CellRef.key(c, r + 1)] = cell else newCells[k] = cell
        }
        sheet.cells.clear()
        sheet.cells.putAll(newCells)
        sheet.rowCount = minOf(sheet.rowCount + 1, 5000)
        grid.refresh()
    }

    private fun deleteRow() {
        pushUndo()
        deleteRowImpl(workbook.active(), grid.selectedRow)
        grid.refresh()
    }

    private fun deleteRowImpl(sheet: Sheet, at: Int) {
        val newCells = HashMap<String, com.bdexcel.app.model.Cell>()
        for ((k, cell) in sheet.cells) {
            val p = CellRef.parseKey(k) ?: continue
            val (c, r) = p
            if (r == at) continue
            if (r > at) newCells[CellRef.key(c, r - 1)] = cell else newCells[k] = cell
        }
        sheet.cells.clear()
        sheet.cells.putAll(newCells)
        sheet.rowCount = maxOf(sheet.rowCount - 1, 1)
    }



    private fun insertCol() {
        pushUndo()
        val sheet = workbook.active()
        val at = grid.selectedCol
        val newCells = HashMap<String, com.bdexcel.app.model.Cell>()
        for ((k, cell) in sheet.cells) {
            val p = CellRef.parseKey(k) ?: continue
            val (c, r) = p
            if (c >= at) newCells[CellRef.key(c + 1, r)] = cell else newCells[k] = cell
        }
        sheet.cells.clear()
        sheet.cells.putAll(newCells)
        sheet.colCount = minOf(sheet.colCount + 1, 254)
        grid.refresh()
    }

    private fun deleteCol() {
        pushUndo()
        val sheet = workbook.active()
        val at = grid.selectedCol
        val newCells = HashMap<String, com.bdexcel.app.model.Cell>()
        for ((k, cell) in sheet.cells) {
            val p = CellRef.parseKey(k) ?: continue
            val (c, r) = p
            if (c == at) continue
            if (c > at) newCells[CellRef.key(c - 1, r)] = cell else newCells[k] = cell
        }
        sheet.cells.clear()
        sheet.cells.putAll(newCells)
        sheet.colCount = maxOf(sheet.colCount - 1, 1)
        grid.refresh()
    }

    // ------------------------- undo / redo -------------------------

    private fun pushUndo() {
        val snapshotBytes = workbook.active().snapshot()
        val snapshot = Sheet(workbook.active().name)
        snapshot.restore(snapshotBytes)
        undoStack.add(snapshot)
        if (undoStack.size > 30) undoStack.removeAt(0)
        redoStack.clear()
    }

    private fun undo() {
        if (undoStack.isEmpty()) {
            Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show()
            return
        }
        val previous = undoStack.removeAt(undoStack.size - 1)
        val currentBytes = workbook.active().snapshot()
        val current = Sheet(workbook.active().name)
        current.restore(currentBytes)
        redoStack.add(current)
        restoreActive(previous)
    }

    private fun redo() {
        if (redoStack.isEmpty()) {
            Toast.makeText(this, "Nothing to redo", Toast.LENGTH_SHORT).show()
            return
        }
        val next = redoStack.removeAt(redoStack.size - 1)
        val currentBytes = workbook.active().snapshot()
        val current = Sheet(workbook.active().name)
        current.restore(currentBytes)
        undoStack.add(current)
        restoreActive(next)
    }

    private fun restoreActive(snapshot: Sheet) {
        val target = workbook.active()
        target.restore(snapshot.snapshot())
        grid.refresh()
        syncFormulaBar()
    }

    // ------------------------- color picker -------------------------

    private fun showColorPicker(title: String) {
        val colorDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setNegativeButton("Cancel", null)
            .create()
        val rows = 4
        val cols = 5
        val gridLayout = LinearLayout(this)
        gridLayout.orientation = LinearLayout.VERTICAL
        val pad = (4 * resources.displayMetrics.density).toInt()
        val size = (48 * resources.displayMetrics.density).toInt()
        for (r in 0 until rows) {
            val rowLayout = LinearLayout(this)
            rowLayout.orientation = LinearLayout.HORIZONTAL
            for (c in 0 until cols) {
                val index = r * cols + c
                val swatch = View(this)
                swatch.layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(pad, pad, pad, pad) }
                val drawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(palette[index % palette.size])
                    setStroke(2, Color.DKGRAY)
                }
                swatch.background = drawable
                swatch.setOnClickListener {
                    applyColor(palette[index % palette.size])
                    colorDialog.dismiss()
                }
                rowLayout.addView(swatch)
            }
            gridLayout.addView(rowLayout)
        }
        colorDialog.setView(gridLayout)
        colorDialog.show()
    }

    private fun applyColor(color: Int) {
        applyStyle { st ->
            if (pendingColorTargetIsText) st.textColor = color else st.fillColor = color
        }
    }


    // ------------------------- sheet tabs -------------------------

    private fun renderTabs() {
        sheetTabs.removeAllViews()
        workbook.sheets.forEachIndexed { index, sheet ->
            val tab = TextView(this)
            tab.text = sheet.name
            tab.setPadding(24, 8, 24, 8)
            tab.gravity = Gravity.CENTER
            val active = index == workbook.current
            tab.setTextColor(if (active) Color.WHITE else Color.parseColor("#37474F"))
            tab.background = GradientDrawable().apply {
                cornerRadius = 12f
                setColor(if (active) Color.parseColor("#006A4E") else Color.parseColor("#EEEEEE"))
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(8, 4, 8, 4)
            tab.layoutParams = lp
            tab.setOnClickListener {
                workbook.current = index
                grid.sheet = workbook.active()
                renderTabs()
                grid.refresh()
                syncFormulaBar()
            }
            tab.setOnLongClickListener {
                showSheetOptions(index)
                true
            }
            sheetTabs.addView(tab)
        }
        val add = TextView(this)
        add.text = "+"
        add.textSize = 20f
        add.setPadding(24, 4, 24, 4)
        add.setTextColor(Color.parseColor("#006A4E"))
        add.setOnClickListener {
            pushUndo()
            workbook.sheets.add(Sheet("Sheet${workbook.sheets.size + 1}"))
            workbook.current = workbook.sheets.size - 1
            grid.sheet = workbook.active()
            renderTabs()
            grid.refresh()
        }
        sheetTabs.addView(add)
    }

    private fun showSheetOptions(index: Int) {
        val options = arrayOf("Rename", "Delete", "Clear contents")
        AlertDialog.Builder(this)
            .setTitle(workbook.sheets[index].name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> renameSheet(index)
                    1 -> deleteSheet(index)
                    2 -> {
                        pushUndo()
                        workbook.sheets[index].cells.clear()
                        grid.refresh()
                    }
                }
            }
            .show()
    }

    private fun renameSheet(index: Int) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setText(workbook.sheets[index].name)
        val container = FrameLayout(this)
        val pad = (20 * resources.displayMetrics.density).toInt()
        container.setPadding(pad, pad, pad, pad)
        container.addView(input)
        AlertDialog.Builder(this)
            .setTitle("Rename sheet")
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    pushUndo()
                    workbook.sheets[index].name = name
                    renderTabs()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteSheet(index: Int) {
        if (workbook.sheets.size <= 1) {
            Toast.makeText(this, "At least one sheet is required", Toast.LENGTH_SHORT).show()
            return
        }
        pushUndo()
        workbook.sheets.removeAt(index)
        if (workbook.current >= workbook.sheets.size) workbook.current = workbook.sheets.size - 1
        grid.sheet = workbook.active()
        renderTabs()
        grid.refresh()
    }
}
