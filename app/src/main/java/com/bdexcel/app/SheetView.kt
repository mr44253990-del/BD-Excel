package com.bdexcel.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.view.GestureDetectorCompat
import kotlin.math.abs

/**
 * কাস্টম জুমযোগ্য গ্রিড ভিউ — এক্সেলের শিটের মত। যেকোন জায়গায় pinch-zoom করা যাবে।
 * প্রতিটি সেলে ভ্যালু, ফরমুলা, কালার, অ্যালাইনমেন্ট দেখায়।
 */
class SheetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var sheet: Sheet? = null
    private var selectedCell: CellCoordinate? = null

    // জুম ও প্যান
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetectorCompat(context, GestureListener())
    private var scaleFactor = 1f
    private var startX = 0f
    private var startY = 0f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    // রেন্ডারিং পেন্ট
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorPalette.gridLine; style = Paint.Style.STROKE; strokeWidth = 1f }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = 16f; antiAlias = true
    }
    private val selectedPaint = Paint().apply { color = ColorPalette.selectedCell; alpha = 120; style = Paint.Style.FILL }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorPalette.primary; textSize = 16f; textAlign = Paint.Align.CENTER; antiAlias = true
    }

    private val colHeaderHeight = 40f
    private val rowHeaderWidth = 50f
    private val defaultColWidth = 100f
    private val defaultRowHeight = 40f

    var onCellClick: ((CellCoordinate) -> Unit)? = null
    var onCellLongClick: ((CellCoordinate) -> Boolean)? = null

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

    fun setSheet(sheet: Sheet?) {
        this.sheet = sheet
        invalidate()
    }

    fun setSelectedCell(coord: CellCoordinate?) {
        selectedCell = coord
        invalidate()
    }

    fun getSelectedCell(): CellCoordinate? = selectedCell

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.rawX
                lastTouchY = event.rawY
                isDragging = false
                startX = offsetX
                startY = offsetY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastTouchX
                val dy = event.rawY - lastTouchY
                if (abs(dx) > 5 || abs(dy) > 5) isDragging = true
                offsetX = startX + dx / scaleFactor
                offsetY = startY + dy / scaleFactor
                lastTouchX = event.rawX
                lastTouchY = event.rawY
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    val tapped = coordAt(event.rawX, event.rawY)
                    if (tapped != null && sheet != null) {
                        selectedCell = tapped
                        invalidate()
                        onCellClick?.invoke(tapped)
                    }
                }
                isDragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.scale(scaleFactor, scaleFactor)
        canvas.translate(offsetX, offsetY)

        val width = (measuredWidth / scaleFactor).coerceAtLeast(1).toInt()
        val height = (measuredHeight / scaleFactor).coerceAtLeast(1).toInt()

        val s = sheet ?: return
        val colW = width.coerceAtLeast(1) / s.columnCount.coerceAtLeast(1).toFloat()
        val rowH = (height - colHeaderHeight).coerceAtLeast(1) / s.rowCount.coerceAtLeast(1).toFloat()

        // ব্যাকগ্রাউন্ড
        bgPaint.color = Color.WHITE
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // কলাম হেডার (A, B, C...)
        headerPaint.color = ColorPalette.headerBg
        canvas.drawRect(rowHeaderWidth, 0f, width.toFloat(), colHeaderHeight, bgPaint)
        headerPaint.color = Color.BLACK
        for (c in 0 until s.columnCount) {
            val x = rowHeaderWidth + c * colW + colW / 2
            canvas.drawText(CellCoordinate(0, c + 1).toAlpha(), x, 26f, headerPaint)
            gridPaint.color = ColorPalette.gridLine
            canvas.drawLine(rowHeaderWidth + c * colW, 0f, rowHeaderWidth + c * colW, height.toFloat(), gridPaint)
        }
        // রো নম্বর (1, 2, 3...)
        for (r in 0 until s.rowCount) {
            val y = colHeaderHeight + r * rowH + rowH / 2 + 6
            canvas.drawText((r + 1).toString(), rowHeaderWidth - 6f, y, headerPaint)
            canvas.drawLine(0f, colHeaderHeight + r * rowH, width.toFloat(), colHeaderHeight + r * rowH, gridPaint)
        }
        canvas.drawLine(rowHeaderWidth, 0f, rowHeaderWidth, height.toFloat(), gridPaint)
        canvas.drawLine(0f, colHeaderHeight, width.toFloat(), colHeaderHeight, gridPaint)

        // সেল কন্টেন্ট
        for (cell in s.cellList()) {
            val left = rowHeaderWidth + cell.column * colW
            val top = colHeaderHeight + cell.row * rowH
            val right = left + colW
            val bottom = top + rowH

            // সেল ব্যাকগ্রাউন্ড কালার
            if (cell.backgroundColor != 0) {
                bgPaint.color = cell.backgroundColor
                canvas.drawRect(left, top, right, bottom, bgPaint)
            }

            // সেল সিলেকশন হাইলাইট
            if (selectedCell != null && selectedCell!!.row == cell.row && selectedCell!!.column == cell.column) {
                canvas.drawRect(left, top, right, bottom, selectedPaint)
                gridPaint.color = ColorPalette.primaryDark
                gridPaint.strokeWidth = 3f
                canvas.drawRect(left, top, right, bottom, gridPaint)
                gridPaint.strokeWidth = 1f
            }

            // সেল টেক্সট
            val text = if (cell.formula.isNotEmpty()) cell.formula else cell.value
            if (text.isNotEmpty()) {
                textPaint.color = cell.textColor
                textPaint.textSize = cell.fontSize.coerceAtLeast(10f)
                textPaint.textAlign = when (cell.horizontalAlignment) {
                    1 -> Paint.Align.CENTER
                    2 -> Paint.Align.RIGHT
                    else -> Paint.Align.LEFT
                }
                val x = when (cell.horizontalAlignment) {
                    1 -> (left + right) / 2
                    2 -> right - 4f
                    else -> left + 4f
                }
                canvas.drawText(text, x, top + cell.fontSize.coerceAtLeast(10f) - 2f, textPaint)
            }
        }

        canvas.restore()
    }

    private fun coordAt(rawX: Float, rawY: Float): CellCoordinate? {
        val s = sheet ?: return null
        val width = (measuredWidth / scaleFactor).coerceAtLeast(1).toInt()
        val height = (measuredHeight / scaleFactor).coerceAtLeast(1).toInt()
        val colW = width.coerceAtLeast(1) / s.columnCount.coerceAtLeast(1).toFloat()
        val rowH = (height - colHeaderHeight).coerceAtLeast(1) / s.rowCount.coerceAtLeast(1).toFloat()
        val x = ((rawX / scaleFactor) - offsetX - rowHeaderWidth) / colW
        val y = ((rawY / scaleFactor) - offsetY - colHeaderHeight) / rowH
        if (x < 0 || y < 0 || x >= s.columnCount || y >= s.rowCount) return null
        return CellCoordinate(y.toInt() + 1, x.toInt() + 1)
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val prev = scaleFactor
            scaleFactor *= detector.scaleFactor
            scaleFactor = scaleFactor.coerceIn(0.5f, 4.0f)
            // জুম সেন্টার রкара Reyes প্যানে নাও跳ে তা Correction
            val cx = detector.focusX / scaleFactor
            val cy = detector.focusY / scaleFactor
            offsetX += (cx - detector.focusX / prev)
            offsetY += (cy - detector.focusY / prev)
            invalidate()
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
    }
}
