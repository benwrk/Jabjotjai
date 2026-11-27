package com.benwrk.jabjotjai.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Icon
import androidx.core.content.ContextCompat
import com.benwrk.jabjotjai.R
import kotlin.math.roundToInt

/**
 * The capture notification's status-bar icon while a heart rate is current: the monitor's heart
 * with the strap's heart rate cut out of it. The digits are left transparent rather than drawn in
 * a colour, so the icon reads the same whatever single colour the status bar paints it in.
 */
object HeartRateIcon {

    /** The size of a notification's small icon. */
    private const val SIZE_DP = 24f

    // Where the digits go, in the heart's own 24-unit grid (res/drawable/ic_heart.xml): the
    // widest part of the heart, clear of its outline for numbers up to three digits.
    private const val CENTRE_X = 12f
    private const val CENTRE_Y = 11f
    private const val MAX_TEXT_HEIGHT = 8f
    private const val MAX_TEXT_WIDTH = 12f

    fun create(context: Context, bpm: Int): Icon {
        val sizePx = (SIZE_DP * context.resources.displayMetrics.density).roundToInt()
        val unit = sizePx / 24f
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        requireNotNull(ContextCompat.getDrawable(context, R.drawable.ic_heart)).apply {
            setBounds(0, 0, sizePx, sizePx)
            draw(canvas)
        }

        val text = bpm.toString()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            // Removes the heart wherever a digit is drawn, keeping the digits' smoothed edges.
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
        val bounds = Rect()
        paint.textSize = 100f
        paint.getTextBounds(text, 0, text.length, bounds)
        paint.textSize = 100f * minOf(MAX_TEXT_HEIGHT * unit / bounds.height(), MAX_TEXT_WIDTH * unit / bounds.width())
        paint.getTextBounds(text, 0, text.length, bounds)
        canvas.drawText(text, CENTRE_X * unit, CENTRE_Y * unit - bounds.exactCenterY(), paint)
        return Icon.createWithBitmap(bitmap)
    }
}
