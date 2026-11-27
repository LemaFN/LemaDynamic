package de.lema.hyperisland

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Rand (dp) links/rechts/unten um die Island im Fenster, Platz für Schatten und das "Einsaugen"-Bounce. */
const val M = 22
/** Höchste ausgeklappte Island (Musik mit Tasten) in dp, bestimmt die feste Fenstergröße. Muss zu dims() passen. */
const val EXP_MAX_H = 160

fun col(k: String) = Color(when (k) { "music" -> 0xFFFA2D55; "timer" -> 0xFFFF9F0A; "call", "chg" -> 0xFF30D158; "bt" -> 0xFF0A84FF; else -> 0xFF5E9BFF })
fun ic(k: String) = when (k) { "music" -> "🎵"; "timer" -> "⏱"; "call" -> "📞"; "chg" -> "⚡"; "bt" -> "🎧"; else -> "💬" }

/** Liegt der Wert unter dem Ziel, wird die Abweichung weich auf max. frac*Ziel begrenzt. */
private fun softFloor(v: Float, target: Float, frac: Float): Float {
    val u = target - v
    if (u <= 0f) return v
    val m = target * frac
    return target - m * kotlin.math.tanh(u / m)
}

/** Zielgröße der Island in dp (Breite, Höhe). 0 x 0 = nichts anzeigen. */
fun dims(a: Act?, e: Boolean, cw: Int = 236): Pair<Int, Int> {
    if (a == null) return 0 to 0
    if (!e) return cw to 36
    val body = when (a.kind) { "music" -> 60; "msg", "call" -> 54; else -> 42 }
    var h = 32 + body
    if (a.kind == "music" || a.prog > 0f) h += 12 + 4
    if (a.ctl.isNotEmpty()) h += 12 + 40
    return 344 to h
}

/** Pille im Kompaktzustand, Rundung wächst mit der Höhe bis max. 38dp. Hängt nur an der Größe, nicht an State. */
private class IslandShape(private val maxDp: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val max = with(density) { maxDp.dp.toPx() }
        val rad = minOf(size.height / 2f, max)
        return Outline.Rounded(RoundRect(size.toRect(), CornerRadius(rad)))
    }
}

/** Größe wird erst in der Layout-Phase gelesen: Animation kostet keine Recomposition pro Frame. */
private fun Modifier.animSize(w: State<Dp>, h: State<Dp>) = layout { m, _ ->
    // Die Feder kann beim schnellen Auf-/Zuklappen mit Restschwung unter 0 schießen (Absturz: height(-2)), daher nach unten begrenzen
    val pw = w.value.roundToPx().coerceAtLeast(100.dp.roundToPx())
    val ph = h.value.roundToPx().coerceAtLeast(26.dp.roundToPx())
    val p = m.measure(Constraints.fixed(pw, ph))
    layout(pw, ph) { p.place(0, 0) }
}

@Composable
fun Island() {
    val cfg by Cfg.v.collectAsState()
    val d0 = LocalDensity.current
    // Gesamtgröße über die Compose-Dichte: Größen, Schrift und Touch-Flächen skalieren zusammen
    CompositionLocalProvider(LocalDensity provides Density(d0.density * cfg.scale, d0.fontScale)) { IslandBody(cfg) }
}

