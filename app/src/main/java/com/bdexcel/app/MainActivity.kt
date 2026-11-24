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
import com.bdexcel.app.io.XlsxCodec
import com.bdexcel.app.model.CellRef
import com.bdexcel.app.model.CellStyle
import com.bdexcel.app.model.Sheet
import com.bdexcel.app.model.Workbook
import com.bdexcel.app.view.SpreadsheetView
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class MainActivity : AppCompatActivity(), SpreadsheetView.Listener {

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

    private val projectFile: File
        get() = File(filesDir, "project.json")

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

        loadProject()
        if (workbook.sheets.isEmpty()) workbook.sheets.add(Sheet("Sheet1"))
        workbook.current = workbook.current.coerceIn(0, workbook.sheets.size - 1)
        grid.sheet = workbook.active()

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
        saveProjectLocally()
        super.onPause()
    }

    // ------------------------- persistence -------------------------

    private fun loadProject() {
        try {
            if (projectFile.exists()) workbook.fromJson(projectFile.readBytes())
        } catch (e: Exception) {
            Toast.makeText(this, "Could not load saved workbook", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveProjectLocally() {
        try {
            projectFile.writeBytes(workbook.toJson())
        } catch (e: Exception) {
            // storage full — ignore
        }
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
            .setTitle("BD Excel 1.0")
            .setMessage("A spreadsheet built for Bangladesh 🇧🇩\n\n• Formulas, formatting, colors\n• CSV & XLSX import/export\n• Pinch zoom, smooth scrolling\n• Offline & private")
            .setPositiveButton("OK", null)
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
            Toast.makeText(this, "Image saved as PNG", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Image save failed: ${e.message}", Toast.LENGTH_LONG).show()
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
