package com.example.polaralign.ui.main

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

private const val CHART_HALF_RANGE_DEGREES = 8.0
private const val TRAIL_LEN = 28
private const val TRAIL_SAMPLE_NS = 40_000_000L
private const val TRAIL_SPAN_NS = 1_000_000_000L
private const val TRAIL_MIN_STEP = 0.004f
private const val CELEBRATION_MS = 800
private const val REALIGN_GAP_MS = 1_500L
private const val SPARKLE_COUNT = 8

private val TAU = (PI * 2.0).toFloat()
private val HALF_PI = (PI / 2.0).toFloat()
private val EIGHTH = (PI / 4.0).toFloat()

@Composable
internal fun PointingChart(azimuthError: Double, altitudeError: Double, aligned: Boolean) {
  val primary = MaterialTheme.colorScheme.primary
  val outline = MaterialTheme.colorScheme.outline
  val onBackground = MaterialTheme.colorScheme.onBackground
  val reduceMotion = !ValueAnimator.areAnimatorsEnabled()
  val strokePx = with(LocalDensity.current) { 2.dp.toPx() }
  val guideStroke = remember(strokePx) { Stroke(width = strokePx) }
  val starPath = remember { Path() }
  val motion = remember { ChartMotion() }
  val trail = remember { DotTrail() }
  val sparkles = remember { Sparkle.field() }
  val settle =
    remember {
      spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    }

  val normX by
    animateFloatAsState(
      targetValue = (azimuthError / CHART_HALF_RANGE_DEGREES).coerceIn(-1.0, 1.0).toFloat(),
      animationSpec = settle,
      visibilityThreshold = 0.0005f,
      label = "azimuth",
    )
  val normY by
    animateFloatAsState(
      targetValue = -(altitudeError / CHART_HALF_RANGE_DEGREES).coerceIn(-1.0, 1.0).toFloat(),
      animationSpec = settle,
      visibilityThreshold = 0.0005f,
      label = "altitude",
    )
  val star by
    animateFloatAsState(targetValue = if (aligned) 1f else 0f, animationSpec = settle, label = "lock")

  val clockTransition = rememberInfiniteTransition(label = "pointing")
  val clock by
    clockTransition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec =
        infiniteRepeatable(animation = tween(durationMillis = 1_000, easing = LinearEasing), repeatMode = RepeatMode.Restart),
      label = "clock",
    )
  val closeness = (1f - hypot(normX, normY)).coerceIn(0f, 1f)

  val ripple = remember { Animatable(0f) }
  var celebration by remember { mutableIntStateOf(0) }
  var primed by remember { mutableStateOf(false) }
  var lockedIn by remember { mutableStateOf(false) }
  var needsGap by remember { mutableStateOf(false) }

  // Replay the burst only after the pointer has stayed outside the 1° ring.
  LaunchedEffect(aligned) {
    if (!primed) {
      primed = true
      lockedIn = aligned
      return@LaunchedEffect
    }
    if (aligned) {
      if (!lockedIn) {
        lockedIn = true
        if (!needsGap && !reduceMotion) celebration++
        needsGap = true
      }
    } else if (lockedIn) {
      delay(REALIGN_GAP_MS)
      if (!aligned) {
        lockedIn = false
        needsGap = false
      }
    }
  }
  LaunchedEffect(celebration) {
    if (celebration == 0) return@LaunchedEffect
    ripple.snapTo(0f)
    ripple.animateTo(1f, tween(durationMillis = CELEBRATION_MS, easing = LinearEasing))
  }

  val rippleProgress = ripple.value
  SideEffect {
    if (!reduceMotion) motion.advance(clock, closeness)
    trail.sample(normX, normY, System.nanoTime())
  }

  Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text("▲  this edge aims at the pole", color = onBackground, style = MaterialTheme.typography.labelLarge)
      Box(Modifier.fillMaxWidth().padding(top = 8.dp).aspectRatio(1f)) {
        Text("high", color = onBackground, style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.TopCenter))
        Text("low", color = onBackground, style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.BottomCenter))
        Text("W", color = onBackground, style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.CenterStart))
        Text("E", color = onBackground, style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.CenterEnd))
        Canvas(
          Modifier.fillMaxSize().padding(28.dp).semantics {
            contentDescription = "Pointing error. Right means too far east. Up means too high."
          }
        ) {
          val chartR = size.minDimension / 2f
          val center = Offset(size.width / 2f, size.height / 2f)
          val alignedRadius = chartR / CHART_HALF_RANGE_DEGREES.toFloat()
          val wave = if (reduceMotion) 0.65f else 0.5f - 0.5f * cos(motion.glow * TAU)
          val warmth = (closeness * (0.28f + 0.72f * wave)).coerceIn(0f, 1f)
          val glow = closeness * closeness * (0.28f + 0.72f * wave)

          if (glow > 0.03f) {
            val glowR = alignedRadius * (1.7f + closeness * (1.5f + wave))
            for (step in 5 downTo 1) {
              val t = step / 5f
              drawCircle(
                color = primary,
                radius = glowR * t,
                center = center,
                alpha = glow * 0.055f * (1.25f - t),
              )
            }
          }

          drawCircle(color = outline, radius = chartR, center = center, style = guideStroke)
          drawLine(outline, Offset(center.x - chartR, center.y), Offset(center.x + chartR, center.y), strokeWidth = strokePx)
          drawLine(outline, Offset(center.x, center.y - chartR), Offset(center.x, center.y + chartR), strokeWidth = strokePx)
          drawCircle(
            color = lerp(outline, primary, warmth),
            radius = alignedRadius * (1f + 0.2f * closeness * wave),
            center = center,
            style = guideStroke,
          )
          if (!reduceMotion && closeness > 0.04f && wave > 0.03f) {
            drawCircle(
              color = primary,
              radius = alignedRadius * (1f + 0.8f * closeness * wave),
              center = center,
              alpha = 0.2f * closeness * wave,
              style = guideStroke,
            )
          }

          if (celebration > 0 && rippleProgress < 1f) {
            for (ring in 0 until 3) {
              val start = ring * 0.14f
              if (rippleProgress < start) continue
              val u = ((rippleProgress - start) / 0.7f).coerceAtMost(1f)
              val radiusNow = alignedRadius * 0.25f + (chartR * 0.92f - alignedRadius * 0.25f) * u
              drawCircle(
                color = primary,
                radius = radiusNow,
                center = center,
                alpha = (1f - u) * (0.4f - ring * 0.08f),
                style = guideStroke,
              )
            }
            val spread = alignedRadius * 2.3f
            val sparkleOuter = 4.dp.toPx()
            for (sparkle in sparkles) {
              val pop = sparkleAlpha(rippleProgress, sparkle.delay)
              if (pop <= 0.02f) continue
              val outer = sparkleOuter * sparkle.scale * (0.55f + 0.45f * pop)
              fillStar(
                starPath,
                Offset(center.x + sparkle.nx * spread, center.y + sparkle.ny * spread),
                outer,
                outer * 0.3f,
                rippleProgress * sparkle.spin * TAU,
              )
              drawPath(starPath, primary, alpha = pop * 0.85f)
            }
          }

          val dotRadius = 10.dp.toPx()
          val now = System.nanoTime()
          val count = trail.count
          val cursor = trail.cursor
          for (n in count downTo 1) {
            val idx = (cursor - n).mod(TRAIL_LEN)
            val age = now - trail.at[idx]
            if (age < 0L || age >= TRAIL_SPAN_NS) continue
            val life = 1f - age.toFloat() / TRAIL_SPAN_NS.toFloat()
            drawCircle(
              color = primary,
              radius = dotRadius * (0.15f + 0.6f * life),
              center = Offset(center.x + trail.x[idx] * chartR, center.y + trail.y[idx] * chartR),
              alpha = life * life * 0.42f,
            )
          }

          val dotAt = Offset(center.x + normX * chartR, center.y + normY * chartR)
          if (star < 1f) {
            drawCircle(
              color = primary,
              radius = dotRadius * (1f - 0.15f * star),
              center = dotAt,
              alpha = (1f - star).coerceIn(0f, 1f),
            )
          }
          if (star > 0f) {
            val twinkle = 0.5f + 0.5f * sin(motion.twinkle * TAU)
            val outer = 16.dp.toPx() * (0.55f + 0.45f * star) * (0.93f + 0.07f * twinkle)
            fillStar(starPath, dotAt, outer, outer * 0.28f, motion.spin * TAU)
            drawPath(starPath, primary, alpha = star * (0.78f + 0.22f * twinkle))
          }
        }
      }
    }
  }
}