@Composable
private fun IslandBody(cfg: Config) {
    val list by Hub.acts.collectAsState()
    val exp by Hub.exp.collectAsState()
    val pulls by Hub.pulls.collectAsState()
    val hidden by Hub.hidden.collectAsState()
    val sel by Hub.sel.collectAsState()
    val top = if (hidden) null else topAct(list, sel)
    val live = top != null

    // Letzte Aktivität merken, damit die Island beim Verschwinden sauber einschrumpft statt zu springen.
    var held by remember { mutableStateOf<Act?>(null) }
    LaunchedEffect(top?.id) { if (top != null) held = top }
    val act = top ?: held

    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(live) { shown = live }

    val show by animateFloatAsState(if (live) 1f else 0f, tween(if (live) 200 else 320), label = "show")
    val glass = animateFloatAsState(if (exp && live) 1f else 0f, tween(320), label = "glass")

    LaunchedEffect(top?.id) { if (top?.kind == "msg" || top?.kind == "call" || top?.kind == "bt") Hub.exp.value = true }
    // App wurde minimiert: Icon fliegt (Fly-Fenster) -> Island öffnet sich kurz, "schluckt" und klappt wieder zu.
    LaunchedEffect(pulls) {
        if (pulls > 0) {
            delay(330)
            Hub.exp.value = true
            delay(1500)
            if (topAct(Hub.acts.value)?.kind != "call") Hub.exp.value = false
        }
    }
    LaunchedEffect(exp, top?.id) { if (exp && top?.kind != "call") { delay((cfg.holdSec * 1000).toLong()); Hub.exp.value = false } }

    val (tw, th) = if (shown && live && act != null) dims(act, exp, cfg.compactW.toInt()) else 126 to 36
    val sp = spring<Dp>(dampingRatio = 0.9f - 0.55f * cfg.wobble, stiffness = 480f - 230f * cfg.wobble)
    val w0 = animateDpAsState(tw.dp, sp, label = "w")
    val h0 = animateDpAsState(th.dp, sp, label = "h")
    // Unterschwingen beim Einklappen weich begrenzen (tanh): volle Wobbel-Optik, aber nie ein dünner Strich
    val tgtW = rememberUpdatedState(tw.toFloat())
    val tgtH = rememberUpdatedState(th.toFloat())
    val w = remember { derivedStateOf { softFloor(w0.value.value, tgtW.value, 0.30f).dp } }
    val h = remember { derivedStateOf { softFloor(h0.value.value, tgtH.value, 0.45f).dp } }

    val bump = remember { Animatable(1f) }
    LaunchedEffect(pulls) {
        if (pulls > 0) {
            delay(330)
            bump.animateTo(1.08f, tween(90))
            bump.animateTo(1f, spring(0.4f, 420f))
        }
    }

    val ox = remember { Animatable(0f) }
    val oy = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val dismissPx = with(LocalDensity.current) { 90.dp.toPx() }
    val upPx = with(LocalDensity.current) { 30.dp.toPx() }
    val shader = remember { Glass.create() }
    var nodeC by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var baseX by remember { mutableFloatStateOf(0f) }
    var baseY by remember { mutableFloatStateOf(0f) }

    if (act == null || (!live && show == 0f)) return
    val c = col(act.kind)
    val shape = remember(cfg.corner) { IslandShape(cfg.corner) }

    Box(Modifier.fillMaxSize(), Alignment.TopCenter) {
        Box(
            Modifier
                .animSize(w, h)
                .onGloballyPositioned { nodeC = it }
                // Gesten VOR graphicsLayer, sonst wandert das Koordinatensystem mit dem Finger mit.
                .pointerInput(act.id) {
                    detectTapGestures(
                        onTap = { Hub.exp.value = !Hub.exp.value },
                        onLongPress = { if (cfg.haptic) haptic.performHapticFeedback(HapticFeedbackType.LongPress); Hub.exp.value = true }
                    )
                }
                .pointerInput(act.id) {
                    detectDragGestures(
                        onDragStart = { off ->
                            // Fensterposition des Fingers merken: lokale Koordinaten springen, wenn die Island unter dem Finger einklappt
                            val wp = nodeC?.localToWindow(off) ?: off
                            baseX = wp.x - ox.value
                            baseY = wp.y - oy.value
                        },
                        onDragEnd = {
                            val dx = ox.value
                            val dy = oy.value
                            scope.launch {
                                if (abs(dx) > dismissPx && Hub.acts.value.size < 2) {
                                    ox.animateTo(if (dx > 0) 700f else -700f, tween(160))
                                    Hub.dismiss(act)
                                    ox.snapTo(0f)
                                    oy.snapTo(0f)
                                } else {
                                    // Mehrere Anzeigen: seitlich wischen wechselt zur nächsten
                                    if (abs(dx) > upPx * 1.5f && Hub.acts.value.size >= 2) Hub.cycle(if (dx < 0) 1 else -1)
                                    if (dy < -upPx) Hub.exp.value = false
                                    launch { ox.animateTo(0f, spring(0.6f, 500f)) }
                                    oy.animateTo(0f, spring(0.6f, 500f))
                                }
                            }
                        },
                        onDragCancel = { scope.launch { ox.snapTo(0f); oy.snapTo(0f) } },
                        onDrag = { change, d ->
                            change.consume()
                            val wp = nodeC?.localToWindow(change.position)
                            val nx = if (wp != null) wp.x - baseX else ox.value + d.x
                            val ny = if (wp != null) wp.y - baseY else oy.value + d.y
                            scope.launch {
                                ox.snapTo(nx)
                                oy.snapTo(ny.coerceIn(-90f, 0f))
                            }
                        }
                    )
                }
                .graphicsLayer {
                    translationX = ox.value
                    translationY = oy.value * 0.4f
                    scaleX = bump.value
                    scaleY = bump.value
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    alpha = show * (1f - (abs(ox.value) / (dismissPx * 3f)).coerceIn(0f, 1f))
                }
                .shadow(14.dp, shape, clip = false, ambientColor = c.copy(alpha = cfg.glow), spotColor = c.copy(alpha = cfg.glow))
                .clip(shape)
                .drawBehind {
                    val g = glass.value
                    val base = cfg.compactOpacity + (cfg.opacity - cfg.compactOpacity) * g
                    // Bevorzugt: Glas-Shader mit Rand-Lichtbrechung; sonst unten das Schichten-Fallback
                    if (cfg.refract && Glass.draw(this, shader, minOf(size.height / 2f, cfg.corner.dp.toPx()), c, cfg.glow, g, base, cfg.frost)) return@drawBehind
                    // 1) getönte Glasfläche
                    drawRect(Brush.verticalGradient(listOf(Color(0xFF24242E).copy(alpha = base), Color(0xFF06060A).copy(alpha = base))))
                    // 2) Farbschimmer der Aktivität von unten
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, c.copy(alpha = 0.55f * cfg.glow * g)), startY = size.height * 0.4f))
                    // 2b) Frost: milchige Schicht, je höher der Regler desto "frosted"
                    drawRect(Color(0xFFDDE3F0).copy(alpha = cfg.frost * 0.22f * (0.35f + 0.65f * g)))
                    // 3) Glanzlicht oben links + weicher Top-Sheen
                    drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.24f), Color.Transparent), center = Offset(size.width * 0.16f, 0f), radius = size.width * 0.6f))
                    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), Color.Transparent), endY = size.height * 0.5f))
                    // 4) feine Lichtkante direkt unter dem oberen Rand
                    drawRoundRect(
                        Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.38f), Color.Transparent)),
                        topLeft = Offset(size.width * 0.12f, 1.5.dp.toPx()),
                        size = Size(size.width * 0.76f, 1.dp.toPx()),
                        cornerRadius = CornerRadius(1f)
                    )
                }
                .border(1.dp, Brush.linearGradient(listOf(Color(0xB3FFFFFF), Color(0x14FFFFFF), Color(0x66FFFFFF))), shape)
        ) {
            Crossfade(act.id to exp, animationSpec = tween(170), label = "c") { (id, e) ->
                val a = list.find { it.id == id } ?: act
                val others = list.filter { it.id != a.id }.sortedByDescending { pr(it.kind) }.take(3)
                if (e) Expanded(a, others) else Compact(a, others)
            }
        }
    }
}

