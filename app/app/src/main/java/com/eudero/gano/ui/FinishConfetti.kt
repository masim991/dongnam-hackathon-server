package com.eudero.gano.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Two short bursts from the edges of the finish card. */
internal class FinishConfetti(context: Context) : View(context) {
    private data class Piece(val left: Boolean, val speed: Float, val lift: Float, val delay: Float, val size: Float,
                             val rotation: Float, val spin: Float, val color: Int, val sparkle: Boolean)
    private val density = resources.displayMetrics.density
    private val colors = intArrayOf(0xFFB9FA03.toInt(), 0xFFF4FFAE.toInt(), 0xFF70E6D4.toInt(), 0xFFE1D3FF.toInt(), 0xFFFFFFFF.toInt())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val diamond = Path().apply { moveTo(0f,-1f); lineTo(.34f,-.28f); lineTo(.9f,0f); lineTo(.34f,.28f); lineTo(0f,1f); lineTo(-.34f,.28f); lineTo(-.9f,0f); lineTo(-.34f,-.28f); close() }
    private var progress = 0f
    private var animation: ValueAnimator? = null
    private val pieces = with(Random(207)) {
        List(72) { i ->
            Piece(i % 2 == 0, .22f + nextFloat() * .50f, .45f + nextFloat() * .48f, nextFloat() * .19f,
                (2.3f + nextFloat() * 4.3f) * density, nextFloat() * 360, (nextFloat() - .5f) * 770,
                colors[i % colors.size], i % 5 == 0)
        }
    }

    fun burst() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        animation = ValueAnimator.ofFloat(0f, 1.48f).apply {
            duration = 1600
            interpolator = LinearInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        for (piece in pieces) {
            val t = progress - piece.delay
            if (t !in 0f..1.28f) continue
            val x = if (piece.left) width * (.05f + t * piece.speed) else width * (.95f - t * piece.speed)
            val y = height * (.78f - t * piece.lift + .64f * t * t)
            val opacity = ((1.28f - t) / .44f).coerceIn(0f, 1f)
            val flip = (.3f + .7f * kotlin.math.abs(cos(t * PI * 3.1)).toFloat())
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(piece.rotation + t * piece.spin)
            paint.color = piece.color
            paint.alpha = (255 * opacity).toInt()
            if (piece.sparkle) {
                canvas.scale(piece.size * 1.45f, piece.size * 1.45f)
                canvas.drawPath(diamond, paint)
            } else {
                val size = piece.size
                canvas.drawRoundRect(-size * flip / 2, -size, size * flip / 2, size,
                    size * .21f, size * .21f, paint)
            }
            canvas.restore()
        }
    }

    override fun onDetachedFromWindow() {
        animation?.cancel()
        animation = null
        super.onDetachedFromWindow()
    }
}
