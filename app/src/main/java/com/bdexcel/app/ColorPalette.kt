package com.bdexcel.app

import android.graphics.Color

/**
 * 앱ের ফিক্সড কালার প্যালেট। সেভ কালার এবং এক্সপোর্ট ফটোতে ব্যবহার হয়।
 */
object ColorPalette {
    val primary = 0xFF006A4E // বাংলাদেশের লাল-সবুজ থিমের(prefix)
    val primaryDark = 0xFF004D35
    val accent = 0xFFF42A2A // লাল (BD ফ্ল্যাগের লাল)
    val surface = 0xFFFFFFFF
    val surfaceVariant = 0xFFF5F5F5
    val background = 0xFFFFFFFF
    val onSurface = 0xFF1A1A1A
    val onBackground = 0xFF1A1A1A
    val outline = 0xFFC4C4C4
    val gridLine = 0xFFBDBDBD
    val headerBg = 0xFFE8F5E9
    val selectedCell = 0xFF64FFDA
    val formulaBarBg = 0xFFFFFFFF

    /** ডার্ক মোড কালার। */
    object Dark {
        val surface = 0xFF121212
        val surfaceVariant = 0xFF1E1E1E
        val background = 0xFF121212
        val onSurface = 0xFFE0E0E0
        val onBackground = 0xFFE0E0E0
        val outline = 0xFF333333
        val gridLine = 0xFF333333
        val headerBg = 0xFF1B5E20
        val selectedCell = 0xFF00BF63
        val formulaBarBg = 0xFF1E1E1E
    }

    fun toHex(color: Int): String = String.format("#%08X", color)
    fun fromHex(hex: String): Int = Color.parseColor(hex)
}