@Composable
fun Eq(c: Color, on: Boolean) {
    val t = rememberInfiniteTransition(label = "eq")
    Row(Modifier.height(16.dp), Arrangement.spacedBy(2.dp), Alignment.CenterVertically) {
        listOf(520, 380, 610, 450).forEach { d ->
            val s by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(d, easing = LinearEasing), RepeatMode.Reverse), label = "b")
            Box(
                Modifier.width(3.dp).fillMaxHeight()
                    .graphicsLayer { scaleY = if (on) s else 0.25f; transformOrigin = TransformOrigin(0.5f, 1f) }
                    .clip(RoundedCornerShape(2.dp)).background(c)
            )
        }
    }
}

@Composable
fun Ico(k: String) = Canvas(Modifier.size(22.dp)) {
    val w = size.width
    val h = size.height
    val c = Color.White
    fun tri(a: Float, b: Float) = drawPath(Path().apply { moveTo(a, h * .15f); lineTo(b, h * .5f); lineTo(a, h * .85f); close() }, c)
    fun bar(x: Float) = drawRoundRect(c, Offset(x, h * .15f), Size(w * .14f, h * .7f), CornerRadius(2f))
    when (k) {
        "play" -> tri(w * .25f, w * .88f)
        "pause" -> { bar(w * .22f); bar(w * .64f) }
        "next" -> { tri(w * .08f, w * .68f); bar(w * .76f) }
        else -> withTransform({ scale(-1f, 1f, Offset(w / 2, h / 2)) }) { tri(w * .08f, w * .68f); bar(w * .76f) }
    }
}

