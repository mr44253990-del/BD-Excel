package com.bdexcel.app

import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * শিটকে ছবি (পিএনজি/jpg) হিসেবে এক্সপোর্ট করে গ্যালারিতে সেভ করে।
 */
class ImageExportManager(private val activity: Activity) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    fun exportSheetAsImage(
        sheet: Sheet,
        viewWidth: Int,
        viewHeight: Int,
        callback: (Uri?) -> Unit
    ) {
        executor.execute {
            try {
                val bitmap = renderSheetToBitmap(sheet, viewWidth.coerceAtLeast(400), viewHeight.coerceAtLeast(300))
                val uri = saveBitmapToGallery(activity, bitmap, "BD_Excel_${System.currentTimeMillis()}.png")
                activity.runOnUiThread { callback(uri) }
            } catch (e: Exception) {
                activity.runOnUiThread { callback(null) }
            }
        }
    }

    private fun renderSheetToBitmap(sheet: Sheet, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        textPaint.textSize = 18f
        textPaint.color = Color.BLACK

        val colW = width.coerceAtLeast(1) / sheet.columnCount.coerceAtLeast(1)
        val rowH = height.coerceAtLeast(1) / sheet.rowCount.coerceAtLeast(1)

        // গ্রিড লাইন
        paint.color = Color.LTGRAY
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        for (c in 0..sheet.columnCount) canvas.drawLine(c * colW, 0f, c * colW, height.toFloat(), paint)
        for (r in 0..sheet.rowCount) canvas.drawLine(0f, r * rowH, width.toFloat(), r * rowH.toFloat(), paint)

        // সেল কন্টেন্ট
        for (cell in sheet.cellList()) {
            val rect = Rect(cell.column * colW, cell.row * rowH, (cell.column + 1) * colW, (cell.row + 1) * rowH)
            if (cell.backgroundColor != 0) {
                paint.style = Paint.Style.FILL
                paint.color = cell.backgroundColor
                canvas.drawRect(rect, paint)
            }
            textPaint.color = cell.textColor
            textPaint.textSize = cell.fontSize.coerceAtLeast(10f)
            val align = when (cell.horizontalAlignment) {
                1 -> Paint.Align.CENTER
                2 -> Paint.Align.RIGHT
                else -> Paint.Align.LEFT
            }
            textPaint.textAlign = align
            val text = if (cell.formula.isNotEmpty()) cell.formula else cell.value
            if (text.isNotEmpty()) {
                canvas.drawText(text, rect.centerX().toFloat(), (rect.centerY().toFloat() + 8f), textPaint)
            }
        }

        // হেডার (কলাম লেটার, রো নম্বর)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 16f
        textPaint.color = Color.DKGRAY
        for (c in 0 until sheet.columnCount) {
            canvas.drawText(CellCoordinate(0, c + 1).toAlpha(), (c * colW + colW / 2).toFloat(), 20f, textPaint)
        }
        textPaint.textAlign = Paint.Align.RIGHT
        for (r in 0 until sheet.rowCount.coerceAtLeast(50)) {
            canvas.drawText((r + 1).toString(), (colW - 4).toFloat(), (r * rowH + rowH / 2 + 6).toFloat(), textPaint)
        }

        return bitmap
    }

    private fun saveBitmapToGallery(activity: Activity, bitmap: Bitmap, fileName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/BD Excel")
        }
        val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val uri = activity.contentResolver.insert(collection, values) ?: throw IOException("Failed to create MediaStore entry")
        activity.contentResolver.openOutputStream(uri, "w")?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: throw IOException("Failed to open output stream")
        return uri
    }

    fun shutdown() {
        executor.shutdown()
    }
}
