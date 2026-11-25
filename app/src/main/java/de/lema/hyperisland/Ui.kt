package de.lema.hyperisland

import android.content.Intent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs

const val M = 22

fun col(k: String) = Color(when (k) { "music" -> 0xFFFA2D55; "timer" -> 0xFFFF9F0A; "call", "chg" -> 0xFF30D158; else -> 0xFF5E9BFF })
fun ic(k: String) = when (k) { "music" -> "🎵"; "timer" -> "⏱"; "call" -> "📞"; "chg" -> "⚡"; else -> "💬" }

fun dims(a: Act?, e: Boolean): Pair<Int, Int> {
    if (a == null) return 0 to 0
    if (!e) return 236 to 36
    val body = if (a.kind == "music") 60 else if (a.kind == "msg" || a.kind == "call") 56 else 42
    val bar = if (a.kind == "music" || a.prog > 0f) 16 else 0
    val btn = if (a.ctl.isNotEmpty()) 52 else 0
    return 340 to (36 + body + bar + btn)
}

@Composable
fun Island() {
    val list by Hub.acts.collectAsState()
    val exp by Hub.exp.collectAsState()
    val pulls by Hub.pulls.collectAsState()
    val top = topAct(list)
    var drag by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(top != null) { shown = top != null }
    LaunchedEffect(top?.id) { Hub.exp.value = top?.kind == "msg" || top?.kind == "call" }
    LaunchedEffect(pulls) { if (pulls > 0) { Hub.exp.value = true; delay(1300); if (topAct(Hub.acts.value)?.kind != "call") Hub.exp.value = false } }
    LaunchedEffect(exp, top?.id) { if (exp && top?.kind != "call") { delay(6000); Hub.exp.value = false } }
    if (top == null) return
    val (tw, th) = if (shown) dims(top, exp) else (126 to 36)
    val sp = spring<Dp>(0.6f, 420f)
    val w by animateDpAsState(tw.dp, sp, label = "w")
    val h by animateDpAsState(th.dp, sp, label = "h")
    val r by animateDpAsState(if (exp && shown) 38.dp else 18.dp, sp, label = "r")
    val c = col(top.kind)
    val shape = RoundedCornerShape(r)
    Box(Modifier.fillMaxSize(), Alignment.TopCenter) {
        Box(
            Modifier.width(w).height(h)
                .shadow(16.dp, shape, ambientColor = c, spotColor = c)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Color(0xEB26262E), Color(0xF00A0A10))))
                .drawBehind { drawRect(Brush.verticalGradient(listOf(Color(0x2EFFFFFF), Color.Transparent), endY = size.height * 0.55f)) }
                .border(1.dp, Brush.linearGradient(listOf(Color(0x70FFFFFF), Color(0x0FFFFFFF), Color(0x4DFFFFFF))), shape)
                .pointerInput(top.id) { detectTapGestures(onTap = { Hub.exp.value = !Hub.exp.value }, onLongPress = { Hub.exp.value = true }) }
                .pointerInput(top.id) {
                    detectHorizontalDragGestures(onDragEnd = { if (abs(drag) > 120f) Hub.drop(top.id); drag = 0f }) { _, d -> drag += d }
                }
        ) {
            Crossfade(top.id to exp, label = "c") { (id, e) ->
                val a = list.find { it.id == id }
                if (a != null) { if (e) Expanded(a) else Compact(a) }
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
            Box(Modifier.width(3.dp).fillMaxHeight(if (on) s else 0.25f).clip(RoundedCornerShape(2.dp)).background(c))
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
fun Bar(p: Float, c: Color) = Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
    Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).fillMaxHeight().background(c))
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
    Box(m.height(40.dp).clip(RoundedCornerShape(20.dp)).background(bg).clickable { b.f() }, Alignment.Center) {
        if (b.label == "prev" || b.label == "play" || b.label == "pause" || b.label == "next") Ico(b.label)
        else Text(b.label, color = if (b.tone == 1) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun Compact(a: Act) = Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
    Lead(a, 24, false)
    if (a.kind == "music") Eq(col(a.kind), a.on)
    else Text(if (a.kind == "msg") a.title else a.sub, color = col(a.kind), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.widthIn(max = 90.dp))
}

@Composable
fun Expanded(a: Act) = Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Lead(a, if (a.kind == "music") 60 else 42)
        Column(Modifier.weight(1f)) {
            Text(a.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(a.sub, color = Color(0xFFB4B4BC), fontSize = 13.sp, maxLines = 2)
        }
        if (a.kind == "music") Eq(col(a.kind), a.on)
    }
    if (a.kind == "music" || a.prog > 0f) Bar(a.prog, col(a.kind))
    if (a.ctl.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        a.ctl.forEach { Pill(it, Modifier.weight(1f)) }
    }
}