@Composable
fun Bar(p: Float, c: Color) {
    val ap by animateFloatAsState(p.coerceIn(0f, 1f), tween(950, easing = LinearEasing), label = "bar")
    Box(
        Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))
            .drawBehind { drawRect(c, size = Size(size.width * ap, size.height)) }
    )
}

@Composable
fun Lead(a: Act, s: Int, open: Boolean = true) {
    val ctx = LocalContext.current
    val m = Modifier.size(s.dp).clip(RoundedCornerShape((s / 4).dp)).clickable(enabled = open) {
        ctx.packageManager.getLaunchIntentForPackage(a.pkg)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    if (a.art != null) Image(a.art.asImageBitmap(), null, m, contentScale = ContentScale.Crop)
    else Box(m.background(col(a.kind).copy(alpha = 0.25f)), Alignment.Center) { Text(ic(a.kind), fontSize = (s / 2).sp) }
}

@Composable
fun Pill(b: Btn, m: Modifier) {
    val bg = when (b.tone) { 1 -> Color(0xFF30D158); 2 -> Color(0xFFFF453A); else -> Color(0x26FFFFFF) }
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) 0.92f else 1f, spring(0.6f, 800f), label = "press")
    Box(
        m.height(40.dp)
            .graphicsLayer { scaleX = sc; scaleY = sc }
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(20.dp))
            .clickable(src, null) { b.f() },
        Alignment.Center
    ) {
        if (b.label == "prev" || b.label == "play" || b.label == "pause" || b.label == "next") Ico(b.label)
        else Text(b.label, color = if (b.tone == 1) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun Compact(a: Act, others: List<Act> = emptyList()) = Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
    Lead(a, 24, false)
    // Weitere laufende Anzeigen: kleine Punkte in ihrer Farbe (seitlich wischen wechselt)
    if (others.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        others.forEach { Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(col(it.kind))) }
    }
    if (a.kind == "music") Eq(col(a.kind), a.on)
    else Text(if (a.kind == "msg") a.title else a.sub, color = col(a.kind), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.widthIn(max = 90.dp))
}

@Composable
fun Expanded(a: Act, others: List<Act> = emptyList()) = Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Lead(a, if (a.kind == "music") 60 else 42)
        Column(Modifier.weight(1f)) {
            Text(a.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(a.sub, color = Color(0xFFB4B4BC), fontSize = 13.sp, maxLines = 2)
        }
        if (a.kind == "music") Eq(col(a.kind), a.on)
        // Weitere laufende Anzeigen als antippbare Symbole
        others.forEach { o ->
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(15.dp)).background(col(o.kind).copy(alpha = 0.22f)).clickable { Hub.sel.value = o.id }, Alignment.Center) {
                Text(ic(o.kind), fontSize = 15.sp)
            }
        }
    }
    if (a.kind == "music" || a.prog > 0f) Bar(a.prog, col(a.kind))
    if (a.ctl.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        a.ctl.forEach { Pill(it, Modifier.weight(1f)) }
    }
}

/**
 * Das App-Icon einer minimierten App fliegt vom unteren Bildschirmbereich in die Island,
 * schrumpft dabei und blendet aus. Läuft in einem eigenen, nicht berührbaren Vollbild-Fenster.
 */
@Composable
fun FlyIcon(bmp: Bitmap, tx: Float, ty: Float, sw: Float, sh: Float) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(520, easing = CubicBezierEasing(0.45f, 0f, 0.85f, 0.35f))) }
    val size = with(LocalDensity.current) { 72.dp.toPx() }
    Box(Modifier.fillMaxSize()) {
        Image(
            bmp.asImageBitmap(), null,
            Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)).graphicsLayer {
                val t = p.value
                val sx = sw / 2f
                val sy = sh * 0.62f
                translationX = sx + (tx - sx) * t - size / 2f
                translationY = sy + (ty - sy) * t - size / 2f
                val s = 1f - 0.74f * t
                scaleX = s
                scaleY = s
                alpha = if (t < 0.7f) 1f else 1f - (t - 0.7f) / 0.3f
            }
        )
    }
}
