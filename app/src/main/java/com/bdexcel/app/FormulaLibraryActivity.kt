package com.bdexcel.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Bangla tutorial library: 100+ formulas with explanations, examples & copy support. */
class FormulaLibraryActivity : AppCompatActivity() {

    private var showing = ArrayList(FormulaLibrary.all)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_formula_library)

        val search = findViewById<EditText>(R.id.searchBox)
        val list = findViewById<ListView>(R.id.formulaList)
        val count = findViewById<TextView>(R.id.formulaCount)

        fun render() {
            count.text = "মোট ${showing.size} টি ফরমুলা"
            list.adapter = object : android.widget.BaseAdapter() {
                override fun getCount() = showing.size
                override fun getItem(position: Int) = showing[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = convertView ?: layoutInflater.inflate(R.layout.item_formula, parent, false)
                    val doc = showing[position]
                    v.findViewById<TextView>(R.id.fName).text = doc.name
                    v.findViewById<TextView>(R.id.fCategory).text = doc.category
                    v.findViewById<TextView>(R.id.fSyntax).text = doc.syntax
                    v.findViewById<TextView>(R.id.fBangla).text = doc.bangla
                    return v
                }
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = (s ?: "").toString().trim().lowercase()
                showing = if (q.isEmpty()) {
                    ArrayList(FormulaLibrary.all)
                } else {
                    ArrayList(FormulaLibrary.all.filter {
                        it.name.lowercase().contains(q) ||
                            it.bangla.contains(q) ||
                            it.category.contains(q) ||
                            it.syntax.lowercase().contains(q) ||
                            it.example.lowercase().contains(q)
                    })
                }
                render()
            }
        })

        list.setOnItemClickListener { _, _, position, _ ->
            showDetail(showing[position])
        }

        render()
    }

    private fun showDetail(doc: FormulaDoc) {
        val message = """
            🧮 কাজ: ${doc.bangla}

            📐 ব্যবহার: ${doc.syntax}

            ✅ উদাহরণ: ${doc.example}

            নিচের বাটনে চাপ দিলে ফরমুলাটি কপি হয়ে যাবে — তারপর যেকোনো সেলে পেস্ট করে ব্যবহার করুন।
        """.trimIndent()

        val dialog = AlertDialog.Builder(this)
            .setTitle("📘 " + doc.name + "  [" + doc.category + "]")
            .setMessage(message)
            .setPositiveButton("📋 ফরমুলা কপি করুন") { _, _ ->
                copy(doc.syntax)
            }
            .setNegativeButton("বন্ধ করুন", null)
            .create()
        dialog.show()
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("BD Excel formula", text))
        Toast.makeText(this, "কপি হয়েছে ✓ এখন সেলে পেস্ট করুন", Toast.LENGTH_SHORT).show()
    }
}
