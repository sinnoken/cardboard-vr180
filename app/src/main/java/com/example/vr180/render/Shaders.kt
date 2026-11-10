package com.example.vr180.render

/**
 * 單次繪製（single pass）：每個螢幕像素
 *   螢幕座標 → 相對鏡片中心的 tan 角 → 鏡片畸變校正 → 視線方向 → 頭部旋轉 → 取樣。
 * 不需要中間的 framebuffer，8K 影片貼圖只在這裡被取樣一次。
 * 畸變公式與 Cardboard SDK 相同：t_scene = t_screen · (1 + k1·r² + k2·r⁴)。
 */
object Shaders {
    const val VERTEX = """#version 300 es
layout(location = 0) in vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
"""

    const val FRAGMENT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;

uniform samplerExternalOES uVideo;
uniform sampler2D uUi;
uniform mat4 uTexMatrix;
uniform mat3 uWorldFromEye;     // 頭部旋轉（含重新置中、漂移補償）
uniform mat3 uContentFromEye;   // 再加上場景校正與單眼修正
uniform vec2 uLensCenter;       // 視窗像素座標
uniform float uPxPerTan;
uniform vec2 uK;                // k1, k2
uniform float uMaxTan;
uniform float uVideoOffset;     // 0.0 = 左半張, 0.5 = 右半張
uniform int uEye;               // 0 左眼, 1 右眼
uniform int uMode;              // 0 影片, 1 方格牆, 2 眼距對準圖, 3 對中心圖
uniform bool uHasVideo;
uniform bool uGuide;            // 水平參考線
uniform bool uUiOn;
uniform vec3 uUiF;
uniform vec3 uUiR;
uniform vec3 uUiU;
uniform vec2 uUiHalf;

out vec4 fragColor;
const float PI = 3.14159265;

// 以像素為單位的抗鋸齒線條：coord 為整數處是線
float gridLine(float coord, float widthPx) {
    float d = abs(fract(coord + 0.5) - 0.5);
    float w = max(fwidth(coord), 1e-5) * widthPx;
    return 1.0 - smoothstep(0.5 * w, 1.5 * w, d);
}

float band(float v, float widthPx) {
    float w = max(fwidth(v), 1e-5) * widthPx;
    return 1.0 - smoothstep(0.5 * w, 1.5 * w, abs(v));
}

void main() {
    vec2 t = (gl_FragCoord.xy - uLensCenter) / uPxPerTan;
    float r2 = dot(t, t);
    vec2 te = t * (1.0 + uK.x * r2 + uK.y * r2 * r2);
    bool inside = max(abs(te.x), abs(te.y)) <= uMaxTan;

    vec3 eyeDir = normalize(vec3(te, -1.0));
    vec3 w = uWorldFromEye * eyeDir;

    // ---- 導數相關的量一律在分支外計算 ----
    float front = -w.z;
    vec2 plane = w.xy / max(front, 0.05);          // 正前方 z=-1 平面上的座標
    float ax = atan(w.x, -w.z);
    float ay = asin(clamp(w.y, -1.0, 1.0));
    float gridA = max(gridLine(plane.x * 4.0, 1.6), gridLine(plane.y * 4.0, 1.6));
    float gridB = max(gridLine(plane.x * 20.0, 1.0), gridLine(plane.y * 20.0, 1.0));
    float horizon = band(ay, 2.5);
    float centerV = band(ax, 2.5) * step(0.0, front);
    float lineAx = band(ax, 3.0);
    float lineAy = band(ay, 3.0);
    float ring = band(length(vec2(ax, ay)) - 0.30, 2.5);
    // 對中心圖（固定在螢幕上，不跟頭轉）：以軟體認定的鏡片中心畫同心圓、十字與可視範圍外框
    float cRings = gridLine(length(te) * 10.0, 1.5);
    float cCross = max(band(te.x, 2.5), band(te.y, 2.5));
    float cEdge = band(max(abs(te.x), abs(te.y)) - uMaxTan * 0.97, 4.0);

    vec3 col = vec3(0.0);

    if (uMode == 0) {
        vec3 c = uContentFromEye * eyeDir;
        float lon = atan(c.x, -c.z);
        float lat = asin(clamp(c.y, -1.0, 1.0));
        if (uHasVideo && abs(lon) <= 0.5 * PI) {
            vec2 uv = vec2(uVideoOffset + (lon / PI + 0.5) * 0.5, lat / PI + 0.5);
            col = texture(uVideo, (uTexMatrix * vec4(uv, 0.0, 1.0)).xy).rgb;
        }
        if (uGuide) col = mix(col, vec3(1.0, 0.25, 0.2), horizon * 0.8);
    } else if (uMode == 1) {
        // 方格牆：直線在正確的畸變校正下必須看起來是直的，轉頭時也必須固定不動
        if (front > 0.05) {
            col = vec3(0.10);
            col = mix(col, vec3(0.35), gridB * 0.6);
            col = mix(col, vec3(0.92), gridA);
            col = mix(col, vec3(0.2, 0.9, 0.3), centerV);
        } else {
            col = vec3(0.04);
        }
        col = mix(col, vec3(1.0, 0.25, 0.2), horizon);
    } else if (uMode == 3) {
        // 對中心：圓心要落在鏡片正中央，四邊外框要被鏡片邊緣切掉一樣多
        col = vec3(0.06);
        col = mix(col, vec3(0.55), cRings);
        col = mix(col, vec3(0.2, 0.9, 0.3), cCross);
        col = mix(col, vec3(1.0, 0.8, 0.2), cEdge);
    } else {
        // 眼距對準圖（nonius）：左眼只看到十字的上半與左半，右眼只看到下半與右半。
        // 鏡片間距正確時，兩半會在無限遠處接成一個完整的十字。
        col = vec3(0.08);
        col = mix(col, vec3(0.25), gridA * 0.5);
        col = mix(col, vec3(0.9, 0.9, 0.3), ring);      // 兩眼都看得到的融像參考圈
        float vSeg = (uEye == 0) ? step(0.015, ay) * step(ay, 0.22)
                                 : step(-0.22, ay) * step(ay, -0.015);
        float hSeg = (uEye == 0) ? step(-0.22, ax) * step(ax, -0.015)
                                 : step(0.015, ax) * step(ax, 0.22);
        col = mix(col, vec3(1.0), lineAx * vSeg);
        col = mix(col, vec3(1.0), lineAy * hSeg);
    }

    // ---- 選單面板（世界固定；以預乘 alpha 疊加）----
    float df = dot(w, uUiF);
    vec3 qp = w / max(df, 1e-3);
    vec2 pl = vec2(dot(qp, uUiR), dot(qp, uUiU)) / uUiHalf;
    vec4 ui = texture(uUi, vec2(pl.x * 0.5 + 0.5, 0.5 - pl.y * 0.5));
    if (uUiOn && df > 0.0 && abs(pl.x) <= 1.0 && abs(pl.y) <= 1.0) {
        col = col * (1.0 - ui.a) + ui.rgb;
    }
    // 視線準星
    if (uUiOn) {
        float rr = length(te);
        if (rr < 0.007) col = vec3(1.0);
        else if (rr < 0.011) col = vec3(0.0);
    }

    if (!inside) col = vec3(0.0);
    fragColor = vec4(col, 1.0);
}
"""
}
