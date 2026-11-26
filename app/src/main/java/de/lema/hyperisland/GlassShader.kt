package de.lema.hyperisland

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Glas-Shader (AGSL, Android 13+). Berechnet Lichtbrechung am Rand der Island selbst:
 * abgeschrägte Glaskante mit Lichtreflex, Farbsäume (Dispersion), innere Linse und Glanzfleck.
 * Den Hintergrund hinter dem Fenster kann eine App nicht lesen, deshalb wird dort nichts "echt" gebrochen.
 * Schlägt irgendetwas fehl (alte Android-Version, Shader-Fehler), liefert [draw] false und die Ui nutzt ihr Fallback.
 */
object Glass {
    private const val SRC = """
uniform float2 iSize;
uniform float iRadius;
uniform float iDp;
uniform float4 iAccent;
uniform float iAmt;
uniform float iFrost;
uniform float iAlpha;

float sdBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r, r);
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0, 0.0))) - r;
}

half4 main(float2 fc) {
    float2 hs = iSize * 0.5;
    float2 p = fc - hs;
    float r = min(iRadius, min(hs.x, hs.y));
    float d = sdBox(p, hs, r);
    float e = max(-d, 0.0);

    // Oberflächen-Normale aus dem Gradienten der Distanzfunktion (zeigt nach außen)
    float2 g = float2(
        sdBox(p + float2(1.0, 0.0), hs, r) - sdBox(p - float2(1.0, 0.0), hs, r),
        sdBox(p + float2(0.0, 1.0), hs, r) - sdBox(p - float2(0.0, 1.0), hs, r));
    float gl = length(g);
    float2 n = float2(0.0, -1.0);
    if (gl > 0.0001) { n = g / gl; }

    // Lichtquelle oben links
    float2 L = normalize(float2(-0.55, -0.85));
    float facing = dot(n, L);
    float spec = clamp(facing, 0.0, 1.0);
    float back = clamp(-facing, 0.0, 1.0);

    float bevel = exp(-e / (6.0 * iDp));
    float ring = exp(-pow((e - 3.0 * iDp) / (1.6 * iDp), 2.0));
    float rr = exp(-pow((e - 2.2 * iDp) / (1.4 * iDp), 2.0));
    float bb = exp(-pow((e - 4.2 * iDp) / (1.4 * iDp), 2.0));
    float3 disp = float3(1.0, 0.35, 0.45) * rr + float3(0.35, 0.55, 1.0) * bb;

    // Körper: dunkles, getöntes Glas mit Verlauf
    float t = clamp(fc.y / iSize.y, 0.0, 1.0);
    float3 base = mix(float3(0.145, 0.145, 0.19), float3(0.025, 0.025, 0.045), t);
    base += iAccent.rgb * (0.30 * iAccent.a * iAmt) * smoothstep(0.35, 1.0, t);
    base = mix(base, float3(0.86, 0.89, 0.95), iFrost * 0.22);

    // Innere Linse: Licht sammelt sich zum Rand hin
    float inner = exp(-e / (14.0 * iDp));
    base += float3(0.90, 0.93, 1.00) * inner * 0.07;

    // Glanzfleck oben links und feine Lichtkante oben
    float2 sp = (fc - float2(iSize.x * 0.2, 0.0)) / (iSize.x * 0.55);
    float blob = exp(-dot(sp, sp) * 3.0);
    base += float3(1.0, 1.0, 1.0) * blob * 0.15;
    float fx = fc.x / iSize.x;
    float line = exp(-pow((fc.y - 2.0 * iDp) / (0.9 * iDp), 2.0)) * smoothstep(0.08, 0.30, fx) * (1.0 - smoothstep(0.70, 0.92, fx));

    float3 col = base;
    col += float3(1.0, 1.0, 1.0) * bevel * spec * 0.55;
    col += disp * (0.20 + 0.80 * spec) * 0.22;
    col += float3(1.0, 1.0, 1.0) * ring * 0.10 * (0.4 + spec);
    col += float3(1.0, 1.0, 1.0) * line * 0.35;
    col = col * (1.0 - bevel * back * 0.35);
    col = clamp(col, 0.0, 1.0);

    float a = clamp(iAlpha + bevel * spec * 0.55 + ring * 0.20 + line * 0.40, 0.0, 1.0);
    float m = clamp(0.5 - d, 0.0, 1.0);
    return half4(half3(col * a), half(a)) * half(m);
}
"""

    @Volatile private var broken = false

    /** Erzeugt den Shader (Typ bewusst Any?, damit alte Android-Versionen die Klasse nie laden müssen). */
    fun create(): Any? {
        if (Build.VERSION.SDK_INT < 33 || broken) return null
        return try { RuntimeShader(SRC) } catch (_: Throwable) { broken = true; null }
    }

    /** true = mit Shader gezeichnet, false = Aufrufer soll sein Fallback zeichnen. */
    fun draw(scope: DrawScope, shader: Any?, radius: Float, accent: Color, glow: Float, amt: Float, alpha: Float, frost: Float): Boolean {
        if (shader == null || broken || Build.VERSION.SDK_INT < 33) return false
        return try {
            val s = shader as RuntimeShader
            s.setFloatUniform("iSize", scope.size.width, scope.size.height)
            s.setFloatUniform("iRadius", radius)
            s.setFloatUniform("iDp", scope.density)
            s.setFloatUniform("iAccent", accent.red, accent.green, accent.blue, glow)
            s.setFloatUniform("iAmt", amt)
            s.setFloatUniform("iFrost", frost)
            s.setFloatUniform("iAlpha", alpha)
            scope.drawRect(ShaderBrush(s))
            true
        } catch (_: Throwable) {
            broken = true
            false
        }
    }
}
