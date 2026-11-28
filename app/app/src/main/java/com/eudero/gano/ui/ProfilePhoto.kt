package com.eudero.gano.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.appcompat.widget.AppCompatImageView

/** Rounded photo frame with slightly bowed sides. */
internal class ProfilePhoto(context: Context, borderColor: Int) : AppCompatImageView(context) {
    private val frame = Path()
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = borderColor
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.8f
    }

    init {
        scaleType = ScaleType.CENTER_CROP
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = outline.strokeWidth
        val width = w - inset * 2
        val height = h - inset * 2
        frame.reset()
        if (width <= 0 || height <= 0) return
        frame.moveTo(inset + width * .5f, inset)
        frame.cubicTo(inset + width * .89f, inset, inset + width, inset + height * .11f, inset + width, inset + height * .5f)
        frame.cubicTo(inset + width, inset + height * .89f, inset + width * .89f, inset + height, inset + width * .5f, inset + height)
        frame.cubicTo(inset + width * .11f, inset + height, inset, inset + height * .89f, inset, inset + height * .5f)
        frame.cubicTo(inset, inset + height * .11f, inset + width * .11f, inset, inset + width * .5f, inset)
        frame.close()
    }

    override fun onDraw(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipPath(frame)
        super.onDraw(canvas)
        canvas.restoreToCount(save)
        canvas.drawPath(frame, outline)
    }
}
