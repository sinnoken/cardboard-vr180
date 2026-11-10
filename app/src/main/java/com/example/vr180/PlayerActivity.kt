package com.example.vr180

import android.hardware.input.InputManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.example.vr180.data.ProfileStore
import com.example.vr180.input.ControllerInput
import com.example.vr180.input.VrAction
import com.example.vr180.model.LensProfile
import com.example.vr180.model.SceneCalib
import com.example.vr180.model.VerticalAlignment
import com.example.vr180.player.HardwareOnly
import com.example.vr180.render.M3
import com.example.vr180.render.VrRenderer
import com.example.vr180.smb.LocalNetworkPermission
import com.example.vr180.player.RoutingDataSource
import com.example.vr180.source.SidecarIo
import com.example.vr180.source.SidecarRef
import com.example.vr180.tracking.HeadTracker
import com.example.vr180.vrui.MenuItem
import com.example.vr180.vrui.MenuPage
import com.example.vr180.vrui.NavAction
import com.example.vr180.vrui.VrMenu
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * VR 播放畫面。執行緒分工：
 *   主執行緒：ExoPlayer、輸入事件、Activity 生命週期
 *   GL 執行緒：渲染、VR 選單（所有選單狀態只在這裡改）
 *   io：SMB 讀寫與設定檔存取
 */
class PlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URIS = "uris"           // 由高到低排序的版本
        const val EXTRA_LABELS = "labels"
        const val EXTRA_SIDECAR = "sidecar"     // 場景校正檔位置（SidecarRef.encode()）
        const val EXTRA_TITLE = "title"
        private const val CALIB_PANEL_PITCH = -36f
    }

    private lateinit var store: ProfileStore
    private lateinit var tracker: HeadTracker
    private lateinit var renderer: VrRenderer
    private lateinit var glView: GLSurfaceView
    private lateinit var input: ControllerInput
    private val menu = VrMenu()
    private val io = Executors.newSingleThreadExecutor()

    private var player: ExoPlayer? = null
    private var videoSurface: Surface? = null

    private var uris = listOf<String>()
    private var labels = listOf<String>()
    private var sidecar: SidecarRef? = null
    private lateinit var sidecarIo: SidecarIo
    private var videoTitle = ""
    private var variantIndex = 0
    private var failedVariant = -1

    // 狀態文字（主執行緒寫入，GL 執行緒讀取）
    @Volatile private var playing = false
    @Volatile private var decoderName = "—"
    @Volatile private var videoSize = "—"
    @Volatile private var controllerName = "未連線"
    @Volatile private var message = ""

    // 以下只在 GL 執行緒存取
    private var lastHead = M3.identity()
    private var replaceMenu = false
    private var lensBeforeEdit: LensProfile? = null
    private var sceneBeforeEdit: SceneCalib? = null

    // ------------------------------------------------------------------ 生命週期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProfileStore(this)
        uris = intent.getStringArrayListExtra(EXTRA_URIS).orEmpty()
        labels = intent.getStringArrayListExtra(EXTRA_LABELS).orEmpty()
        sidecar = SidecarRef.decode(intent.getStringExtra(EXTRA_SIDECAR))
        sidecarIo = SidecarIo(this)
        videoTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (uris.isEmpty()) { finish(); return }

        val device = store.loadDevice()
        tracker = HeadTracker(this, display?.rotation ?: Surface.ROTATION_90).apply {
            predictionMs = device.predictionMs
            yawDriftDegPerSec = device.yawDriftDegPerSec
        }
        renderer = VrRenderer(tracker) { s ->
            runOnUiThread { videoSurface = s; player?.setVideoSurface(s) }
        }.apply {
            lens = store.loadLenses().active
            pxPerMm = if (device.pxPerMm > 0f) device.pxPerMm else systemPxPerMm()
            uiF = menu.f; uiR = menu.r; uiU = menu.u; uiHalf = menu.half
            frameHook = { head -> onGlFrame(head) }
        }

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContentView(glView)
        goImmersive()

        // Cardboard 按鈕 = 觸碰螢幕：輕點 = 選取，長按 = 重新置中
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { dispatch(VrAction.SELECT, fromTap = true); return true }
            override fun onLongPress(e: MotionEvent) { dispatch(VrAction.RECENTER) }
        })
        glView.setOnTouchListener { _, ev -> gestures.onTouchEvent(ev); true }

        input = ControllerInput { dispatch(it) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = dispatch(VrAction.BACK)
        })
        watchControllers()

        if (uris.any { it.startsWith("smb:") } && !LocalNetworkPermission.granted(this)) {
            message = "缺少區域網路權限，無法讀取 NAS。請回主畫面重新連線以授權。"
        }
        loadScene()
        createPlayer()
        playVariant(0)
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        tracker.start()
        goImmersive()
    }

    override fun onPause() {
        super.onPause()
        input.cancel()
        tracker.stop()
        glView.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release(); player = null
        if (::renderer.isInitialized) renderer.release()
        io.shutdown()
    }

    // ------------------------------------------------------------------ 輸入

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        input.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        input.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event)

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        input.onGenericMotion(event) || super.onGenericMotionEvent(event)

    private fun dispatch(a: VrAction, fromTap: Boolean = false) {
        when (a) {
            VrAction.PLAY_PAUSE -> togglePlay()
            VrAction.SEEK_BACK -> seekBy(-10_000)
            VrAction.SEEK_FWD -> seekBy(10_000)
            else -> glView.queueEvent { handleOnGl(a, fromTap) }
        }
    }

    /** GL 執行緒。 */
    private fun handleOnGl(a: VrAction, fromTap: Boolean) {
        when (a) {
            VrAction.RECENTER -> { tracker.recenter(); replaceMenu = menu.visible }
            VrAction.MENU -> if (menu.visible) closeMenu() else menu.show(mainPage, lastHead)
            else -> {
                if (!menu.visible) {
                    when (a) {
                        VrAction.SELECT, VrAction.BACK -> menu.show(mainPage, lastHead)
                        VrAction.LEFT -> runOnUiThread { seekBy(-10_000) }
                        VrAction.RIGHT -> runOnUiThread { seekBy(10_000) }
                        else -> {}
                    }
                } else if (fromTap && !menu.isGazingAtItem()) {
                    // 看向別處按 Cardboard 按鈕：主選單時關閉選單；校正頁則忽略，避免誤觸「歸零」等按鈕
                    if (menu.page === mainPage) closeMenu()
                } else {
                    menu.handle(a.toNav() ?: return)
                }
            }
        }
    }

    private fun VrAction.toNav(): NavAction? = when (this) {
        VrAction.UP -> NavAction.UP
        VrAction.DOWN -> NavAction.DOWN
        VrAction.LEFT -> NavAction.LEFT
        VrAction.RIGHT -> NavAction.RIGHT
        VrAction.SELECT -> NavAction.SELECT
        VrAction.BACK -> NavAction.BACK
        else -> null
    }

    /** GL 執行緒：每幀由渲染器呼叫。 */
    private fun onGlFrame(head: FloatArray) {
        lastHead = head
        if (replaceMenu) {
            replaceMenu = false
            menu.placeInFront(head, if (menu.page === mainPage) null else CALIB_PANEL_PITCH)
        }
        if (menu.onFrame(head)) renderer.uploadUi(menu.bitmap)
        renderer.uiOn = menu.visible
    }

    private fun watchControllers() {
        val im = getSystemService(INPUT_SERVICE) as InputManager
        fun refresh() {
            val names = im.inputDeviceIds.asList().mapNotNull { im.getInputDevice(it) }
                .filter { d ->
                    d.isExternal && (d.supportsSource(InputDevice.SOURCE_GAMEPAD) ||
                        d.supportsSource(InputDevice.SOURCE_JOYSTICK) ||
                        d.supportsSource(InputDevice.SOURCE_DPAD) ||
                        d.supportsSource(InputDevice.SOURCE_KEYBOARD))
                }
                .map { it.name }.distinct()
            controllerName = if (names.isEmpty()) "未連線" else names.joinToString("、")
            refreshStatus()
        }
        im.registerInputDeviceListener(object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(id: Int) = refresh()
            override fun onInputDeviceRemoved(id: Int) = refresh()
            override fun onInputDeviceChanged(id: Int) = refresh()
        }, null)
        refresh()
    }

    // ------------------------------------------------------------------ 播放

    private fun createPlayer() {
        val renderers = DefaultRenderersFactory(this)
            .setMediaCodecSelector(HardwareOnly.codecSelector)
            .setEnableDecoderFallback(false)
        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setExceedRendererCapabilitiesIfNecessary(false).build())
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 40_000, 2_500, 5_000)
            .build()

        val p = ExoPlayer.Builder(this, renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(RoutingDataSource.Factory(this)))
            .build()

        p.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                if (tracks.containsType(C.TRACK_TYPE_VIDEO) &&
                    !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO, /* allowExceedsCapabilities = */ false)
                ) fallback("硬體解碼器不支援此解析度或格式")
            }

            override fun onPlayerError(error: PlaybackException) {
                when (error.errorCode) {
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                        fallback(error.errorCodeName)
                    else -> {
                        message = "播放錯誤：${error.errorCodeName}"
                        refreshStatus()
                        glView.queueEvent { if (!menu.visible) menu.show(mainPage, lastHead) }
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying; refreshStatus()
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0) { videoSize = "${size.width}×${size.height}"; refreshStatus() }
            }

            override fun onRenderedFirstFrame() { renderer.hasVideo = true }
        })
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                this@PlayerActivity.decoderName = decoderName
                refreshStatus()
            }
        })
        videoSurface?.let(p::setVideoSurface)
        player = p
    }

    private fun playVariant(i: Int) {
        val p = player ?: return
        variantIndex = i
        renderer.hasVideo = false
        decoderName = "—"; videoSize = "—"
        p.setMediaItem(MediaItem.fromUri(uris[i]))
        p.prepare()
        p.playWhenReady = true
        refreshStatus()
    }

    /** 目前版本無法硬解時，自動改播下一個較低解析度的版本。 */
    private fun fallback(reason: String) {
        if (failedVariant == variantIndex) return
        failedVariant = variantIndex
        if (variantIndex + 1 < uris.size) {
            message = "${labels.getOrElse(variantIndex) { "此版本" }} 無法硬解（$reason），改播較低解析度"
            playVariant(variantIndex + 1)
        } else {
            message = "所有版本都無法以硬體解碼播放：$reason"
            player?.stop()
            refreshStatus()
            glView.queueEvent { if (!menu.visible) menu.show(mainPage, lastHead) }
        }
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    private fun seekBy(ms: Long) {
        val p = player ?: return
        p.seekTo((p.currentPosition + ms).coerceAtLeast(0L))
    }

    private fun refreshStatus() {
        val label = labels.getOrElse(variantIndex) { "" }
        menu.status = buildString {
            append(videoTitle)
            if (label.isNotBlank()) append(" · $label（${variantIndex + 1}/${uris.size}）")
            append(" · $videoSize · 解碼器 $decoderName\n控制器：$controllerName")
            if (message.isNotBlank()) append("\n$message")
        }
        if (::glView.isInitialized) glView.queueEvent { menu.invalidate() }
    }

    // ------------------------------------------------------------------ 場景校正存取

    private val sceneKey get() = sidecar?.key ?: uris.first()

    private fun loadScene() {
        io.execute {
            val local = store.loadScene(sceneKey)
            val remote = sidecar?.let { sc ->
                runCatching { sidecarIo.read(sc) }.getOrNull()
                    ?.let { runCatching { SceneCalib.fromJson(JSONObject(it)) }.getOrNull() }
            }
            renderer.scene = remote ?: local ?: SceneCalib()
        }
    }

    private fun saveScene(s: SceneCalib) {
        io.execute {
            store.saveScene(sceneKey, s)
            val sc = sidecar
            message = if (sc == null) "場景校正已存在手機" else runCatching {
                sidecarIo.write(sc, s.toJson().toString(2))
            }.fold({ "場景校正已存到手機與影片資料夾" }, { "影片資料夾無法寫入，場景校正只存在手機" })
            runOnUiThread { refreshStatus() }
        }
    }

    // ------------------------------------------------------------------ 選單頁面（GL 執行緒）

    private fun closeMenu() {
        menu.hide()
        renderer.mode = VrRenderer.Mode.VIDEO
        renderer.guide = false
    }

    private val mainPage: MenuPage by lazy {
        MenuPage(
            title = "VR180 播放器",
            hint = {
                "控制器：方向鍵移動、A 確認、B 返回、X 重新置中。" +
                    "沒有控制器時：看著項目按 Cardboard 按鈕，長按畫面重新置中。"
            },
            rows = listOf(
                listOf(
                    MenuItem.Button({ if (playing) "暫停" else "播放" }) { runOnUiThread { togglePlay() } },
                    MenuItem.Button({ "−10 秒" }) { runOnUiThread { seekBy(-10_000) } },
                    MenuItem.Button({ "+10 秒" }) { runOnUiThread { seekBy(10_000) } },
                ),
                listOf(
                    MenuItem.Button({ "重新置中" }) { tracker.recenter(); replaceMenu = true },
                    MenuItem.Button({ "場景校正" }) { openScene() },
                    MenuItem.Button({ "鏡片校正" }) { openLens() },
                ),
                listOf(
                    MenuItem.Button({ "關閉選單" }) { closeMenu() },
                    MenuItem.Button({ "離開播放" }) { runOnUiThread { finish() } },
                ),
            ),
            onBack = { closeMenu() },
            showStatus = true,
        )
    }

    private fun backToMain() {
        renderer.mode = VrRenderer.Mode.VIDEO
        renderer.guide = false
        menu.open(mainPage)
        menu.placeInFront(lastHead)
    }

    // ---- 場景校正 ----

    private fun openScene() {
        sceneBeforeEdit = renderer.scene
        renderer.guide = true
        menu.open(scenePage)
        menu.placeInFront(lastHead, CALIB_PANEL_PITCH)
    }

    private fun sceneStep(
        label: String, step: Float, get: (SceneCalib) -> Float, set: (SceneCalib, Float) -> SceneCalib,
    ) = MenuItem.Stepper(
        label,
        value = { "%+.2f°".format(get(renderer.scene)) },
        dec = { renderer.scene = set(renderer.scene, wrap180(get(renderer.scene) - step)) },
        inc = { renderer.scene = set(renderer.scene, wrap180(get(renderer.scene) + step)) },
    )

    private val scenePage: MenuPage by lazy {
        MenuPage(
            title = "場景校正（這支影片）",
            hint = {
                "先用『地平線』和『仰俯』讓影片的地平線對齊紅色水平線，『水平方向』決定正前方。" +
                    "看久眼睛吃力或近物重影，再調雙眼垂直差與匯聚。B 鍵放棄修改。"
            },
            rows = listOf(
                listOf(sceneStep("水平方向", 1f, { it.yawDeg }) { s, v -> s.copy(yawDeg = v) }),
                listOf(sceneStep("仰俯", 0.5f, { it.pitchDeg }) { s, v -> s.copy(pitchDeg = v) }),
                listOf(sceneStep("地平線", 0.2f, { it.rollDeg }) { s, v -> s.copy(rollDeg = v) }),
                listOf(sceneStep("雙眼垂直差", 0.05f, { it.eyeVerticalDeg }) { s, v -> s.copy(eyeVerticalDeg = v) }),
                listOf(sceneStep("匯聚", 0.1f, { it.convergenceDeg }) { s, v -> s.copy(convergenceDeg = v) }),
                listOf(
                    MenuItem.Button({ if (renderer.scene.swapEyes) "左右眼：已對調" else "左右眼：正常" }) {
                        renderer.scene = renderer.scene.copy(swapEyes = !renderer.scene.swapEyes)
                    },
                    MenuItem.Button({ if (renderer.guide) "水平參考線：開" else "水平參考線：關" }) {
                        renderer.guide = !renderer.guide
                    },
                ),
                listOf(
                    MenuItem.Button({ "全部歸零" }) { renderer.scene = SceneCalib() },
                    MenuItem.Button({ "儲存並返回" }) {
                        saveScene(renderer.scene)
                        backToMain()
                    },
                ),
            ),
            onBack = {
                sceneBeforeEdit?.let { renderer.scene = it }
                backToMain()
            },
        )
    }

    // ---- 鏡片校正 ----

    private fun openLens() {
        lensBeforeEdit = renderer.lens
        renderer.mode = VrRenderer.Mode.GRID
        menu.open(lensPage)
        menu.placeInFront(lastHead, CALIB_PANEL_PITCH)
    }

    private fun lensStep(
        label: String, step: Float, fmt: String, min: Float, max: Float,
        get: (LensProfile) -> Float, set: (LensProfile, Float) -> LensProfile,
    ) = MenuItem.Stepper(
        label,
        value = { fmt.format(get(renderer.lens)) },
        dec = { renderer.lens = set(renderer.lens, (get(renderer.lens) - step).coerceIn(min, max)) },
        inc = { renderer.lens = set(renderer.lens, (get(renderer.lens) + step).coerceIn(min, max)) },
    )

    private val lensPage: MenuPage by lazy {
        MenuPage(
            title = "鏡片校正",
            hint = {
                "設定檔：${renderer.lens.name}\n" + if (renderer.mode == VrRenderer.Mode.NONIUS)
                    "眼距：左眼看到十字的上半與左半，右眼看到下半與右半。調『鏡片間距』直到兩段直線接成一直線、" +
                        "橫線也對齊；黃圈是兩眼共同的參考。"
                else
                    "畸變：看正前方調 k1 讓中段線條變直，再看邊緣調 k2。最後左右轉頭，方格應像釘在牆上；" +
                        "若方格跟著頭跑或反向滑動，調『螢幕到鏡片』。B 鍵放棄修改。"
            },
            rows = listOf(
                listOf(
                    MenuItem.Button({ mark(renderer.mode == VrRenderer.Mode.NONIUS) + "眼距對準圖" }) {
                        renderer.mode = VrRenderer.Mode.NONIUS
                    },
                    MenuItem.Button({ mark(renderer.mode == VrRenderer.Mode.GRID) + "方格牆" }) {
                        renderer.mode = VrRenderer.Mode.GRID
                    },
                ),
                listOf(lensStep("鏡片間距", 0.2f, "%.1f mm", 45f, 80f, { it.interLensMm }) { l, v -> l.copy(interLensMm = v) }),
                listOf(lensStep("螢幕到鏡片", 0.2f, "%.1f mm", 20f, 70f, { it.screenToLensMm }) { l, v -> l.copy(screenToLensMm = v) }),
                listOf(
                    MenuItem.Stepper(
                        "鏡片中心高度",
                        value = {
                            if (renderer.lens.alignment == VerticalAlignment.CENTER) "（置中）"
                            else "%.1f mm".format(renderer.lens.lensHeightMm)
                        },
                        dec = { renderer.lens = renderer.lens.copy(lensHeightMm = (renderer.lens.lensHeightMm - 0.2f).coerceAtLeast(0f)) },
                        inc = { renderer.lens = renderer.lens.copy(lensHeightMm = renderer.lens.lensHeightMm + 0.2f) },
                    ),
                    MenuItem.Button({
                        "基準：" + when (renderer.lens.alignment) {
                            VerticalAlignment.BOTTOM -> "下緣"
                            VerticalAlignment.CENTER -> "置中"
                            VerticalAlignment.TOP -> "上緣"
                        }
                    }) {
                        val all = VerticalAlignment.entries
                        renderer.lens = renderer.lens.copy(
                            alignment = all[(renderer.lens.alignment.ordinal + 1) % all.size])
                    },
                ),
                listOf(
                    lensStep("k1", 0.01f, "%.2f", -0.5f, 1.5f, { it.k1 }) { l, v -> l.copy(k1 = v) },
                    lensStep("k2", 0.01f, "%.2f", -0.5f, 1.5f, { it.k2 }) { l, v -> l.copy(k2 = v) },
                ),
                listOf(lensStep("可視半角", 1f, "%.0f°", 30f, 75f, { it.halfFovDeg }) { l, v -> l.copy(halfFovDeg = v) }),
                listOf(
                    MenuItem.Button({ "還原" }) { lensBeforeEdit?.let { renderer.lens = it } },
                    MenuItem.Button({ "Cardboard 預設值" }) {
                        // 只換數值，保留目前設定檔的名稱；按「儲存」才會寫入
                        val cur = renderer.lens
                        renderer.lens = LensProfile.cardboardDefault().copy(id = cur.id, name = cur.name)
                    },
                    MenuItem.Button({ "儲存並返回" }) {
                        val l = renderer.lens
                        // 預設檔永遠不會被修改：從預設檔出發的校正結果另存成新的設定檔
                        val toSave = if (l.isPreset) l.copy(
                            id = java.util.UUID.randomUUID().toString(),
                            name = "我的眼鏡（以 ${l.name} 為基礎）",
                        ) else l
                        renderer.lens = toSave
                        io.execute { store.upsertActive(toSave) }
                        message = "鏡片設定檔「${toSave.name}」已儲存並啟用"
                        runOnUiThread { refreshStatus() }
                        backToMain()
                    },
                ),
            ),
            onBack = {
                lensBeforeEdit?.let { renderer.lens = it }
                backToMain()
            },
        )
    }

    // ------------------------------------------------------------------ 工具

    private fun mark(on: Boolean) = if (on) "● " else "○ "

    private fun wrap180(v: Float): Float {
        var x = v
        while (x > 180f) x -= 360f
        while (x < -180f) x += 360f
        return x
    }

    private fun systemPxPerMm(): Float {
        val dm = resources.displayMetrics
        return (dm.xdpi + dm.ydpi) / 2f / 25.4f
    }

    private fun goImmersive() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 讓畫面延伸到瀏海/挖孔區域，螢幕中心才會等於實體螢幕中心
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
