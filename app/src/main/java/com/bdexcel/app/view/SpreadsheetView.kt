package com.bdexcel.app.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.OverScroller
import com.bdexcel.app.engine.Calculator
import com.bdexcel.app.model.CellStyle
import com.bdexcel.app.model.Sheet
import kotlin.math.max
import kotlin.math.min

class SpreadsheetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : android.view.View(context, attrs) {

    interface Listener {
        fun onCellTapped(col: Int, row: Int)
        fun onCellEditRequested(col: Int, row: Int)
        fun onSelectionChanged(col: Int, row: Int)
        fun onCellLongPress(col: Int, row: Int) {}
    }

    var listener: Listener? = null

    var sheet: Sheet? = null
        set(value) {
            field = value
            calc = value?.let { Calculator(it) }
            requestLayout()
            invalidate()
        }

    private var calc: Calculator? = null

    var selectedCol = 0
        private set
    var selectedRow = 0
        private set

    private val density = resources.displayMetrics.density
    private val baseColWidth = 96f * density
    private val baseRowHeight = 40f * density
    private val headerWidth = 44f * density
    private val headerHeight = 30f * density

    var zoom = 1f
        private set

    private var panX = 0f
    private var panY = 0f

    private val colWidth: Float get() = baseColWidth * zoom
    private val rowHeight: Float get() = baseRowHeight * zoom

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EEF2F0") }
    private val headerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#37474F")
        textAlign = Paint.Align.CENTER
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#006A4E")
        strokeWidth = 3f * density
    }
    private val selectionFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2200A45E")
    }
    private val clipRect = Rect()

    private val scroller = OverScroller(context)
    private var velocity: android.view.VelocityTracker? = null
    private var flingOnZoom = false

    /** Folder that stores cell images for the current workbook. */
    var imageDir: java.io.File? = null
    private val imageCache = HashMap<String, android.graphics.Bitmap?>()

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val f = detector.scaleFactor
            val newZoom = (zoom * f).coerceIn(0.25f, 4f)
            val applied = newZoom / zoom
            zoom = newZoom
            panX = (detector.focusX - headerWidth) - (detector.focusX - headerWidth - panX) * applied
            panY = (detector.focusY - headerHeight) - (detector.focusY - headerHeight - panY) * applied
            clampPan()
            if (!scroller.isFinished) scroller.forceFinished(true)
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            if (!scroller.isFinished) scroller.forceFinished(true)
            velocity?.recycle()
            velocity = android.view.VelocityTracker.obtain()
            velocity?.addMovement(e)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            hitTest(e.x, e.y)?.let { (c, r) ->
                if (c == selectedCol && r == selectedRow) {
                    listener?.onCellEditRequested(c, r)
                } else {
                    select(c, r)
                }
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            hitTest(e.x, e.y)?.let { (c, r) ->
                select(c, r)
                listener?.onCellEditRequested(c, r)
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            panX -= dx
            panY -= dy
            clampPan()
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.forceFinished(true)
            // Velocity sign: finger moving right => vx>0; content must keep moving right,
            // so pan grows in the direction of the finger velocity (NOT inverted).
            scroller.fling(
                panX.toInt(), panY.toInt(),
                vx.toInt(), vy.toInt(),
                minX().toInt(), 0,
                minY().toInt(), 0
            )
            postInvalidateOnAnimation()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            hitTest(e.x, e.y)?.let { (c, r) -> listener?.onCellLongPress(c, r) }
        }
    })

    init {
        isVerticalScrollBarEnabled = false
    }

    fun select(col: Int, row: Int) {
        selectedCol = col.coerceAtLeast(0)
        selectedRow = row.coerceAtLeast(0)
        listener?.onSelectionChanged(selectedCol, selectedRow)
        ensureVisible(col, row)
        invalidate()
    }

    fun zoomTo(z: Float) {
        zoom = z.coerceIn(0.25f, 4f)
        clampPan()
        invalidate()
    }

    fun setZoomSilently(z: Float) {
        zoom = z.coerceIn(0.25f, 4f)
        clampPan()
        invalidate()
    }

    fun refresh() {
        calc?.invalidate()
        invalidate()
    }

    /** Captures the currently visible grid as a bitmap (for image export). */
    fun captureBitmap(): android.graphics.Bitmap {
        val w = max(1, width)
        val h = max(1, height)
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        draw(canvas)
        return bitmap
    }

    private fun minX(): Float = min(0f, width - headerWidth - contentWidth())
    private fun minY(): Float = min(0f, height - headerHeight - contentHeight())
    private fun contentWidth(): Float = (sheet?.colCount ?: 26) * colWidth
    private fun contentHeight(): Float = (sheet?.rowCount ?: 100) * rowHeight

    private fun clampPan() {
        panX = panX.coerceIn(minX(), 0f)
        panY = panY.coerceIn(minY(), 0f)
    }

    private fun ensureVisible(col: Int, row: Int) {
        val x0 = headerWidth + panX + col * colWidth
        val y0 = headerHeight + panY + row * rowHeight
        if (x0 < headerWidth) panX += headerWidth - x0
        if (y0 < headerHeight) panY += headerHeight - y0
        val x1 = x0 + colWidth
        val y1 = y0 + rowHeight
        if (x1 > width) panX -= x1 - width
        if (y1 > height) panY -= y1 - height
        clampPan()
        invalidate()
    }

    private fun hitTest(x: Float, y: Float): Pair<Int, Int>? {
        val s = sheet ?: return null
        if (x < headerWidth || y < headerHeight) return null
        val col = ((x - headerWidth - panX) / colWidth).toInt()
        val row = ((y - headerHeight - panY) / rowHeight).toInt()
        if (col < 0 || row < 0 || col >= s.colCount || row >= s.rowCount) return null
        return Pair(col, row)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampPan()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            panX = scroller.currX.toFloat()
            panY = scroller.currY.toFloat()
            clampPan()
            postInvalidateOnAnimation()
        }
        super.computeScroll()
    }

    override fun onDraw(canvas: Canvas) {
        val s = sheet ?: return
        val bgColor = Color.WHITE
        canvas.drawColor(bgColor)
        getClipBounds(clipRect)

        val firstVisibleCol = max(0, ((-panX) / colWidth).toInt() - 1)
        val lastVisibleCol = min(s.colCount - 1, ((width - panX) / colWidth).toInt() + 1)
        val firstVisibleRow = max(0, ((-panY) / rowHeight).toInt() - 1)
        val lastVisibleRow = min(s.rowCount - 1, ((height - panY) / rowHeight).toInt() + 1)

        // cells
        for (r in firstVisibleRow..lastVisibleRow) {
            val y = headerHeight + panY + r * rowHeight
            for (c in firstVisibleCol..lastVisibleCol) {
                val x = headerWidth + panX + c * colWidth
                val cell = s.cellOrNull(c, r) ?: continue
                cell.style.fillColor?.let {
                    fillPaint.color = it
                    canvas.drawRect(x, y, x + colWidth, y + rowHeight, fillPaint)
                }
                if (c == selectedCol && r == selectedRow) {
                    canvas.drawRect(x, y, x + colWidth, y + rowHeight, selectionFill)
                }
                if (cell.image != null) {
                    drawCellImage(canvas, cell.image!!, x, y)
                }
                val text = calc?.displayFor(c, r) ?: cell.raw
                if (text.isNotEmpty()) {
                    drawCellText(canvas, text, cell.style, x, y)
                }
            }
        }

        // grid lines
        linePaint.color = Color.parseColor("#D8D8D8")
        for (r in firstVisibleRow..lastVisibleRow + 1) {
            val y = headerHeight + panY + r * rowHeight
            canvas.drawLine(headerWidth, y, width.toFloat(), y, linePaint)
        }
        for (c in firstVisibleCol..lastVisibleCol + 1) {
            val x = headerWidth + panX + c * colWidth
            canvas.drawLine(x, headerHeight, x, height.toFloat(), linePaint)
        }

        // top header
        canvas.drawRect(0f, 0f, width.toFloat(), headerHeight, headerFill)
        headerText.textSize = (if (zoom > 1.6f) 12f * zoom * 0.8f else 12f) * density
        for (c in firstVisibleCol..lastVisibleCol) {
            val x = headerWidth + panX + c * colWidth
            canvas.drawRect(x, 0f, x + colWidth, headerHeight, headerFill)
            if (c == selectedCol) {
                fillPaint.color = Color.parseColor("#CC006A4E")
                canvas.drawRect(x, 0f, x + colWidth, headerHeight, fillPaint)
                headerText.color = Color.WHITE
            } else {
                headerText.color = Color.parseColor("#37474F")
            }
            val label = com.bdexcel.app.model.CellRef.colLetters(c)
            val ty = headerHeight / 2 - (headerText.descent() + headerText.ascent()) / 2
            canvas.drawText(label, x + colWidth / 2, ty, headerText)
        }

        // left header
        canvas.drawRect(0f, 0f, headerWidth, height.toFloat(), headerFill)
        for (r in firstVisibleRow..lastVisibleRow) {
            val y = headerHeight + panY + r * rowHeight
            canvas.drawRect(0f, y, headerWidth, y + rowHeight, headerFill)
            if (r == selectedRow) {
                fillPaint.color = Color.parseColor("#CC006A4E")
                canvas.drawRect(0f, y, headerWidth, y + rowHeight, fillPaint)
                headerText.color = Color.WHITE
            } else {
                headerText.color = Color.parseColor("#37474F")
            }
            canvas.drawText((r + 1).toString(), headerWidth / 2, y + rowHeight / 2 - (headerText.descent() + headerText.ascent()) / 2, headerText)
        }

        // corner
        canvas.drawRect(0f, 0f, headerWidth, headerHeight, headerFill)
        linePaint.color = Color.parseColor("#B0BEC5")
        canvas.drawLine(0f, headerHeight, width.toFloat(), headerHeight, linePaint)
        canvas.drawLine(headerWidth, 0f, headerWidth, height.toFloat(), linePaint)

        // selection border
        val sx = headerWidth + panX + selectedCol * colWidth
        val sy = headerHeight + panY + selectedRow * rowHeight
        canvas.drawRect(sx, sy, sx + colWidth, sy + rowHeight, selectionPaint)
    }

    private val textBounds = Rect()

    /** Decodes (and caches) a cell image; returns null when unavailable. */
    fun imageFor(name: String): android.graphics.Bitmap? {
        if (imageCache.containsKey(name)) return imageCache[name]
        val dir = imageDir ?: return null
        val file = java.io.File(dir, name)
        val bmp = try {
            if (file.exists()) {
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
            } else null
        } catch (e: Exception) {
            null
        }
        imageCache[name] = bmp
        return bmp
    }

    fun clearImageCache() {
        imageCache.clear()
    }

    private fun drawCellImage(canvas: Canvas, name: String, x: Float, y: Float) {
        val bmp = imageFor(name) ?: return
        val cw = colWidth - 6f * density
        val ch = rowHeight - 6f * density
        if (cw <= 0 || ch <= 0) return
        val scale = minOf(cw / bmp.width, ch / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        val left = x + (colWidth - w) / 2
        val top = y + (rowHeight - h) / 2
        canvas.save()
        canvas.clipRect(x, y, x + colWidth, y + rowHeight)
        canvas.drawBitmap(bmp, null, android.graphics.RectF(left, top, left + w, top + h), fillPaint)
        canvas.restore()
    }

    private fun drawCellText(canvas: Canvas, text: String, style: CellStyle, x: Float, y: Float) {
        textPaint.textSize = 13f * density * (if (zoom in 0.75f..1.5f) 1f else zoom.coerceIn(0.85f, 1.6f))
        textPaint.typeface = when {
            style.bold && style.italic -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
            style.bold -> Typeface.DEFAULT_BOLD
            style.italic -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            else -> Typeface.DEFAULT
        }
        textPaint.color = style.textColor ?: Color.parseColor("#202124")
        textPaint.getTextBounds(text, 0, text.length, textBounds)
        val textW = textBounds.width()
        val cellW = colWidth - 8f * density
        val align = when (style.align) {
            CellStyle.ALIGN_LEFT -> Paint.Align.LEFT
            CellStyle.ALIGN_CENTER -> Paint.Align.CENTER
            CellStyle.ALIGN_RIGHT -> Paint.Align.RIGHT
            else -> {
                if (text.toDoubleOrNull() != null || (text.length > 1 && text.startsWith("="))) Paint.Align.RIGHT
                else Paint.Align.LEFT
            }
        }
        textPaint.textAlign = align
        val tx = when (align) {
            Paint.Align.LEFT -> x + 4f * density
            Paint.Align.CENTER -> x + colWidth / 2
            else -> x + colWidth - 4f * density
        }
        val ty = y + rowHeight / 2 - (textPaint.descent() + textPaint.ascent()) / 2
        val saveCount = canvas.save()
        canvas.clipRect(x, y, x + colWidth, y + rowHeight)
        if (textW > cellW) {
            val ratio = (cellW / textW).coerceAtMost(1f)
            val prevAlign = textPaint.textAlign
            textPaint.textAlign = Paint.Align.LEFT
            canvas.save()
            canvas.translate(x + 4f * density, ty)
            canvas.scale(ratio, 1f)
            canvas.drawText(text, 0f, 0f, textPaint)
            canvas.restore()
            textPaint.textAlign = prevAlign
        } else {
            canvas.drawText(text, tx, ty, textPaint)
        }
        canvas.restoreToCount(saveCount)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        velocity?.addMovement(event)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            velocity?.recycle()
            velocity = null
        }
        return true
    }
}
