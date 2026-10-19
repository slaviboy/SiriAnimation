package com.slaviboy.sirianimation

import java.util.Locale

/** Uniform contract between [OrbField] (producer) and the AGSL shader (consumer). Pure Kotlin. */
internal object OrbUniforms {

    class Uniform(val name: String, val type: String, val floats: Int, val arrayLength: Int = 0) {
        /** Number of floats `RuntimeShader.setFloatUniform` must receive for this uniform. */
        val total: Int get() = floats * maxOf(arrayLength, 1)
    }

    // Static: set in configure().
    const val CENTER = "uCenter"
    const val RADIUS = "uRadius"
    const val EXTENT = "uExtent"
    const val BODY = "uBody"
    const val RIM_TOP = "uRimTop"
    const val RIM_SIDE = "uRimSide"
    const val RIM_BOTTOM = "uRimBottom"
    const val CORE_COL = "uCoreCol"
    const val HALO_COL = "uHaloCol"
    const val DEEP_COL = "uDeepCol"
    const val GLOW_COL = "uGlowCol"

    // Per frame: OrbField.update() output.
    const val BLADE_M = "uBM"
    const val BLADE_Z = "uBZ"
    const val BLADE_S = "uBS"
    const val BLADE_W = "uBW"
    const val BLADE_P = "uBP"
    const val BLADE_A = "uBA"
    const val BLADE_B = "uBB"
    const val BLADE_T = "uBT"
    const val CORE = "uCore"
    const val CORE_SHAPE = "uCoreShape"
    const val LOOK = "uLook"
    const val HALO = "uHalo"

    private const val N = OrbField.BLADES

    @JvmField
    val ALL: Array<Uniform> = arrayOf(
        Uniform(CENTER, "float2", 2), Uniform(RADIUS, "float", 1), Uniform(EXTENT, "float", 1),
        Uniform(BODY, "float3", 3), Uniform(RIM_TOP, "float3", 3), Uniform(RIM_SIDE, "float3", 3),
        Uniform(RIM_BOTTOM, "float3", 3), Uniform(CORE_COL, "float3", 3), Uniform(HALO_COL, "float3", 3),
        Uniform(DEEP_COL, "float3", 3), Uniform(GLOW_COL, "float3", 3),
        Uniform(BLADE_M, "float4", 4, N), Uniform(BLADE_Z, "float4", 4, N), Uniform(BLADE_S, "float4", 4, N),
        Uniform(BLADE_W, "float4", 4, N), Uniform(BLADE_P, "float4", 4, N), Uniform(BLADE_A, "float4", 4, N),
        Uniform(BLADE_B, "float4", 4, N), Uniform(BLADE_T, "float4", 4, N),
        Uniform(CORE, "float4", 4), Uniform(CORE_SHAPE, "float4", 4), Uniform(LOOK, "float4", 4),
        Uniform(HALO, "float4", 4)
    )
}

/**
 * AGSL source of the GPU orb. The prelude (uniforms, `BLADES`, shared constants) is generated from
 * [OrbUniforms] and [OrbField.SHARED], so names and values cannot drift from the Kotlin model.
 * [BODY] mirrors [OrbField.shade] stage by stage (`[F1]`..`[F14]`).
 */
internal object OrbShaderSource {

    val AGSL: String by lazy(LazyThreadSafetyMode.NONE) { prelude() + BODY }

    fun prelude(): String = buildString {
        append("const int BLADES = ").append(OrbField.BLADES).append(";\n")
        for ((name, value) in OrbField.SHARED) {
            append("const float ").append(name).append(" = ").append(literal(value)).append(";\n")
        }
        for (u in OrbUniforms.ALL) {
            append("uniform ").append(u.type).append(' ').append(u.name)
            if (u.arrayLength > 0) append('[').append(u.arrayLength).append(']')
            append(";\n")
        }
    }

    /** Locale-independent GLSL float literal (a comma decimal separator would not compile). */
    fun literal(v: Float): String = String.format(Locale.ROOT, "%.6f", v)