private fun fillStar(path: Path, at: Offset, outer: Float, inner: Float, rotationRad: Float) {
  path.rewind()
  var angle = rotationRad - HALF_PI
  for (i in 0 until 8) {
    val r = if ((i and 1) == 0) outer else inner
    val x = at.x + cos(angle) * r
    val y = at.y + sin(angle) * r
    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    angle += EIGHTH
  }
  path.close()
}

private fun sparkleAlpha(progress: Float, delay: Float): Float {
  val t = (progress - delay) / (1f - delay)
  if (t <= 0f || t >= 1f) return 0f
  val fadeIn = (t / 0.2f).coerceAtMost(1f)
  val fadeOut = ((1f - t) / 0.35f).coerceAtMost(1f)
  return fadeIn * fadeOut
}

private class ChartMotion {
  var glow = 0f
  var spin = 0f
  var twinkle = 0f
  private var previousClock = 0f
  private var started = false

  fun advance(clock: Float, closeness: Float) {
    if (!started) {
      started = true
      previousClock = clock
      return
    }
    var delta = clock - previousClock
    if (delta < 0f) delta += 1f
    previousClock = clock
    if (delta <= 0f) return
    if (delta > 0.08f) delta = 0.08f
    glow = (glow + delta * (0.45f + closeness * 2.2f)) % 1f
    spin = (spin + delta * 0.08f) % 1f
    twinkle = (twinkle + delta * 1.15f) % 1f
  }
}

