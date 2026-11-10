package com.example.vr180.render

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.Surface
import com.example.vr180.model.LensProfile
import com.example.vr180.model.SceneCalib
import com.example.vr180.model.VerticalAlignment
import com.example.vr180.tracking.HeadTracker
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.tan

/**
 * 硬體解碼器 → SurfaceTexture（OES 外部貼圖，零複製）→ 這裡的著色器 → 螢幕。
 * 所有參數都以 @Volatile 不可變物件交換；選單邏輯在 GL 執行緒的 [frameHook] 中執行。
 */
class VrRenderer(
    private val tracker: HeadTracker,
    private val onVideoSurface: (Surface) -> Unit,
) : GLSurfaceView.Renderer {

    enum class Mode(val id: Int) { VIDEO(0), GRID(1), NONIUS(2), CENTER(3) }

    @Volatile var lens: LensProfile = LensProfile.cardboardDefault()
    @Volatile var scene: SceneCalib = SceneCalib()
    @Volatile var pxPerMm: Float = 16f
    @Volatile var mode: Mode = Mode.VIDEO
    @Volatile var guide: Boolean = false
    @Volatile var hasVideo: Boolean = false

    /** 每幀在 GL 執行緒呼叫，傳入頭部矩陣；選單在這裡做視線判定與重繪。 */
    var frameHook: ((head: FloatArray) -> Unit)? = null

    // 選單面板（僅限 GL 執行緒存取）
    var uiOn = false
    var uiF = floatArrayOf(0f, 0f, -1f)
    var uiR = floatArrayOf(1f, 0f, 0f)
    var uiU = floatArrayOf(0f, 1f, 0f)
    var uiHalf = floatArrayOf(0.5f, 0.42f)

    private var program = 0
    private var videoTex = 0
    private var uiTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    @Volatile private var frameAvailable = false
    private val texMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private var width = 1
    private var height = 1
    private val loc = HashMap<String, Int>()

    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        releaseSurface()
        program = buildProgram(Shaders.VERTEX, Shaders.FRAGMENT)
        listOf(
            "uVideo", "uUi", "uTexMatrix", "uWorldFromEye", "uContentFromEye", "uLensCenter",
            "uPxPerTan", "uK", "uMaxTan", "uVideoOffset", "uEye", "uMode", "uHasVideo", "uGuide",
            "uUiOn", "uUiF", "uUiR", "uUiU", "uUiHalf",
        ).forEach { loc[it] = GLES30.glGetUniformLocation(program, it) }

        val ids = IntArray(2)
        GLES30.glGenTextures(2, ids, 0)
        videoTex = ids[0]; uiTex = ids[1]

        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, uiTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        // 先放一個 1x1 透明貼圖
        uploadUi(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))

        val st = SurfaceTexture(videoTex).apply { setOnFrameAvailableListener { frameAvailable = true } }
        surfaceTexture = st
        surface = Surface(st).also(onVideoSurface)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
    }

    /** 只能在 GL 執行緒呼叫。 */
    fun uploadUi(bmp: Bitmap) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, uiTex)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (frameAvailable) {
            frameAvailable = false
            surfaceTexture?.updateTexImage()
            surfaceTexture?.getTransformMatrix(texMatrix)
        }
        val head = tracker.headMatrix(System.nanoTime())
        frameHook?.invoke(head)

        val l = lens; val s = scene; val m = mode
        val pxmm = pxPerMm
        val halfSep = l.interLensMm / 2f * pxmm
        val cy = when (l.alignment) {
            VerticalAlignment.BOTTOM -> l.lensHeightMm * pxmm
            VerticalAlignment.TOP -> height - l.lensHeightMm * pxmm
            VerticalAlignment.CENTER -> height / 2f + l.lensHeightMm * pxmm
        }
        val offX = l.offsetXMm * pxmm

        // 場景校正：內容 ← 世界 = Rs⁻¹；單眼修正（垂直落差、匯聚）各分一半給兩眼
        val rs = M3.mul(M3.rotY(rad(s.yawDeg)), M3.rotX(rad(s.pitchDeg)), M3.rotZ(rad(s.rollDeg)))
        val contentFromWorld = M3.transpose(rs)

        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex)
        GLES30.glUniform1i(loc["uVideo"]!!, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, uiTex)
        GLES30.glUniform1i(loc["uUi"]!!, 1)

        GLES30.glUniformMatrix4fv(loc["uTexMatrix"]!!, 1, false, texMatrix, 0)
        GLES30.glUniformMatrix3fv(loc["uWorldFromEye"]!!, 1, true, head, 0)
        GLES30.glUniform1f(loc["uPxPerTan"]!!, l.screenToLensMm * pxmm)
        GLES30.glUniform2f(loc["uK"]!!, l.k1, l.k2)
        GLES30.glUniform1f(loc["uMaxTan"]!!, tan(rad(l.halfFovDeg)))
        GLES30.glUniform1i(loc["uMode"]!!, m.id)
        GLES30.glUniform1i(loc["uHasVideo"]!!, if (hasVideo) 1 else 0)
        GLES30.glUniform1i(loc["uGuide"]!!, if (guide) 1 else 0)
        GLES30.glUniform1i(loc["uUiOn"]!!, if (uiOn) 1 else 0)
        GLES30.glUniform3fv(loc["uUiF"]!!, 1, uiF, 0)
        GLES30.glUniform3fv(loc["uUiR"]!!, 1, uiR, 0)
        GLES30.glUniform3fv(loc["uUiU"]!!, 1, uiU, 0)
        GLES30.glUniform2fv(loc["uUiHalf"]!!, 1, uiHalf, 0)

        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad)

        for (eye in 0..1) {
            val sign = if (eye == 0) 1f else -1f
            val adj = M3.mul(
                M3.rotY(rad(sign * s.convergenceDeg / 2f)),
                M3.rotX(rad(sign * s.eyeVerticalDeg / 2f)),
            )
            val contentFromEye = M3.mul(adj, contentFromWorld, head)
            val cx = width / 2f + offX + (if (eye == 0) -halfSep else halfSep)
            val useRightHalf = (eye == 1) != s.swapEyes

            GLES30.glViewport(eye * width / 2, 0, width / 2, height)
            GLES30.glUniform1i(loc["uEye"]!!, eye)
            GLES30.glUniform2f(loc["uLensCenter"]!!, cx, cy)
            GLES30.glUniform1f(loc["uVideoOffset"]!!, if (useRightHalf) 0.5f else 0f)
            GLES30.glUniformMatrix3fv(loc["uContentFromEye"]!!, 1, true, contentFromEye, 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
    }

    fun release() = releaseSurface()

    private fun releaseSurface() {
        surface?.release(); surface = null
        surfaceTexture?.release(); surfaceTexture = null
    }

    private fun rad(deg: Float) = Math.toRadians(deg.toDouble()).toFloat()

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val sh = GLES30.glCreateShader(type)
            GLES30.glShaderSource(sh, src)
            GLES30.glCompileShader(sh)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "著色器編譯失敗：" + GLES30.glGetShaderInfoLog(sh) }
            return sh
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "著色器連結失敗：" + GLES30.glGetProgramInfoLog(p) }
        return p
    }
}
