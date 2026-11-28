package com.eudero.maplib

/** 핀(사람 글·경고) 표시 상태(데이터). */
class PinOverlay {
    var pins: List<Pin> = emptyList()
    var onGroupTap: ((List<Pin>) -> Unit)? = null
    var onPinTap: ((Pin) -> Unit)? = null
}

internal const val SINGLE_BUBBLE_WIDTH_DP = 48

/** 한 번 합성한 이미지 한 장이 사진과 꼬리를 함께 담는다. */
internal fun bubbleImage(context: android.content.Context, face: android.graphics.Bitmap?, color: Int,
                         widthDp: Int, warning: Boolean = false, count: Int? = null): android.graphics.Bitmap {
    val unit = widthDp * context.resources.displayMetrics.density / 40f
    val width = (40 * unit).toInt()
    val height = (52 * unit).toInt()
    val result = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(result)
    val shell = context.getDrawable(R.drawable.bubble_pin)!!.mutate()
    shell.colorFilter = android.graphics.LightingColorFilter(color, 0)
    shell.setBounds(0, 0, width, height)
    shell.draw(canvas)
    canvas.scale(unit, unit)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    if (face != null && !warning) {
        canvas.clipPath(android.graphics.Path().apply { addCircle(20f, 20f, 14.6f, android.graphics.Path.Direction.CW) })
        val side = minOf(face.width, face.height)
        val left = (face.width - side) / 2
        val top = (face.height - side) / 2
        canvas.drawBitmap(face, android.graphics.Rect(left, top, left + side, top + side),
            android.graphics.RectF(5.4f, 5.4f, 34.6f, 34.6f), paint)
    } else {
        paint.color = if (count != null) android.graphics.Color.WHITE else color
        paint.textAlign = android.graphics.Paint.Align.CENTER
        paint.typeface = MapFonts.bold
        val text = when { count == null -> "!"; warning -> "!$count"; else -> count.toString() }
        paint.textSize = if (count != null && text.length >= 3) 15f else 22f
        val textWidth = paint.measureText(text)
        if (textWidth > 27f) paint.textSize *= 27f / textWidth
        canvas.drawText(text, 20f, 20f - (paint.ascent() + paint.descent()) / 2f, paint)
    }
    return result
}
