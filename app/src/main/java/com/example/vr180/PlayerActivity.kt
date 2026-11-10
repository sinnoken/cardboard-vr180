package com.example.vr180

import android.hardware.input.InputManager
import android.media.AudioManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.example.vr180.data.ProfileStore
import com.example.vr180.input.ControllerChoice
import com.example.vr180.input.ControllerInput
import com.example.vr180.input.VrAction
import com.example.vr180.model.LensProfile
import com.example.vr180.model.SceneCalib
import com.example.vr180.model.VerticalAlignment
import com.example.vr180.player.HardwareOnly
import com.example.vr180.player.Playlist
import com.example.vr180.player.changeLensAlignment
import com.example.vr180.player.fmtTime
import com.example.vr180.player.moveLensVertical
import com.example.vr180.player.seekStepMs
import com.example.vr180.player.stepsFor
import com.example.vr180.player.wrap180
import com.example.vr180.render.M3
import com.example.vr180.render.VrRenderer
import com.example.vr180.smb.LocalNetworkPermission
import com.example.vr180.smb.SmbRepository
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
import kotlin.math.atan2
import kotlin.math.sqrt

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
        const val EXTRA_PLAYLIST_INDEX = "playlistIndex"    // 在 Playlist.items 裡的位置，-1 = 沒有清單
        private const val CALIB_PANEL_PITCH = -36f
        private const val SEEK_HOLD_INTERVAL_MS = 400L
    }

    private lateinit var store: ProfileStore
    private lateinit var tracker: HeadTracker
    private lateinit var renderer: VrRenderer
    private lateinit var glView: GLSurfaceView
    private lateinit var input: ControllerInput
    private var controllerChoice = ControllerChoice.AUTO
    private val menu = VrMenu()
    private val io = Executors.newSingleThreadExecutor()
    private var playlistIndex = -1

    private var player: ExoPlayer? = null
    private lateinit var trackSelector: DefaultTrackSelector
    private var videoSurface: Surface? = null

    private var uris = listOf<String>()
    private var labels = listOf<String>()
    private var sidecar: SidecarRef? = null
    private lateinit var sidecarIo: SidecarIo
    @Volatile private var videoTitle = ""     // 主執行緒寫（切換影片），GL 執行緒畫標題時讀
    private var variantIndex = 0
    private var failedVariant = -1

    // 狀態文字（主執行緒寫入，GL 執行緒讀取）
    @Volatile private var playing = false
    @Volatile private var decoderName = "—"
    @Volatile private var videoSize = "—"
    @Volatile private var controllerName = "未連線"
    @Volatile private var message = ""
    @Volatile private var playerFailed = false      // 播放錯誤：主選單的播放鍵變成「重試」
    @Volatile private var playerEnded = false       // 播完：播放鍵變成「重播」
    @Volatile private var buffering = false
    @Volatile private var posMs = 0L
    @Volatile private var durMs = 0L
    @Volatile private var sceneLoaded = false
    @Volatile private var started = false           // 戴好眼鏡按下開始之前不播放、不置中

    private val ui = Handler(Looper.getMainLooper())
    private var deviceListener: InputManager.InputDeviceListener? = null
    private val clock = object : Runnable {
        override fun run() {
            player?.let { p ->
                val pos = p.currentPosition
                val dur = p.duration.coerceAtLeast(0L)
                if (pos / 1000 != posMs / 1000 || dur != durMs) { posMs = pos; durMs = dur; refreshStatus() }
            }
            ui.postDelayed(this, 500)
        }
    }

    // 以下只在 GL 執行緒存取
    private var lastHead = M3.identity()
    private var replaceMenu = false
    private var lensBeforeEdit: LensProfile? = null
    private var sceneBeforeEdit: SceneCalib? = null
    private var aiming = false                      // 「設定正前方」等待使用者按鍵

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
        // 程序被回收後 Playlist 是空的，或已換成別的清單：對不上就當作沒有清單
        playlistIndex = intent.getIntExtra(EXTRA_PLAYLIST_INDEX, -1)
            .takeIf { Playlist.items.getOrNull(it)?.uris == uris } ?: -1

        val device = store.loadDevice()
        tracker = HeadTracker(this, display?.rotation ?: Surface.ROTATION_90).apply {
            predictionMs = device.predictionMs
            yawDriftDegPerSec = device.yawDriftDegPerSec
            fullRecenter = device.fullRecenter
        }
        renderer = VrRenderer(tracker) { s ->
            // GL 執行緒：GL context 重建後選單貼圖被換成 1×1 透明，要重畫一次，否則開著的選單會看不見
            menu.invalidate()
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

        controllerChoice = store.loadControllerChoice()
        input = ControllerInput({ controllerChoice }) { a, held -> dispatch(a, heldMs = held) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = dispatch(VrAction.BACK)
        })
        watchControllers()

        if (!tracker.available) {
            message = "這支手機找不到頭部追蹤感測器，畫面不會跟著轉頭"
        }
        // 程序被系統回收後直接回到播放器時，SmbRepository 是空的；不設定就會被當成「連不到 NAS」
        if (uris.any { it.startsWith("smb:") }) SmbRepository.configure(store.loadConnection())
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
        ui.post(clock)
    }

    override fun onPause() {
        super.onPause()
        input.cancel()
        ui.removeCallbacks(clock)
        tracker.stop()
        glView.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(clock)
        deviceListener?.let { (getSystemService(INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(it) }
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

    private fun dispatch(a: VrAction, fromTap: Boolean = false, heldMs: Long = 0L) {
        if (!started) {
            if (heldMs == 0L) glView.queueEvent { if (a == VrAction.BACK) runOnUiThread { finish() } else startPlayback() }
            return
        }
        when (a) {
            VrAction.PLAY_PAUSE -> playButton()
            VrAction.VOL_UP -> adjustVolume(AudioManager.ADJUST_RAISE)
            VrAction.VOL_DOWN -> adjustVolume(AudioManager.ADJUST_LOWER)
            else -> glView.queueEvent { handleOnGl(a, fromTap, heldMs) }
        }
    }

    /** GL 執行緒。 */
    private fun handleOnGl(a: VrAction, fromTap: Boolean, heldMs: Long) {
        when (a) {
            // 鏡片校正時倒退／快轉鍵用來切換測試圖樣（面板收起時也可以），其他時候跳 10 秒
            VrAction.SEEK_BACK, VrAction.SEEK_FWD -> {
                val d = if (a == VrAction.SEEK_FWD) 1 else -1
                if (menu.page === lensPage) cyclePattern(d) else runOnUiThread { seekBy(d * 10_000L) }
            }
            VrAction.TRIGGER -> when {
                menu.concealed -> showMenu()
                menu.visible || aiming -> handleOnGl(VrAction.SELECT, fromTap, heldMs)
                else -> runOnUiThread { playButton() }
            }
            VrAction.RECENTER -> { tracker.recenter(); replaceMenu = menu.visible }
            // 校正頁按選單鍵只收起面板、保留修改，再按一次回到同一頁；放棄修改只有 B 與「取消」
            VrAction.MENU -> when {
                menu.concealed -> showMenu()
                menu.visible && isCalibPage(menu.page) -> {
                    if (aiming) { aiming = false; menu.open(scenePage) }
                    menu.conceal()
                }
                menu.visible -> closeMenu()
                else -> showMenu()
            }
            else -> {
                if (menu.concealed && (a == VrAction.SELECT || a == VrAction.BACK)) {
                    showMenu()
                } else if (aiming && a == VrAction.SELECT && !(fromTap && menu.isGazingAtItem())) {
                    captureFront()
                } else if (!menu.visible) {
                    when (a) {
                        VrAction.SELECT, VrAction.BACK -> showMenu()
                        // 鏡片校正面板收起時畫面是測試圖樣，左右改成切換圖樣（單手配置沒有倒退／快轉鍵）
                        VrAction.LEFT, VrAction.RIGHT -> if (menu.concealed && menu.page === lensPage) {
                            if (heldMs == 0L) cyclePattern(if (a == VrAction.LEFT) -1 else 1)
                        } else {
                            holdSeek(if (a == VrAction.LEFT) -1 else 1, heldMs)
                        }
                        // 上下：上一支／下一支。按住不連續切換；校正面板收起時不切，免得修改跟著影片一起丟掉
                        VrAction.UP, VrAction.DOWN -> if (heldMs == 0L && !menu.concealed) {
                            runOnUiThread { switchVideo(if (a == VrAction.UP) -1 else 1) }
                        }
                        else -> {}
                    }
                } else if (fromTap && !menu.isGazingAtItem()) {
                    // 看向別處按 Cardboard 按鈕：主選單時關閉選單；校正頁則忽略，避免誤觸「歸零」等按鈕
                    if (menu.page === mainPage) closeMenu()
                } else {
                    val nav = a.toNav() ?: return
                    val horizontal = a == VrAction.LEFT || a == VrAction.RIGHT
                    // 進度列的左右跟選單關著時走同一個函式，按住的節奏與距離完全一樣
                    if (horizontal && menu.focusedIsSeek()) {
                        holdSeek(if (a == VrAction.LEFT) -1 else 1, heldMs)
                        menu.invalidate()
                        return
                    }
                    // 數值項目按住左右會加速：越按越多格，校正時不用一格一格慢慢調
                    val times = if ((a == VrAction.LEFT || a == VrAction.RIGHT) && menu.focusedIsStepper())
                        stepsFor(heldMs) else 1
                    repeat(times) { menu.handle(nav) }
                }
            }
        }
    }

    /** GL 執行緒：叫出選單。校正頁被收起時回到那一頁，否則開主選單。 */
    private fun showMenu() {
        if (menu.concealed) menu.reveal(lastHead, CALIB_PANEL_PITCH)
        else menu.show(mainPage, lastHead)
    }

    private fun isCalibPage(p: MenuPage?) =
        p === lensPage || p === scenePage || p === sceneAdvancedPage || p === aimPage

    private val patterns = listOf(VrRenderer.Mode.CENTER, VrRenderer.Mode.NONIUS, VrRenderer.Mode.GRID)

    /** GL 執行緒：鏡片校正的測試圖樣往前／往後切換一個。 */
    private fun cyclePattern(d: Int) {
        val i = patterns.indexOf(renderer.mode).coerceAtLeast(0)
        renderer.mode = patterns[(i + d + patterns.size) % patterns.size]
        menu.invalidate()
    }

    private var lastHeldSeekMs = 0L

    /** 剛按下一定跳；按住時至少間隔 SEEK_HOLD_INTERVAL_MS 才再跳一次。 */
    private fun seekAllowed(heldMs: Long): Boolean {
        if (heldMs == 0L) { lastHeldSeekMs = 0L; return true }
        if (heldMs - lastHeldSeekMs < SEEK_HOLD_INTERVAL_MS) return false
        lastHeldSeekMs = heldMs
        return true
    }

    /**
     * GL 執行緒：倒退／快轉鍵或左右鍵按下與按住。按住時最多每 SEEK_HOLD_INTERVAL_MS 跳一次，
     * 距離依按住時間（seekStepMs）；按住期間從上一次的目標往下算（見 seekBy）。
     */
    private fun holdSeek(dir: Int, heldMs: Long) {
        if (!seekAllowed(heldMs)) return
        val ms = seekStepMs(heldMs) * dir
        val continuing = heldMs > 0L
        runOnUiThread { seekBy(ms, continuing) }
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
        if (!started) {
            // 還沒開始：提示面板跟著視線，戴上眼鏡時一定看得到
            if (menu.page !== startPage) menu.show(startPage, head)
            menu.placeInFront(head)
        }
        if (replaceMenu) {
            replaceMenu = false
            menu.placeInFront(head, if (isCalibPage(menu.page)) CALIB_PANEL_PITCH else null)
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
                .map { "${it.name}（${controllerChoice.resolve(it.name).label}）" }.distinct()
            controllerName = if (names.isEmpty()) "未連線" else names.joinToString("、")
            refreshStatus()
        }
        val l = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(id: Int) = refresh()
            override fun onInputDeviceRemoved(id: Int) = refresh()
            override fun onInputDeviceChanged(id: Int) = refresh()
        }
        deviceListener = l
        im.registerInputDeviceListener(l, null)
        refresh()
    }

    // ------------------------------------------------------------------ 播放

    private fun createPlayer() {
        val renderers = DefaultRenderersFactory(this)
            .setMediaCodecSelector(HardwareOnly.codecSelector)
            // 初始化失敗時改試優先順序較低的解碼器。清單來自上面的選擇器，只有硬體解碼器，不會繞到軟體解碼
            .setEnableDecoderFallback(true)
        trackSelector = DefaultTrackSelector(this)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 40_000, 2_500, 5_000)
            .build()

        val p = ExoPlayer.Builder(this, renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(RoutingDataSource.Factory(this)))
            .build()
        // 8K HEVC 的關鍵幀間隔常有好幾秒：精確跳轉要從前一個關鍵幀解到目標，按住連續跳時幾乎看不到畫面。
        // 跳到最近的關鍵幀快很多，誤差一兩秒對看片沒有影響
        p.setSeekParameters(SeekParameters.CLOSEST_SYNC)

        p.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                if (!tracks.containsType(C.TRACK_TYPE_VIDEO) ||
                    tracks.isTypeSupported(C.TRACK_TYPE_VIDEO, /* allowExceedsCapabilities = */ false)
                ) return
                if (isLastVariant()) {
                    message = "超出硬體解碼器宣告的能力，仍嘗試播放"
                    refreshStatus()
                } else {
                    fallback("硬體解碼器宣告不支援此解析度或格式")
                }
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
                        playerFailed = true
                        message = playbackErrorText(error)
                        refreshStatus()
                        glView.queueEvent { if (!menu.visible) showMenu() }
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying; refreshStatus()
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                playerEnded = state == Player.STATE_ENDED
                if (state == Player.STATE_READY) playerFailed = false
                refreshStatus()
                if (playerEnded) glView.queueEvent { if (!menu.visible) showMenu() }
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

    /** keepFrame：切換影片或降版時保留上一個畫面直到新的第一幀，不先變黑（避免閃一下）。 */
    private fun playVariant(i: Int, keepFrame: Boolean = false) {
        val p = player ?: return
        variantIndex = i
        // 解碼器宣告的上限常比實際能力保守（例如宣告 8K30，實際解得了 8K60）。
        // 還有較低版本時照宣告降版；已是最後一個版本就照樣嘗試，真的解不了會走 onPlayerError。
        trackSelector.setParameters(
            trackSelector.buildUponParameters().setExceedRendererCapabilitiesIfNecessary(isLastVariant()).build()
        )
        if (!keepFrame) renderer.hasVideo = false
        seekTarget = -1L
        decoderName = "—"; videoSize = "—"
        p.setMediaItem(MediaItem.fromUri(uris[i]))
        p.prepare()
        p.playWhenReady = started
        refreshStatus()
    }

    private fun isLastVariant() = variantIndex == uris.lastIndex

    /** 目前版本無法硬解時，自動改播下一個較低解析度的版本。 */
    private fun fallback(reason: String) {
        if (failedVariant == variantIndex) return
        failedVariant = variantIndex
        if (variantIndex + 1 < uris.size) {
            message = "${labels.getOrElse(variantIndex) { "此版本" }} 無法硬解（$reason），改播較低解析度"
            // 播到一半才失敗（過熱、暫時性錯誤）時從原位置接著播，不回到片頭
            val pos = player?.currentPosition ?: 0L
            playVariant(variantIndex + 1, keepFrame = true)
            if (pos > 0) player?.seekTo(pos)
        } else {
            message = "這支手機無法硬體解碼這支影片的任何版本（$reason）。請轉成較低解析度，例如 5.7K。"
            playerFailed = true
            player?.stop()
            refreshStatus()
            glView.queueEvent { if (!menu.visible) showMenu() }
        }
    }

    /** 主選單的播放鍵：依狀態是播放／暫停、重試或重播。 */
    private fun playButton() {
        val p = player ?: return
        when {
            playerFailed -> {
                val pos = p.currentPosition
                playerFailed = false; failedVariant = -1; message = ""
                playVariant(variantIndex)
                if (pos > 0) p.seekTo(pos)
            }
            playerEnded -> { p.seekTo(0); p.play() }
            else -> togglePlay()
        }
    }

    private fun playbackErrorText(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "連不到 NAS（網路中斷或 NAS 關機）"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "NAS 回應逾時，網路可能太慢或不穩"
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "找不到影片檔，可能已被移動或刪除"
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "沒有讀取這個檔案的權限"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "影片檔損壞或格式不支援"
        else -> "播放錯誤（${e.errorCodeName}）"
    } + "。可以按「重試」。"

    /** 主執行緒：切到清單裡的上一支（-1）或下一支（+1）。 */
    private fun switchVideo(d: Int) {
        val items = Playlist.items
        val i = playlistIndex + d
        if (playlistIndex < 0 || i !in items.indices) {
            message = when {
                playlistIndex < 0 -> "沒有影片清單，請從清單重新開啟"
                d > 0 -> "已經是最後一支"
                else -> "已經是第一支"
            }
            refreshStatus()
            return
        }
        val item = items[i]
        playlistIndex = i
        uris = item.uris
        labels = item.labels
        sidecar = SidecarRef.decode(item.sidecar)
        videoTitle = item.title
        failedVariant = -1; playerFailed = false; playerEnded = false; message = ""
        sceneLoaded = false
        loadScene()
        playVariant(0, keepFrame = true)
    }

    /** 主執行緒：調整媒體音量。VR 裡看不到系統音量面板，所以不顯示，改寫在選單狀態列。 */
    private fun adjustVolume(direction: Int) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        message = "音量 ${am.getStreamVolume(AudioManager.STREAM_MUSIC)} / ${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}"
        refreshStatus()
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    /** 主執行緒：上一次跳轉的目標位置，按住連續跳時用。 */
    private var seekTarget = -1L

    /**
     * 主執行緒：相對跳轉。播放器設成跳到最近的關鍵幀（CLOSEST_SYNC），位置可能前後差一兩秒；
     * continuing（按住連續跳）時從上一次的目標往下算，不會因為對齊關鍵幀而卡在同一個位置。
     */
    private fun seekBy(ms: Long, continuing: Boolean = false) {
        val p = player ?: return
        val base = if (continuing && seekTarget >= 0) seekTarget else p.currentPosition
        var target = (base + ms).coerceAtLeast(0L)
        if (p.duration > 0) target = target.coerceAtMost(p.duration)
        seekTarget = target
        p.seekTo(target)
    }

    private fun refreshStatus() {
        val label = labels.getOrElse(variantIndex) { "" }
        // 最重要的放前面：面板文字最多 5 行，超過的會被截掉
        menu.status = buildString {
            if (message.isNotBlank()) append(message).append('\n')
            append(fmtTime(posMs)).append(" / ").append(if (durMs > 0) fmtTime(durMs) else "--:--")
            if (buffering) append(" · 載入中…")
            if (label.isNotBlank()) append(" · $label（${variantIndex + 1}/${uris.size}）")
            append(" · $videoSize · 解碼器 $decoderName")
            append("\n控制器：$controllerName")
            if (::renderer.isInitialized && renderer.scene != SceneCalib()) append(" · 已套用這支影片的校正")
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
            sceneLoaded = true
            runOnUiThread { refreshStatus() }
        }
    }

    private fun saveScene(s: SceneCalib) {
        io.execute {
            store.saveScene(sceneKey, s)
            val sc = sidecar
            message = if (sc == null) "這支影片的校正已存在手機" else runCatching {
                sidecarIo.write(sc, s.toJson().toString(2))
            }.fold({ "這支影片的校正已存到手機與影片資料夾" }, { "影片資料夾無法寫入，校正只存在手機" })
            runOnUiThread { refreshStatus() }
        }
    }

    // ------------------------------------------------------------------ 選單頁面（GL 執行緒）

    private fun closeMenu() {
        menu.hide()
        renderer.mode = VrRenderer.Mode.VIDEO
        renderer.guide = false
        aiming = false
    }

    /** 校正頁沒按「儲存」就離開：還原修改並提示。 */
    private fun discardCalibration() {
        when (menu.page) {
            lensPage -> lensBeforeEdit?.let { renderer.lens = it }
            scenePage, sceneAdvancedPage, aimPage -> sceneBeforeEdit?.let { renderer.scene = it }
            else -> return
        }
        message = "校正沒有儲存，已還原"
        runOnUiThread { refreshStatus() }
    }

    // ---- 開始前：戴好眼鏡再置中、開始播放 ----

    private val startPage: MenuPage by lazy {
        MenuPage(
            title = { videoTitle.ifBlank { "VR180 播放器" } },
            hint = { "戴好眼鏡、看向正前方，按任意鍵或點一下螢幕開始播放。" },
            rows = listOf(listOf(MenuItem.Button({ "開始播放" }) { startPlayback() })),
            onBack = { runOnUiThread { finish() } },
            showStatus = true,
        )
    }

    /** GL 執行緒。 */
    private fun startPlayback() {
        if (started) return
        started = true
        tracker.recenter()
        closeMenu()
        runOnUiThread { player?.playWhenReady = true }
    }

    // ---- 主選單：只放每次看片都會用到的 ----

    private val mainPage: MenuPage by lazy {
        MenuPage(
            title = { videoTitle.ifBlank { "VR180 播放器" } },
            hint = { "沒有控制器：看著項目點螢幕；看別處點一下關閉選單；長按畫面重新置中。" },
            rows = listOf(
                listOf(
                    MenuItem.Button({
                        when {
                            playerFailed -> "重試"
                            playerEnded -> "重播"
                            playing -> "暫停"
                            else -> "播放"
                        }
                    }) { runOnUiThread { playButton() } },
                    MenuItem.Button({ "−10 秒" }) { runOnUiThread { seekBy(-10_000) } },
                    MenuItem.Button({ "+10 秒" }) { runOnUiThread { seekBy(10_000) } },
                ),
                // 進度列放在播放鍵下面：打開選單時焦點仍在播放鍵，按確認不會誤跳
                listOf(
                    MenuItem.Seek(
                        label = { fmtTime(posMs) + " / " + if (durMs > 0) fmtTime(durMs) else "--:--" },
                        fraction = { if (durMs > 0) posMs.toFloat() / durMs else -1f },
                        step = { d -> runOnUiThread { seekBy(d * 10_000L) } },
                        seekTo = { f ->
                            val dur = durMs
                            if (dur > 0) runOnUiThread { player?.seekTo((f * dur).toLong()) }
                        },
                    ),
                ),
                listOf(
                    MenuItem.Button({ "重新置中" }) { tracker.recenter(); replaceMenu = true },
                    MenuItem.Button({ "設定…" }) { menu.open(settingsPage) },
                    MenuItem.Button({ "離開播放" }) { runOnUiThread { finish() } },
                ),
            ),
            onBack = { closeMenu() },
            showStatus = true,
        )
    }

    private val settingsPage: MenuPage by lazy {
        MenuPage(
            title = { "設定" },
            hint = {
                "鏡片校正：換眼鏡或手機時做一次。這支影片的校正：只有這支片拍歪時才需要。" +
                    "置中方式：坐著、站著用「只轉水平」；躺著、側躺用「全方向」，換姿勢後再置中一次。"
            },
            rows = listOf(
                listOf(MenuItem.Button({ "鏡片校正" }) { openLens() }),
                listOf(MenuItem.Button({ "這支影片的校正" }) { openScene() }),
                listOf(MenuItem.Button({
                    if (tracker.fullRecenter) "置中方式：全方向" else "置中方式：只轉水平"
                }) { toggleFullRecenter() }),
                listOf(MenuItem.Button({ "返回" }) { menu.open(mainPage) }),
            ),
            onBack = { menu.open(mainPage) },
        )
    }

    /** GL 執行緒：切換置中方式，記住並馬上用新方式重新置中。 */
    private fun toggleFullRecenter() {
        val full = !tracker.fullRecenter
        tracker.fullRecenter = full
        tracker.recenter()
        replaceMenu = true
        io.execute { store.saveDevice(store.loadDevice().copy(fullRecenter = full)) }
    }

    private fun backToMain() {
        renderer.mode = VrRenderer.Mode.VIDEO
        renderer.guide = false
        aiming = false
        menu.open(mainPage)
        menu.placeInFront(lastHead)
    }

    // ---- 這支影片的校正（場景校正）----

    private fun openScene() {
        if (!sceneLoaded) {
            message = "這支影片的校正還在讀取，請稍候再試"
            runOnUiThread { refreshStatus() }
            return
        }
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
            title = { "這支影片的校正" },
            hint = {
                "畫面中的主角或地平線不在正前方：按『設定正前方』。地平線左右歪：調『地平線』，" +
                    "讓影片的地平線對齊紅色水平線。"
            },
            rows = listOf(
                listOf(MenuItem.Button({ "設定正前方（看著目標點一下）" }) {
                    aiming = true
                    menu.open(aimPage)
                }),
                listOf(sceneStep("地平線", 0.2f, { it.rollDeg }) { s, v -> s.copy(rollDeg = v) }),
                listOf(
                    MenuItem.Button({ "進階…" }) { menu.open(sceneAdvancedPage) },
                    MenuItem.Button({ if (renderer.guide) "水平參考線：開" else "水平參考線：關" }) {
                        renderer.guide = !renderer.guide
                    },
                ),
                listOf(
                    MenuItem.Button({ "取消" }) { discardCalibration(); backToMain() },
                    MenuItem.Button({ "全部歸零" }) { renderer.scene = SceneCalib() },
                    MenuItem.Button({ "儲存" }) {
                        saveScene(renderer.scene)
                        backToMain()
                    },
                ),
            ),
            onBack = { discardCalibration(); backToMain() },
        )
    }

    /** 對準模式：使用者看向想要的正前方，按 A 或點螢幕。 */
    private val aimPage: MenuPage by lazy {
        MenuPage(
            title = { "設定正前方" },
            hint = { "看向影片中想要放在正前方的位置（例如主角），按 A 或點一下螢幕。" },
            rows = listOf(listOf(MenuItem.Button({ "取消" }) { aiming = false; menu.open(scenePage) })),
            onBack = { aiming = false; menu.open(scenePage) },
        )
    }

    /**
     * 把目前視線看到的影片方向設成正前方（同時決定水平方向與仰俯，地平線不變）。
     * 內容方向 c = Rs⁻¹·g；要找新的 Rs' = rotY(yaw')·rotX(pitch')·rotZ(roll) 使 Rs'·c = (0,0,-1)。
     */
    private fun captureFront() {
        val s = renderer.scene
        val rs = M3.mul(M3.rotY(rad(s.yawDeg)), M3.rotX(rad(s.pitchDeg)), M3.rotZ(rad(s.rollDeg)))
        val g = M3.apply(lastHead, 0f, 0f, -1f)
        val c = M3.apply(M3.transpose(rs), g[0], g[1], g[2])
        val c1 = M3.apply(M3.rotZ(rad(s.rollDeg)), c[0], c[1], c[2])
        val pitch = atan2(-c1[1], -c1[2])
        val r = sqrt(c1[1] * c1[1] + c1[2] * c1[2])
        val yaw = atan2(c1[0], r)
        renderer.scene = s.copy(yawDeg = deg(yaw), pitchDeg = deg(pitch))
        aiming = false
        menu.open(scenePage)
        menu.placeInFront(lastHead, CALIB_PANEL_PITCH)
    }

    private val sceneAdvancedPage: MenuPage by lazy {
        MenuPage(
            title = { "這支影片的校正：進階" },
            hint = {
                "微調正前方；看久眼睛吃力或近物重影，再調雙眼垂直差與匯聚。"
            },
            rows = listOf(
                listOf(sceneStep("水平方向", 1f, { it.yawDeg }) { s, v -> s.copy(yawDeg = v) }),
                listOf(sceneStep("仰俯", 0.5f, { it.pitchDeg }) { s, v -> s.copy(pitchDeg = v) }),
                listOf(sceneStep("雙眼垂直差", 0.05f, { it.eyeVerticalDeg }) { s, v -> s.copy(eyeVerticalDeg = v) }),
                listOf(sceneStep("匯聚", 0.1f, { it.convergenceDeg }) { s, v -> s.copy(convergenceDeg = v) }),
                listOf(
                    MenuItem.Button({ if (renderer.scene.swapEyes) "左右眼：已對調" else "左右眼：正常" }) {
                        renderer.scene = renderer.scene.copy(swapEyes = !renderer.scene.swapEyes)
                    },
                    MenuItem.Button({ "返回" }) { menu.open(scenePage) },
                ),
            ),
            onBack = { menu.open(scenePage) },
        )
    }

    // ---- 鏡片校正 ----

    private fun openLens() {
        lensBeforeEdit = renderer.lens
        renderer.mode = VrRenderer.Mode.CENTER
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
            title = { "鏡片校正" },
            hint = {
                "設定檔：${renderer.lens.name}\n" + if (renderer.mode == VrRenderer.Mode.CENTER)
                    "對中心：一次閉一隻眼。同心圓的圓心要在鏡片正中央，黃色外框四邊要被鏡片邊緣切掉一樣多。" +
                        "圓心偏左就把『左右位置』往右調，偏下就把『上下位置』往上調；" +
                        "若一眼偏高、另一眼偏低（手機歪了），調『傾斜』。"
                else if (renderer.mode == VrRenderer.Mode.NONIUS)
                    "眼距：左眼看到十字的上半與左半，右眼看到下半與右半。調『鏡片間距』直到兩段直線接成一直線、" +
                        "橫線也對齊；黃圈是兩眼共同的參考。"
                else
                    "畸變：看正前方調 k1 讓中段線條變直，再看邊緣調 k2。最後左右轉頭，方格應像釘在牆上；" +
                        "若方格跟著頭跑或反向滑動，調『螢幕到鏡片』。"
            },
            rows = listOf(
                listOf(
                    MenuItem.Button({ mark(renderer.mode == VrRenderer.Mode.CENTER) + "對中心" }) {
                        renderer.mode = VrRenderer.Mode.CENTER
                    },
                    MenuItem.Button({ mark(renderer.mode == VrRenderer.Mode.NONIUS) + "眼距對準圖" }) {
                        renderer.mode = VrRenderer.Mode.NONIUS
                    },
                    MenuItem.Button({ mark(renderer.mode == VrRenderer.Mode.GRID) + "方格牆" }) {
                        renderer.mode = VrRenderer.Mode.GRID
                    },
                ),
                listOf(
                    lensStep("左右位置", 0.5f, "%+.1f mm", -30f, 30f, { it.offsetXMm }) { l, v -> l.copy(offsetXMm = v) },
                    lensStep("傾斜", 0.2f, "%+.1f°", -10f, 10f, { it.tiltDeg }) { l, v -> l.copy(tiltDeg = v) },
                ),
                listOf(
                    MenuItem.Stepper(
                        "上下位置",
                        value = {
                            if (renderer.lens.alignment == VerticalAlignment.CENTER) "%+.1f mm".format(renderer.lens.lensHeightMm)
                            else "%.1f mm".format(renderer.lens.lensHeightMm)
                        },
                        // 「上下位置」的 − / + 一律代表畫面往下 / 往上；以上緣為基準時數值方向相反
                        dec = { renderer.lens = moveLensVertical(renderer.lens, -0.5f) },
                        inc = { renderer.lens = moveLensVertical(renderer.lens, +0.5f) },
                    ),
                    MenuItem.Button({
                        "基準：" + when (renderer.lens.alignment) {
                            VerticalAlignment.BOTTOM -> "下緣"
                            VerticalAlignment.CENTER -> "置中"
                            VerticalAlignment.TOP -> "上緣"
                        }
                    }) {
                        // 只換表示方式：數值依螢幕高度換算，畫面不會跳
                        val all = VerticalAlignment.entries
                        val next = all[(renderer.lens.alignment.ordinal + 1) % all.size]
                        renderer.lens = changeLensAlignment(renderer.lens, next, renderer.screenHeightMm)
                    },
                ),
                listOf(lensStep("鏡片間距", 0.2f, "%.1f mm", 45f, 80f, { it.interLensMm }) { l, v -> l.copy(interLensMm = v) }),
                listOf(lensStep("螢幕到鏡片", 0.2f, "%.1f mm", 20f, 70f, { it.screenToLensMm }) { l, v -> l.copy(screenToLensMm = v) }),
                listOf(
                    lensStep("k1", 0.01f, "%.2f", -0.5f, 1.5f, { it.k1 }) { l, v -> l.copy(k1 = v) },
                    lensStep("k2", 0.01f, "%.2f", -0.5f, 1.5f, { it.k2 }) { l, v -> l.copy(k2 = v) },
                ),
                listOf(lensStep("可視半角", 1f, "%.0f°", 30f, 75f, { it.halfFovDeg }) { l, v -> l.copy(halfFovDeg = v) }),
                listOf(
                    MenuItem.Button({ "取消" }) { discardCalibration(); backToMain() },
                    MenuItem.Button({ "Cardboard 預設值" }) {
                        // 只換數值，保留目前設定檔的名稱；按「儲存」才會寫入
                        val cur = renderer.lens
                        renderer.lens = LensProfile.cardboardDefault().copy(id = cur.id, name = cur.name)
                    },
                    MenuItem.Button({ "儲存" }) {
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
            onBack = { discardCalibration(); backToMain() },
        )
    }

    // ------------------------------------------------------------------ 工具

    private fun mark(on: Boolean) = if (on) "● " else "○ "

    private fun rad(deg: Float) = Math.toRadians(deg.toDouble()).toFloat()
    private fun deg(rad: Float) = Math.toDegrees(rad.toDouble()).toFloat()

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