private class DotTrail {
  val x = FloatArray(TRAIL_LEN)
  val y = FloatArray(TRAIL_LEN)
  val at = LongArray(TRAIL_LEN)
  var cursor = 0
  var count = 0
  private var lastSample = 0L
  private var lastX = 0f
  private var lastY = 0f
  private var hasLast = false

  fun sample(nx: Float, ny: Float, now: Long) {
    if (!hasLast) {
      hasLast = true
      lastX = nx
      lastY = ny
      return
    }
    if (now - lastSample < TRAIL_SAMPLE_NS) return
    val dx = nx - lastX
    val dy = ny - lastY
    if (dx * dx + dy * dy < TRAIL_MIN_STEP * TRAIL_MIN_STEP) return
    val i = cursor
    x[i] = lastX
    y[i] = lastY
    at[i] = now
    cursor = (i + 1) % TRAIL_LEN
    if (count < TRAIL_LEN) count++
    lastX = nx
    lastY = ny
    lastSample = now
  }
}

private class Sparkle(val nx: Float, val ny: Float, val delay: Float, val spin: Float, val scale: Float) {
  companion object {
    fun field(): Array<Sparkle> =
      Array(SPARKLE_COUNT) { index ->
        val random = Random(index * 31 + 11)
        val angle = random.nextFloat() * TAU
        val dist = 0.25f + random.nextFloat() * 0.9f
        val spin = (0.5f + random.nextFloat()) * if (random.nextBoolean()) 1f else -1f
        Sparkle(
          nx = cos(angle) * dist,
          ny = sin(angle) * dist,
          delay = index * 0.055f,
          spin = spin,
          scale = 0.7f + random.nextFloat() * 0.55f,
        )
      }
  }
}