    // Coordinates: p = (fragCoord - uCenter) / uRadius, sphere radius 1, +y down, +z toward the viewer.
    // No time uniform: every time-dependent value arrives precomputed from OrbField.update().
    const val BODY = """
float3 toneMap(float3 x) {
    float m = max(max(x.r, x.g), x.b);
    float tm = 1.0 - exp(-m);
    float3 hue = x * (tm / max(m, 0.0001));
    float bleach = 1.0 - exp(-max(m - WHITE_POINT, 0.0) * BLEACH);
    return mix(hue, float3(tm), bleach);
}

float3 rimTint(float2 d) {
    float wt = max(-d.y, 0.0);
    float wb = max(d.y, 0.0);
    float ws = 0.8 * abs(d.x);
    return (uRimTop * wt + uRimBottom * wb + uRimSide * ws) / (wt + wb + ws + 0.0001);
}

half4 main(float2 fragCoord) {
    // [F1] Disc coordinates and a weak glass lens.
    float2 p = (fragCoord - uCenter) / uRadius;
    float r2 = dot(p, p);
    float r = sqrt(r2);
    float fpx = 1.0 / uRadius;
    float2 dir = (r > 0.0001) ? p / r : float2(0.0, -1.0);
    float3 col = float3(0.0);
    float alpha = 0.0;

    if (r < 1.0) {
        float2 pin = p * (1.0 - LENS * r2);
        float rin2 = dot(pin, pin);
        float kZ[BLADES];
        float kA[BLADES];
        float3 kE[BLADES];

        for (int k = 0; k < BLADES; k++) {
            float4 M = uBM[k];
            float4 Z = uBZ[k];
            float4 S = uBS[k];
            float4 Wv = uBW[k];
            float4 P = uBP[k];
            float4 A = uBA[k];
            float4 B = uBB[k];
            // [F2] Ray/plane hit in the plane's own coordinates (inverse 2x2 precomputed on the CPU).
            float2 q = pin - P.xy;
            float a = M.x * q.x + M.y * q.y;
            float b = M.z * q.x + M.w * q.y;
            float zh = P.z + a * Z.x + b * Z.y;
            kZ[k] = zh;
            kA[k] = 0.0;
            kE[k] = float3(0.0);
            // [F3] Polar coordinates in the plane.
            float rho = sqrt(a * a + b * b);
            if (rho < S.w) {
                float phi = atan(b, (rho > 0.00001) ? a : 1.0);
                // [F4] Curved arms: twist, curl and a travelling bend.
                phi -= S.y * rho + S.z * rho * rho + Wv.x * sin(Wv.y * rho + Wv.z);
                // [F5] Sector profile: x = 0 on the crisp fold edge, 1 on the soft trailing edge.
                float W = S.x;
                float aW = abs(W);
                float u = (phi - 0.5 * W) / TAU;
                u -= floor(u + 0.5);
                float x = 0.5 + u * TAU / W;
                float xp = max(x, 0.0);
                float tw = abs(S.y) + 2.0 * abs(S.z) * rho + abs(Wv.x * Wv.y);
                float grad = sqrt(1.0 / max(rho * rho, 0.000001) + tw * tw);
                float back = smoothstep(BACK_LO, BACK_HI, uCore.z - zh);
                float fp = Z.z * fpx;
                float sig = (B.w * (1.0 + DOF * back) + 0.75 * fp) * grad / aW;
                float edge = smoothstep(-sig, sig, x);
                float tail = 1.0 - smoothstep(P.w, 1.0 + sig, x);
                float fold = 1.0 / sqrt(1.0 + xp / FOLD_LAMBDA);
                float shape = edge * tail * (BLADE_LEVEL + FOLD_LEVEL * fold);
                float cbl = smoothstep(0.0, CORE_BLEND, rho);
                shape = mix(CORE_FILL, shape, cbl);
                float xc = min(xp, 1.0);
                float rk = S.w * (1.0 - PETAL * xc * xc);
                float radial = (1.0 - smoothstep(rk * (1.0 - RAD_SOFT), rk, rho)) *
                    (FILL_IN + (1.0 - FILL_IN) * exp(-RAD_DECAY * rho));
                // [F6] Gain (weight, tilt foreshortening, audio) is precomputed: Z.w.
                // [F7] Fade where the plane leaves the sphere; parts behind the core are dimmer, bluer, softer.
                float hr = sqrt(rin2 + zh * zh);
                float inside = 1.0 - smoothstep(INSIDE_LO, INSIDE_HI, hr);
                float I = max(shape * radial * inside * Z.w * (1.0 - DEPTH_DIM * back), 0.0);
                float mixAB = clamp(0.6 * xp + 0.5 * rho / S.w, 0.0, 1.0);
                float3 c = mix(mix(A.rgb, B.rgb, mixAB), uDeepCol, DEPTH_TINT * back);
                // Fans run white-hot where they converge on the core.
                c = mix(c, uCoreCol, WHITEN * exp(-rho / WHITEN_R));
                kE[k] = c * I;
                kA[k] = 1.0 - exp(-OPACITY_K * I);
            }
        }

        // [F8] Soft O(N^2) occlusion: blades in front hide part of the ones behind.
        float3 e = float3(0.0);
        float trans = 1.0;
        for (int i = 0; i < BLADES; i++) {
            float tr = 1.0;
            for (int j = 0; j < BLADES; j++) {
                if (j != i) {
                    tr *= 1.0 - OCCLUSION * kA[j] * smoothstep(-OCC_SOFT, OCC_SOFT, kZ[j] - kZ[i]);
                }
            }
            e += kE[i] * tr;
            trans *= 1.0 - kA[i];
        }

        // [F9] Core: white-hot point plus a colored halo.
        float2 q0 = pin - uCore.xy;
        float d2 = dot(q0, q0);
        e += uCoreCol * (uCore.w * exp(-d2 * uCoreShape.x));
        e += uHaloCol * (uCoreShape.z * exp(-d2 * uCoreShape.y));

        // [F10] Hue-preserving tone map; only very hot regions bleach to white.
        float3 c = toneMap(e * uCoreShape.w);

        // [F11] Glass body (hidden behind dense blades) and a faint inner fresnel.
        float zf = sqrt(max(1.0 - r2, 0.0));
        float et = 1.0 - zf;
        float3 rim = rimTint(dir);
        c += uBody * (uLook.z * (0.6 + 0.4 * zf) * (1.0 - BODY_HIDE * (1.0 - trans)));
        c += rim * (et * et * et * uLook.y);

        // [F12] Thin rim line, lit from the top-left and where blades reach the glass.
        float w = max(RIM_W, fpx);
        float rd = (r - 1.0 + 1.3 * w) / w;
        float ring = exp(-rd * rd);
        float tip = 0.0;
        for (int k = 0; k < BLADES; k++) {
            float4 T = uBT[k];
            tip += T.z * exp((dot(dir, T.xy) - 1.0) / RIM_SPREAD);
        }
        float lit = max(-0.55 * dir.x - 0.835 * dir.y, 0.0);
        c += rim * (ring * uLook.x * (0.45 + 0.3 * lit + tip));

        // [F13] Faint specular crescent near the top-left of the glass.
        float cd = (1.0 - r - 0.04) / 0.025;
        c += float3(SPEC * exp(-cd * cd) * lit * lit * lit);

        c = min(sqrt(max(c, 0.0)), 1.0);

        // [F14] Anti-aliased silhouette.
        alpha = 1.0 - smoothstep(1.0 - 1.5 * fpx, 1.0, r);
        col = c * alpha;
    }

    // [F14] Outer halo behind the orb, gone before the edge of the drawing area.
    float gr = uHalo.y;
    float rmax = min(1.0 + HALO_REACH * gr, uExtent - 2.0 * fpx);
    // rmax <= 1 + 2px: no room between the orb and the edge of the drawing area, so no halo.
    if (uHalo.x > 0.0 && rmax > 1.0 + 2.0 * fpx && r > 1.0 - 2.0 * fpx) {
        float d = max(r - 1.0, 0.0);
        float fade = 1.0 - smoothstep(1.0 + 0.5 * (rmax - 1.0), rmax, r);
        float ga = clamp(uHalo.x * HALO_PEAK * exp(-d / (HALO_LEN * gr)) * fade, 0.0, HALO_MAX);
        float k = ga * (1.0 - alpha);
        col += uGlowCol * k;
        alpha += k;
    }

    // +-0.5 LSB interleaved-gradient dither against banding; keep premultiplied (col <= alpha).
    float n = fract(52.9829189 * fract(dot(fragCoord, float2(0.06711056, 0.00583715))));
    col = clamp(col + (n - 0.5) / 255.0, 0.0, alpha);
    return half4(half3(col), half(alpha));
}
"""
}
