package com.hackathon.assistant.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Jarvis mark: an assistant that lives in your phone and acts on what you ask.
 *  - the orb is Jarvis, always there (glossy, in the Jarvis spectrum);
 *  - the four-point spark at its heart is the intelligence acting for you; it breathes with
 *    [energy] (your voice while listening, Jarvis' words while speaking);
 *  - the orbit and its satellite circle the orb: always on, always around you. It turns faster
 *    while [busy] (thinking).
 * Everything is drawn inside the bounds, so it never clips.
 */
@Composable
fun JarvisLogo(modifier: Modifier = Modifier, energy: Float = 0f, busy: Boolean = false, colors: List<Color> = JarvisSpectrum) {
    val t = rememberInfiniteTransition(label = "logo")
    val orbit by t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (busy) 1_100 else 4_200, easing = LinearEasing)), label = "orbit")
    val shimmer by t.animateFloat(0f, 360f, infiniteRepeatable(tween(7_000, easing = LinearEasing)), label = "shimmer")

    Canvas(modifier.semantics { contentDescription = "Jarvis" }) {
        val s = size.minDimension
        val c = Offset(size.width / 2, size.height / 2)
        val ringStroke = s * 0.055f
        val ringRadius = s / 2 - ringStroke / 2 - s * 0.03f   // inset: stroke and satellite stay inside
        val orbRadius = s * 0.33f

        // Orbit: a faint full ring, a brighter trailing arc, and the satellite at its head.
        drawCircle(colors[1].copy(alpha = 0.18f), ringRadius, c, style = Stroke(ringStroke))
        rotate(orbit, c) {
            drawArc(
                brush = Brush.sweepGradient(listOf(Color.Transparent, colors[0].copy(alpha = 0.2f), colors[2]), c),
                startAngle = 200f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(c.x - ringRadius, c.y - ringRadius), size = Size(ringRadius * 2, ringRadius * 2),
                style = Stroke(ringStroke, cap = StrokeCap.Round),
            )
            val head = Math.toRadians(340.0)
            val dot = Offset(c.x + ringRadius * cos(head).toFloat(), c.y + ringRadius * sin(head).toFloat())
            drawCircle(Color.White, ringStroke * 1.25f, dot)
            drawCircle(colors[2], ringStroke * 0.85f, dot)
        }

        // Orb: spectrum body turning slowly, a soft rim light, and a glossy highlight.
        rotate(shimmer, c) {
            drawCircle(Brush.sweepGradient(colors + colors.first(), c), orbRadius, c)
        }
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.0f), Color.Black.copy(alpha = 0.18f)), c, orbRadius), orbRadius, c)
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent), Offset(c.x - orbRadius * 0.35f, c.y - orbRadius * 0.45f), orbRadius * 0.7f),
            orbRadius, c,
        )

        // Spark: the four-point star, breathing with the voice.
        val sparkScale = 0.86f + 0.3f * energy.coerceIn(0f, 1f)
        scale(sparkScale, c) { drawSpark(c, orbRadius * 0.62f) }
    }
}

/** A four-point spark with softly concave sides. */
private fun DrawScope.drawSpark(c: Offset, r: Float) {
    val k = r * 0.18f // how far the sides pinch toward the centre
    val p = Path().apply {
        moveTo(c.x, c.y - r)
        quadraticTo(c.x + k, c.y - k, c.x + r, c.y)
        quadraticTo(c.x + k, c.y + k, c.x, c.y + r)
        quadraticTo(c.x - k, c.y + k, c.x - r, c.y)
        quadraticTo(c.x - k, c.y - k, c.x, c.y - r)
        close()
    }
    drawPath(p, Color.White)
    drawCircle(Color.White.copy(alpha = 0.35f), r * 0.42f, c)
}

/** The Jarvis colours: waveform, aurora, outline and logo all use these. */
val JarvisSpectrum = listOf(Color(0xFF4C7DFF), Color(0xFF8E5CF7), Color(0xFFE9508F), Color(0xFF19B3A6))
