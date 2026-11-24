package com.bdexcel.app

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bdexcel.app.io.CsvCodec
import com.bdexcel.app.io.Docs
import com.bdexcel.app.io.PdfCodec
import com.bdexcel.app.io.XlsxCodec
import com.bdexcel.app.model.Sheet
import com.bdexcel.app.model.Workbook
import java.io.ByteArrayInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Home / directory screen: lists every saved sheet and lets you manage them. */
class HomeActivity : AppCompatActivity() {

    private lateinit var list: ListView
    private lateinit var emptyView: TextView
    private val docs = ArrayList<File>()
    private val meta = ArrayList<Workbook>()

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importToNewDoc(uri)
        }

    private val exportCsvLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) exportPending(uri, "csv")
        }

    private val exportXlsxLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(XlsxCodec.mimeTypeFor("a.xlsx"))) { uri ->
            if (uri != null) exportPending(uri, "xlsx")
        }

    private val exportPdfLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri != null) exportPending(uri, "pdf")
        }

    private var pendingExport: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        list = findViewById(R.id.docList)
        emptyView = findViewById(R.id.emptyView)

        findViewById<View>(R.id.fabNew).setOnClickListener {
            openDoc(Docs.create(this))
        }
        findViewById<View>(R.id.bnImport).setOnClickListener {
            importLauncher.launch(arrayOf("*/*"))
        }
        findViewById<View>(R.id.bnAbout).setOnClickListener { showAbout() }

        list.setOnItemClickListener { _, _, position, _ ->
            openDoc(docs[position])
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            showDocOptions(position)
            true
        }

        handleViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun handleViewIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        importToNewDoc(uri)
    }

    // ------------------------- listing -------------------------

    private fun refresh() {
        docs.clear()
        meta.clear()
        docs.addAll(Docs.all(this))
        if (docs.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            list.visibility = View.GONE
        } else {
            emptyView.visibility = View.GONE
            list.visibility = View.VISIBLE
        }
        list.adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = docs.size
            override fun getItem(position: Int) = docs[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(R.layout.item_doc, parent, false)
                val wb = Docs.readWorkbook(docs[position])
                val nameView = v.findViewById<TextView>(R.id.docName)
                val metaView = v.findViewById<TextView>(R.id.docMeta)
                nameView.text = "📄 " + wb.name.ifBlank { Docs.defaultName(position + 1) }
                val cells = wb.sheets.sumOf { it.cells.size }
                val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
                metaView.text = "${wb.sheets.size} টি শিট • $cells সেল • ${fmt.format(Date(docs[position].lastModified()))}"
                v.findViewById<ImageButton>(R.id.docMore).setOnClickListener { showDocOptions(position) }
                return v
            }
        }
    }

    private fun openDoc(dir: File) {
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra(MainActivity.EXTRA_DIR, dir.absolutePath)
        startActivity(intent)
    }

    // ------------------------- import -------------------------

    private fun importToNewDoc(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw Exception("ফাইল পড়া যায়নি")
            val displayName = queryName(uri) ?: "Imported"
            val wb = Workbook()
            val name = displayName.substringBeforeLast('.')
            when {
                displayName.endsWith(".xlsx", true) || bytes.size > 4 && bytes[0].toInt() == 'P'.toInt() && bytes[1].toInt() == 'K'.toInt() ->
                    XlsxCodec.read(ByteArrayInputStream(bytes), wb)
                displayName.endsWith(".json", true) -> wb.fromJson(bytes)
                displayName.endsWith(".csv", true) || bytes.firstOrNull { it == '{'.code.toByte() } == null -> {
                    val sheet = Sheet(name.ifBlank { "Sheet1" })
                    CsvCodec.read(ByteArrayInputStream(bytes), sheet)
                    wb.sheets.add(sheet)
                }
                else -> wb.fromJson(bytes)
            }
            wb.name = name.ifBlank { "Imported" }
            val dir = Docs.create(this)
            Docs.writeWorkbook(dir, wb)
            refresh()
            Toast.makeText(this, "ইমপোর্ট সম্পন্ন ✓", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "ইমপোর্ট ব্যর্থ: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
    } catch (e: Exception) {
        null
    }

    // ------------------------- per-document options -------------------------

    private fun showDocOptions(position: Int) {
        val dir = docs[position]
        val wb = Docs.readWorkbook(dir)
        val options = arrayOf("✏️ নাম বদলান", "📤 XLSX এক্সপোর্ট", "📤 CSV এক্সপোর্ট", "📤 PDF এক্সপোর্ট", "🗑 ডিলিট")
        AlertDialog.Builder(this)
            .setTitle(wb.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> renameDoc(position)
                    1 -> { pendingExport = dir; exportXlsxLauncher.launch(exportName(dir, "xlsx")) }
                    2 -> { pendingExport = dir; exportCsvLauncher.launch(exportName(dir, "csv")) }
                    3 -> { pendingExport = dir; exportPdfLauncher.launch(exportName(dir, "pdf")) }
                    4 -> confirmDelete(position)
                }
            }
            .show()
    }

    private fun exportName(dir: File, ext: String): String {
        val wb = Docs.readWorkbook(dir)
        return wb.name.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "BDExcel" } + ".$ext"
    }

    private fun exportPending(uri: Uri, kind: String) {
        val dir = pendingExport ?: return
        try {
            val wb = Docs.readWorkbook(dir)
            contentResolver.openOutputStream(uri, "w")?.use { out ->
                when (kind) {
                    "csv" -> CsvCodec.write(wb.active(), out)
                    "pdf" -> PdfCodec.write(wb.active(), out)
                    else -> XlsxCodec.write(wb, out)
                }
            }
            Toast.makeText(this, "এক্সপোর্ট সম্পন্ন ✓", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "এক্সপোর্ট ব্যর্থ: ${e.message}", Toast.LENGTH_LONG).show()
        }
        pendingExport = null
    }

    private fun renameDoc(position: Int) {
        val input = EditText(this)
        input.setText(Docs.readWorkbook(docs[position]).name)
        val container = FrameLayout(this)
        val pad = (20 * resources.displayMetrics.density).toInt()
        container.setPadding(pad, pad, pad, pad)
        container.addView(input)
        AlertDialog.Builder(this)
            .setTitle("নাম বদলান")
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val wb = Docs.readWorkbook(docs[position])
                    wb.name = name
                    Docs.writeWorkbook(docs[position], wb)
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(position: Int) {
        AlertDialog.Builder(this)
            .setTitle("ডিলিট করবেন?")
            .setMessage("\"${Docs.readWorkbook(docs[position]).name}\" শিটটি স্থায়ীভাবে মুছে যাবে।")
            .setPositiveButton("ডিলিট") { _, _ ->
                Docs.delete(docs[position])
                refresh()
                Toast.makeText(this, "ডিলিট হয়েছে", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("BD Excel 🇧🇩")
            .setMessage("ডেভেলপার: রাকিবুল\n\nআমি অ্যাপ্লিকেশন ডেভেলপ করতে ভালোবাসি ❤️\n\nBD Excel — সম্পূর্ণ অফলাইন স্প্রেডশিট অ্যাপ। Google Sheets ও MS Excel এর ফাইল ওপেন করা যায়, আর XLSX/CSV/PDF/PNG-তে এক্সপোর্ট করা যায়।")
            .setPositiveButton("OK", null)
            .show()
    }
}
