/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.ui.home

import android.content.Intent
import kotlin.coroutines.resume
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.SeekBar
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.mediaplayer.app.Prefs
import com.mediaplayer.app.R
import com.mediaplayer.app.data.api.ApiClient
import com.mediaplayer.app.data.api.ClientAuthManager
import com.mediaplayer.app.data.api.ServerAuthFlowManager
import com.mediaplayer.app.data.model.Channel
import com.mediaplayer.app.data.model.ChannelGroup
import com.mediaplayer.app.data.model.ChannelLine
import com.mediaplayer.app.data.repository.ChannelRepository
import com.mediaplayer.app.data.ChannelMemoryManager
import com.mediaplayer.app.ui.player.PlayerActivity
import com.mediaplayer.app.ui.settings.SettingsActivity
import com.mediaplayer.app.util.DeviceUtils
import com.mediaplayer.app.util.FocusHelper
import com.mediaplayer.app.util.AudioTrackInfo
import com.mediaplayer.app.util.SubtitleTrackInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.mediaplayer.app.util.VlcPlayerHelper
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.math.max

class MainActivity : AppCompatActivity(), com.mediaplayer.app.util.PipActionCallback {

    override fun getResources(): android.content.res.Resources {
        val res = super.getResources()
        val dm = res.displayMetrics
        if (dm.widthPixels > 0 && dm.heightPixels > 0) {
            val shortSide = Math.min(dm.widthPixels, dm.heightPixels)
            val targetDensity = shortSide / 720f
            if (Math.abs(dm.density - targetDensity) > 0.01f) {
                val targetScaledDensity = targetDensity * (dm.scaledDensity / dm.density)
                val targetDensityDpi = (160 * targetDensity).toInt()
                dm.density = targetDensity
                dm.scaledDensity = targetScaledDensity
                dm.densityDpi = targetDensityDpi
            }
        }
        return res
    }

    private val repo = ChannelRepository()
    private lateinit var authManager: ClientAuthManager
    private lateinit var authFlowManager: ServerAuthFlowManager
    private var isTvMode = true

    // ── Views (TV mode - Zapping & Player) ──
    private var tvGroupsRv: RecyclerView? = null
    private var tvChannelsRv: RecyclerView? = null
    private var tvAuthWaiting: View? = null
    private var layoutZappingMenu: View? = null
    private var osdOverlayView: com.mediaplayer.app.ui.widget.OsdOverlayView? = null
    private var progressBuffering: ProgressBar? = null
    private var videoLayout: android.widget.FrameLayout? = null
    private var snapshotOverlay: android.widget.ImageView? = null
    
    // EPG Menu
    private var layoutEpgMenu: View? = null
    private var rvEpgList: androidx.recyclerview.widget.RecyclerView? = null
    private var progressEpgLoading: View? = null
    private var tvEpgEmptyText: TextView? = null
    private var tvEpgMenuTitle: TextView? = null
    private lateinit var epgAdapter: EpgAdapter
    
    // Line Selection Menu
    private var layoutLineMenu: View? = null
    private var tvLineMenuTitle: TextView? = null
    
    // PiP Controller
    private lateinit var pipController: com.mediaplayer.app.util.PipController
    private var containerLines: LinearLayout? = null

    // ── Settings Sidebar ──
    private var layoutSettingsMenu: View? = null
    private var layoutTrackMenu: View? = null
    private var tvTrackPanelTitle: TextView? = null
    private var containerTracks: LinearLayout? = null

    /** Track menu open flag, used by OSD auto-hide coordinator and key routing */
    private var isTrackPanelOpen: Boolean = false
    private var etSettingsUrl: EditText? = null
    private var sbSettingsCache: android.widget.SeekBar? = null
    private var tvSettingsCacheValue: TextView? = null

    private var tvSettingsInfo: TextView? = null

    // QR Code Config
    private var configWebServer: com.mediaplayer.app.server.ConfigWebServer? = null
    private var layoutQrConfig: View? = null
    private var ivQrCode: android.widget.ImageView? = null
    private var tvQrConfigHint: TextView? = null
    
    private var layoutAuthQrConfig: View? = null
    private var ivAuthQrCode: android.widget.ImageView? = null
    private var tvAuthQrConfigHint: TextView? = null


    // ── Shared loading/empty views ──
    private var progressLoading: ProgressBar? = null

    // ── Catchup State ──
    private var currentCatchupStartTime: String? = null
    private var currentCatchupChannelIndex: Int = -1

    // ── Data ──
    private var groups = listOf<ChannelGroup>()
    private var allChannels: MutableList<Channel> = mutableListOf()
    private var channelIndexById = HashMap<Long, Channel>() // ID 索引，加速查找
    private var channelsByGroup: Map<Long, List<Channel>> = emptyMap()
    private var filteredChannels = listOf<Channel>()
    private var currentGroupId = 0L
    private var currentChannelIndex = 0
    private var currentLineIndex = 0

    private lateinit var groupAdapter: GroupAdapter
    private lateinit var channelAdapter: ChannelAdapter

    private val authPollHandler = Handler(Looper.getMainLooper())
    private var authPollRunnable: Runnable? = null
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private var heartbeatRunnable: Runnable? = null
    
    private val epgTickerHandler = Handler(Looper.getMainLooper())
    private var epgTickerRunnable: Runnable? = null

    private val focusDebounceHandler = Handler(Looper.getMainLooper())
    private var focusDebounceRunnable: Runnable? = null

    private var globalProgressBar: ProgressBar? = null
    private val globalProgressHandler = Handler(Looper.getMainLooper())
    private var globalProgressRunnable: Runnable? = null

    private var playerHelper: com.mediaplayer.app.util.IPlayerHelper? = null
    private var continuousSkipCount = 0
    private val maxAutoSkips = 5
    private val uiHandler = Handler(Looper.getMainLooper())
    private var coreRetryLevel = 0
    private var isWatchdogEnabledForCurrentStream = false

    // VOD 专业快进/快退状态
    private var vodSeekActive = false          // 快进或快退进行中
    private var vodSeekDirection = 0           // -1=快退, 1=快进
    private var vodSeekTarget = 0L             // 当前 seek 目标位置
    private var vodSeekLastTick = 0L
    private var vodSeekStartTime = 0L          // 开始时间（用于速度递增）
    private val vodSeekStepMs = 60_000L        // 短按步进：60秒（1分钟）
    private val vodSeekBaseSpeed = 60_000L     // 起始速度：60秒/秒（1分钟/秒）
    private val vodSeekMaxSpeed = 300_000L     // 最大速度：300秒/秒（5分钟/秒）
    private val vodSeekTickMs = 50L            // 50ms 刷新（20fps，够平滑且不卡）
    private val vodSeekHandler = Handler(Looper.getMainLooper())
    private val vodSeekRunnable = object : Runnable {
        override fun run() {
            if (!vodSeekActive) return
            if (osdOverlayView?.isOsdVisible() != true) {
                stopVodSeek()
                return
            }
            val now = SystemClock.uptimeMillis()
            val elapsed = (now - vodSeekLastTick).coerceAtLeast(1)
            vodSeekLastTick = now
            // 线性加速：每秒增加 60秒/秒 的速度
            val seekElapsed = (now - vodSeekStartTime) / 1000.0
            val currentSpeed = (vodSeekBaseSpeed + (seekElapsed * 60_000).toLong())
                .coerceAtMost(vodSeekMaxSpeed)
            val step = currentSpeed * elapsed / 1000
            val duration = playerHelper?.getDuration() ?: 0
            vodSeekTarget = (vodSeekTarget + vodSeekDirection * step)
                .coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
            playerHelper?.setTime(vodSeekTarget)
            // 更新 OSD 显示
            if (vodSeekDirection > 0) {
                osdOverlayView?.updateVodProgress(vodSeekTarget, if (duration > 0) duration else vodSeekTarget)
            } else {
                osdOverlayView?.setVodSeekBackward(vodSeekTarget, if (duration > 0) duration else vodSeekTarget)
            }
            vodSeekHandler.postDelayed(this, vodSeekTickMs)
        }
    }

    // ── 音轨/字幕缓存状态 ──
    private var cachedAudioTracks: List<AudioTrackInfo>? = null
    private var cachedSubtitleTracks: List<SubtitleTrackInfo>? = null

    // 数据加载并发锁
    private var isLoadingData = false
    // 首次 onResume 标记（防止与 onCreate 的认证链路冲突）
    private var isFirstResume = true
    private var isPipClosedBySystem = false
    private var hasShownSplash = false
    private var lastEpgBgRefreshTime = 0L
    
    // Watchdog State
    enum class PlaybackState { IDLE, BUFFERING, PLAYING }
    private var currentPlaybackState = PlaybackState.IDLE
    private var stateStartTime = 0L
    private var lastPlaybackTime = 0L
    private var frozenTimeCounter = 0
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (isTvMode && playerHelper != null) {
                val now = System.currentTimeMillis()
                when (currentPlaybackState) {
                    PlaybackState.BUFFERING -> {
                        // 场景 A：连接或缓冲超时（10秒未进入 PLAYING）
                        // 注释掉该看门狗策略，将超时判断权完全交还给底层播放器（ExoPlayer/VLC）。
                        // 避免在弱网或加载较慢的有效源上出现频繁的“误杀”和自动换台跳跃。
                        /*
                        if (stateStartTime > 0 && now - stateStartTime > 10000L) {
                            Toast.makeText(this@MainActivity, "网络连接超时，正在尝试切换线路...", Toast.LENGTH_SHORT).show()
                            currentPlaybackState = PlaybackState.IDLE
                            handlePlaybackError(isNetworkTimeout = true)
                            return
                        }
                        */
                    }
                    PlaybackState.PLAYING -> {
                        // 场景 B：画面冻结（假死）逻辑
                        // 由于多数直播流底层时间戳不会随播放推进而正常改变（getTime() 返回固定值），
                        // 这里通过比对 currentTime == lastPlaybackTime 极易造成正常播放时的误判卡死，
                        // 因此将此逻辑注释掉。如有真实卡顿，应依靠播放器的 onBuffering 或 onError 来处理。
                        /*
                        if (!isWatchdogEnabledForCurrentStream) {
                            lastPlaybackTime = playerHelper?.getTime() ?: 0L
                            frozenTimeCounter = 0
                        } else {
                            val currentTime = playerHelper?.getTime() ?: 0L
                            if (currentTime > 0 && currentTime == lastPlaybackTime) {
                                frozenTimeCounter++
                                if (frozenTimeCounter >= 4) { // 4 * 2s = 8s
                                    Toast.makeText(this@MainActivity, "检测到画面卡死，正在尝试恢复...", Toast.LENGTH_SHORT).show()
                                    currentPlaybackState = PlaybackState.IDLE
                                    handlePlaybackError()
                                    frozenTimeCounter = 0
                                    return
                                }
                            } else {
                                lastPlaybackTime = currentTime
                                frozenTimeCounter = 0
                            }
                        }
                        */
                    }
                    PlaybackState.IDLE -> {
                        frozenTimeCounter = 0
                    }
                }
            }
            uiHandler.postDelayed(this, 2000)
        }
    }


    private val hideZappingRunnable = Runnable { 
        layoutZappingMenu?.visibility = View.GONE
        activeListArea = "channels"
        com.mediaplayer.app.util.RemoteLogger.i("PanelTrace", "ZappingMenu GONE")
    }

    // System Announcement state
    private var sysAnnouncement: String? = null
    private var sysAnnouncementInterval: Int = 0
    private var marqueeIsVisible = false
    private val marqueeRunnable = Runnable { triggerMarquee() }
    private val hideMarqueeRunnable = Runnable { 
        findViewById<android.view.View>(R.id.layoutAnnouncement)?.visibility = View.GONE
        findViewById<android.widget.TextView>(R.id.tvAnnouncement)?.isSelected = false
        marqueeIsVisible = false
        if (sysAnnouncementInterval > 0) {
            uiHandler.postDelayed(marqueeRunnable, sysAnnouncementInterval * 60 * 1000L)
        }
    }

    private var activeListArea = "channels" // "groups", "channels", "epg", "track"

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        
        // 终极全局焦点防溢出守护神：监听由系统(如RecyclerView回收)引发的非自愿焦点跳转
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { oldFocus, newFocus ->
            if (newFocus != null) {
                val isNewGroups = isViewDescendantOf(newFocus, tvGroupsRv)
                val isNewChannels = isViewDescendantOf(newFocus, tvChannelsRv)
                val isNewEpg = isViewDescendantOf(newFocus, rvEpgList)

                if (isNewGroups && activeListArea != "groups") {
                    com.mediaplayer.app.util.RemoteLogger.i("FocusTrace", "Involuntary jump to Groups rejected! Forcing back to $activeListArea.")
                    bounceFocusBack()
                } else if (isNewChannels && activeListArea != "channels") {
                    com.mediaplayer.app.util.RemoteLogger.i("FocusTrace", "Involuntary jump to Channels rejected! Forcing back to $activeListArea.")
                    bounceFocusBack()
                } else if (isNewEpg && activeListArea != "epg") {
                    com.mediaplayer.app.util.RemoteLogger.i("FocusTrace", "Involuntary jump to EPG rejected! Forcing back to $activeListArea.")
                    bounceFocusBack()
                }
            }
        }

        // 强制所有设备使用 TV 的沉浸式界面大一统！
        isTvMode = true
        
        pipController = com.mediaplayer.app.util.PipController(this, this)
        
        // 安全地请求横屏方向，规避 Android 8.0 透明主题请求固定方向时的崩溃Bug
        try {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } catch (e: Exception) {
            // 忽略 Android 8.0 上的 IllegalStateException
        }

        // 保持屏幕常亮，防止手机/Pad自动锁屏
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 兼容刘海屏/挖孔屏/灵动岛：允许画面延伸到全部屏幕边缘
        // Android 15+ (API 35): ALWAYS 模式确保长边（灵动岛）区域也被覆盖
        // Android 9-14 (API 28-34): SHORT_EDGES 已足够覆盖所有刘海/挖孔场景
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = if (android.os.Build.VERSION.SDK_INT >= 35) {
                // LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS = 3 (Android 15+)
                3
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            window.attributes = lp
        }

        // 强行关闭系统布局自适应，允许布局内容延伸到状态栏和导航栏区域下
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemUI()

        setContentView(R.layout.activity_main)
        setupTvViews()
        // Player will be initialized when playing a channel
        setupTouchGestures()

        authManager = ClientAuthManager(this)
        authFlowManager = ServerAuthFlowManager(this, lifecycleScope, authManager)

        val prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        val serverUrl = prefs.getString(Prefs.KEY_SERVER_URL, Prefs.DEFAULT_SERVER_URL) ?: Prefs.DEFAULT_SERVER_URL
        ApiClient.init(serverUrl)
        
        ChannelMemoryManager.init(this)

        setupAdapters()

        // 初始化认证流程回调
        authFlowManager.setCallback(object : ServerAuthFlowManager.Callback {
            override fun onStatusUpdate(message: String, showQr: Boolean) {
                showAuthWaiting(message, showQr)
            }

            override fun onSuccess(resp: com.mediaplayer.app.data.model.VerifyResponse) {
                handleAuthSuccess(
                    resp.announcement, resp.announcementInterval,
                    resp.startupMediaEnabled, resp.startupMedia,
                    resp.startupMediaType, resp.startupDuration,
                    resp.startupSkipAfter, resp.globalMaintenance,
                    resp.backupServers, resp.isTester
                )
            }

            override fun onPending(deviceId: String) {
                showAuthWaiting("设备已注册，等待管理员审批...\n\n设备ID: $deviceId", showQr = true)
                startAuthPolling()
            }

            override fun onRejected(message: String) {
                showAuthWaiting(message, showQr = true)
            }

            override fun onBanned(message: String) {
                showAuthWaiting(message, showQr = true)
            }

            override fun onAllFailed() {
                showAuthWaiting("所有服务器均无法连接，15秒后自动重试...\n请检查配置信息", showQr = true)
            }

            override fun onRetryScheduled(delayMs: Long) {
                // 可以在这里显示倒计时
            }
        })

        // 启动认证流程
        authFlowManager.startAuthFlow()

        // 检查版本更新
        com.mediaplayer.app.util.UpdateManager.checkUpdate(this, lifecycleScope, false)
    }



    private fun hideSystemUI() {
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private var initialBrightness = -1f
    private var initialVolume = -1
    private var isAdjusting = false
    private var adjustMode = 0 // 0: none, 1: brightness, 2: volume

    // ── Touch Gestures for Mobile/Tablet ──
    private fun setupTouchGestures() {
        val gestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent): Boolean {
                isAdjusting = false
                adjustMode = 0
                val screenWidth = videoLayout?.width ?: 0
                if (screenWidth > 0) {
                    val prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
                    val enableBrightness = prefs.getBoolean(Prefs.KEY_GESTURE_BRIGHTNESS, true)
                    val enableVolume = prefs.getBoolean(Prefs.KEY_GESTURE_VOLUME, true)
                    
                    if (enableBrightness && e.x > screenWidth * 0.05f && e.x < screenWidth * 0.15f) {
                        adjustMode = 1
                        val lp = window.attributes
                        initialBrightness = lp.screenBrightness
                        if (initialBrightness < 0) {
                            try {
                                val sysBrightness = android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
                                initialBrightness = sysBrightness / 255f
                            } catch (e: Exception) {
                                initialBrightness = 0.5f
                            }
                        }
                    } else if (enableVolume && e.x < screenWidth * 0.95f && e.x > screenWidth * 0.85f) {
                        adjustMode = 2
                        val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                        initialVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                    }
                }
                return true
            }

            override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                var menuHidden = false
                if (layoutZappingMenu?.visibility == View.VISIBLE) {
                    uiHandler.removeCallbacks(hideZappingRunnable)
                    hideZappingRunnable.run()
                    menuHidden = true
                }
                if (layoutEpgMenu?.visibility == View.VISIBLE) {
                    hideEpgMenu()
                    menuHidden = true
                }
                if (layoutSettingsMenu?.visibility == View.VISIBLE) {
                    hideSettingsMenu()
                    menuHidden = true
                }
                if (layoutLineMenu?.visibility == View.VISIBLE) {
                    hideLineSelectionMenu()
                    menuHidden = true
                }
                
                if (menuHidden) {
                    return true
                }

                // 单点：显示 OSD（5s 自动隐藏）
                showOsd()
                return true
            }

            override fun onLongPress(e: android.view.MotionEvent) {
                // 手机端：长按屏幕呼出“手动切换线路”
                showLineSelectionMenu()
            }

            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                // 手机端：双击屏幕呼出“设置菜单”
                val isSettingsVisible = layoutSettingsMenu?.visibility == View.VISIBLE
                if (isSettingsVisible) hideSettingsMenu() else showSettingsMenu()
                return true
            }

            override fun onScroll(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (e1 == null || e2 == null) return false
                val deltaY = e1.y - e2.y // 向上滑动为正
                val screenHeight = videoLayout?.height ?: 1080
                
                if (adjustMode != 0) {
                    if (kotlin.math.abs(deltaY) > 20) {
                        isAdjusting = true
                    }
                    if (isAdjusting) {
                        if (adjustMode == 1) {
                            // 调节亮度
                            val change = deltaY / screenHeight
                            val lp = window.attributes
                            lp.screenBrightness = (initialBrightness + change).coerceIn(0.01f, 1f)
                            window.attributes = lp
                        } else if (adjustMode == 2) {
                            // 调节音量
                            val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                            val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                            val change = (deltaY / screenHeight) * maxVolume
                            val newVol = (initialVolume + change).toInt().coerceIn(0, maxVolume)
                            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, newVol, android.media.AudioManager.FLAG_SHOW_UI)
                        }
                        return true
                    }
                }
                return false
            }

            override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                if (isCurrentChannelVod()) {
                    // VOD模式：OSD已显示时才触发暂停/恢复，避免误触
                    if (osdOverlayView?.isOsdVisible() == true) {
                        toggleVodPauseResume()
                    }
                }
                showOsd()
                return true
            }

            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                if (isAdjusting) return false // 如果正在调亮度/音量，则不触发切台
                
                val deltaY = e2.y - e1.y
                val deltaX = e2.x - e1.x
                
                // 上下滑动：切台
                if (kotlin.math.abs(deltaY) > kotlin.math.abs(deltaX) && kotlin.math.abs(deltaY) > 100) {
                    if (layoutZappingMenu?.visibility == View.VISIBLE || layoutEpgMenu?.visibility == View.VISIBLE) return false
                    if (deltaY > 0) {
                        // 向下滑动：上一个频道
                        val prev = if (currentChannelIndex > 0) currentChannelIndex - 1 else allChannels.size - 1
                        playTvChannel(prev)
                    } else {
                        // 向上滑动：下一个频道
                        val next = if (currentChannelIndex < allChannels.size - 1) currentChannelIndex + 1 else 0
                        playTvChannel(next)
                    }
                    return true
                } 
                // 左右滑动：上下文感知的抽屉式交互
                else if (kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) && kotlin.math.abs(deltaX) > 100) {
                    if (deltaX < 0) {
                        // 向左滑动：侧边栏已显示则关闭，否则显示 EPG
                        if (layoutZappingMenu?.visibility == View.VISIBLE) {
                            uiHandler.removeCallbacks(hideZappingRunnable)
                            hideZappingRunnable.run()
                            return true
                        } else if (layoutEpgMenu?.visibility != View.VISIBLE && layoutSettingsMenu?.visibility != View.VISIBLE) {
                            showEpgMenu()
                            return true
                        }
                    } else {
                        // 向右滑动：EPG 已显示则关闭，否则显示侧边栏
                        if (layoutEpgMenu?.visibility == View.VISIBLE) {
                            hideEpgMenu()
                            return true
                        } else if (layoutZappingMenu?.visibility != View.VISIBLE && layoutSettingsMenu?.visibility != View.VISIBLE) {
                            showZappingMenu(focusOnGroups = false, resetToPlaying = true)
                            return true
                        }
                    }
                }
                return false
            }
        })

        // 在最顶层的 AuthWaiting 后加一个透明遮罩层或者直接接管 Activity 的 onTouchEvent 比较困难
        // 最优雅的方法是给 videoLayout 所在的父布局加拦截，但由于 VLCVideoLayout 结构特殊，
        // 我们可以在 rootView 上监听，或者给 videoLayout 设置
        videoLayout?.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true // 消费掉触摸事件
        }
    }

    // ═══════════════════════════════════════════════════
    // TV MODE SETUP & PLAYER
    // ═══════════════════════════════════════════════════

    private fun setupTvViews() {
        globalProgressBar = findViewById(R.id.globalProgressBar)
        tvGroupsRv = findViewById(R.id.rvGroups)
        tvChannelsRv = findViewById(R.id.rvChannels)
        tvAuthWaiting = findViewById(R.id.layoutAuthWaiting)
        layoutZappingMenu = findViewById(R.id.layoutZappingMenu)
        osdOverlayView = findViewById(R.id.osdOverlayView)
        osdOverlayView?.onOsdVisibilityChanged = { isVisible ->
            findViewById<com.mediaplayer.app.ui.widget.TimeOverlayView>(R.id.timeOverlayView)?.forceShowByOsd = isVisible
        }
        osdOverlayView?.setTrackButtonListener { type ->
            showTrackPanel(type)
        }
        progressBuffering = findViewById(R.id.progressBuffering)
        videoLayout = findViewById(R.id.videoLayout)
        progressLoading = findViewById(R.id.progressLoading)

        // 切台截帧占位 ImageView：覆盖在 videoLayout 最上层，消除切台黑屏
        snapshotOverlay = android.widget.ImageView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        videoLayout?.addView(snapshotOverlay)

        // Settings sidebar
        layoutSettingsMenu = findViewById(R.id.layoutSettingsMenu)
        etSettingsUrl = findViewById(R.id.etSettingsUrl)
        sbSettingsCache = findViewById(R.id.sbSettingsCache)
        tvSettingsCacheValue = findViewById(R.id.tvSettingsCacheValue)

        tvSettingsInfo = findViewById(R.id.tvSettingsInfo)

        layoutQrConfig = findViewById(R.id.layoutQrConfig)
        ivQrCode = findViewById(R.id.ivQrCode)
        tvQrConfigHint = findViewById(R.id.tvQrConfigHint)
        
        layoutAuthQrConfig = findViewById(R.id.layoutAuthQrConfig)
        ivAuthQrCode = findViewById(R.id.ivAuthQrCode)
        tvAuthQrConfigHint = findViewById(R.id.tvAuthQrConfigHint)

        // EPG Menu
        layoutEpgMenu = findViewById(R.id.layoutEpgMenu)
        rvEpgList = findViewById(R.id.rvEpgList)
        progressEpgLoading = findViewById(R.id.progressEpgLoading)
        tvEpgEmptyText = findViewById(R.id.tvEpgEmptyText)
        tvEpgMenuTitle = findViewById(R.id.tvEpgMenuTitle)
        
        epgAdapter = EpgAdapter()
        epgAdapter.setOnItemClickListener { prog ->
            val channel = allChannels.getOrNull(currentChannelIndex) ?: return@setOnItemClickListener
            try {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
                val startUnix = sdf.parse(prog.startTime)?.time?.div(1000) ?: 0L
                val endUnix = sdf.parse(prog.endTime)?.time?.div(1000) ?: 0L
                if (startUnix > 0 && endUnix > 0) {
                    val url = ApiClient.getCatchupUrl(channel.id, startUnix, endUnix)
                    val lines = channel.getLinesSafely()
                    val ua = if (lines.isNotEmpty()) lines[0].userAgent else ""
                    val headers = if (lines.isNotEmpty()) lines[0].customHeaders else ""
                    
                    if (isTvMode) {
                        currentCatchupStartTime = prog.startTime
                        currentCatchupChannelIndex = currentChannelIndex
                        osdOverlayView?.setInfoText("回看: ${prog.title}".toString())
                        playerHelper?.play(url, ua, headers)
                        hideEpgMenu()
                    } else {
                        val intent = android.content.Intent(this, com.mediaplayer.app.ui.player.PlayerActivity::class.java).apply {
                            putExtra("channel_id", channel.id)
                            putExtra("channel_name", channel.name)
                            putExtra("stream_url", url)
                            putExtra("stream_type", "hls") // Catchup is usually HLS
                            putExtra("user_agent", ua)
                            putExtra("custom_headers", headers)
                        }
                        startActivity(intent)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        rvEpgList?.layoutManager = object : androidx.recyclerview.widget.LinearLayoutManager(this) {
            override fun onFocusSearchFailed(focused: View, focusDirection: Int, recycler: androidx.recyclerview.widget.RecyclerView.Recycler, state: androidx.recyclerview.widget.RecyclerView.State): View? {
                val next = super.onFocusSearchFailed(focused, focusDirection, recycler, state)
                if (next == null && (focusDirection == View.FOCUS_DOWN || focusDirection == View.FOCUS_UP)) {
                    return focused // 捕获焦点
                }
                return next
            }
            override fun requestChildRectangleOnScreen(parent: androidx.recyclerview.widget.RecyclerView, child: View, rect: android.graphics.Rect, immediate: Boolean, focusedChildVisible: Boolean): Boolean {
                // 核心UX优化：预留上下各 2 个 Item 的高度作为焦点边距
                // 这样在焦点达到倒数第 3 个时，列表就会提前向上滚动
                rect.top -= child.height * 2
                rect.bottom += child.height * 2
                return super.requestChildRectangleOnScreen(parent, child, rect, immediate, focusedChildVisible)
            }
        }
        rvEpgList?.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                if (FocusHelper.trapVerticalScroll(rvEpgList!!, keyCode)) return@setOnKeyListener true
            }
            false
        }
        rvEpgList?.adapter = epgAdapter
        
        // Line Menu
        layoutLineMenu = findViewById(R.id.layoutLineMenu)
        tvLineMenuTitle = findViewById(R.id.tvLineMenuTitle)
        containerLines = findViewById(R.id.containerLines)

        // Track Selection Menu
        layoutTrackMenu = findViewById(R.id.layoutTrackMenu)
        tvTrackPanelTitle = findViewById(R.id.tvTrackPanelTitle)
        containerTracks = findViewById(R.id.containerTracks)

        setupSettingsViews()

        tvGroupsRv?.let { FocusHelper.setupTvRecyclerView(it) }
        tvChannelsRv?.let { FocusHelper.setupTvRecyclerView(it) }

        val groupsRv = tvGroupsRv
        val channelsRv = tvChannelsRv
        if (groupsRv != null && channelsRv != null) {
            FocusHelper.linkHorizontalFocus(groupsRv, channelsRv,
                onLeftNav = {
                    val groupIndex = groupAdapter.currentList.indexOfFirst { it.id == currentGroupId }
                    if (groupIndex >= 0) {
                        val lm = groupsRv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                        lm?.findViewByPosition(groupIndex)?.requestFocus() ?: groupsRv.requestFocus()
                        true
                    } else {
                        false
                    }
                },
                onRightNav = {
                    // 获取当前焦点所在的分组
                    val focusedView = groupsRv.findFocus() ?: return@linkHorizontalFocus false
                    val focusedPos = groupsRv.getChildAdapterPosition(focusedView)
                    if (focusedPos < 0) return@linkHorizontalFocus false
                    val focusedGroup = groupAdapter.currentList.getOrNull(focusedPos) ?: return@linkHorizontalFocus false

                    // 判断是否需要刷新右侧列表
                    val needsFilter = focusDebounceRunnable != null || currentGroupId != focusedGroup.id
                    // 立即取消挂起的分组切台防抖任务，防止异步刷新导致焦点错乱
                    focusDebounceRunnable?.let {
                        focusDebounceHandler.removeCallbacks(it)
                        focusDebounceRunnable = null
                    }
                    if (needsFilter) {
                        currentGroupId = focusedGroup.id
                        groupAdapter.setSelected(focusedGroup.id)
                        filterChannels(scrollToTop = false)
                    }

                    val playingChannel = allChannels.getOrNull(currentChannelIndex)
                    val playingGroupId = playingChannel?.groupId ?: 0L

                    // 判断当前焦点分组是否是正在播放的频道所在分组（若为全部频道 0L 也视为匹配）
                    val isPlayingGroup = (focusedGroup.id == playingGroupId || focusedGroup.id == 0L)

                    val targetPos = if (isPlayingGroup && playingChannel != null) {
                        // 回到当前分组选中的频道上
                        val pos = filteredChannels.indexOfFirst { it.id == playingChannel.id }
                        if (pos < 0) 0 else pos
                    } else {
                        // 焦点转移到当前分组频道的第一个频道列表项
                        0
                    }

                    channelsRv.scrollToPosition(targetPos)
                    
                    val focusAction = Runnable {
                        val lm = channelsRv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                        val view = lm?.findViewByPosition(targetPos)
                        if (view != null) {
                            view.requestFocus()
                        } else {
                            channelsRv.requestFocus()
                        }
                    }
                    
                    if (needsFilter) {
                        // 如果列表刚刷新，等待 RecyclerView 布局完成
                        channelsRv.postDelayed(focusAction, 50)
                    } else {
                        val lm = channelsRv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                        val view = lm?.findViewByPosition(targetPos)
                        if (view != null) {
                            view.requestFocus()
                        } else {
                            // 视图未就绪(例如刚被滚动入屏)，延时重试
                            channelsRv.postDelayed(focusAction, 50)
                        }
                    }
                    true
                }
            )
        }
    }

    private var btnSettingsScale: View? = null
    private var btnSettingsDecoder: View? = null
    private var btnSettingsCore: View? = null
    private var btnSettingsMemory: View? = null
    private var tvSettingsMemoryValue: TextView? = null
    private var isIndependentMemoryOn = false
    private var currentCore = Prefs.PLAYER_CORE_AUTO
    var currentDecoderMode = Prefs.DECODER_MODE_AUTO
    
    private fun syncSettingsMemoryState() {
        val prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        val channel = allChannels.getOrNull(currentChannelIndex)
        val mem = channel?.let { com.mediaplayer.app.data.ChannelMemoryManager.getMemory(it.id) }
        
        if (mem != null && (mem.decoderMode != null || mem.playerCore != null)) {
            isIndependentMemoryOn = true
            currentDecoderMode = mem.decoderMode ?: prefs.getInt(Prefs.KEY_DECODER_MODE, Prefs.DECODER_MODE_AUTO)
            currentCore = mem.playerCore ?: prefs.getInt(Prefs.KEY_PLAYER_CORE, Prefs.PLAYER_CORE_AUTO)
        } else {
            isIndependentMemoryOn = false
            currentDecoderMode = prefs.getInt(Prefs.KEY_DECODER_MODE, Prefs.DECODER_MODE_AUTO)
            currentCore = prefs.getInt(Prefs.KEY_PLAYER_CORE, Prefs.PLAYER_CORE_AUTO)
        }
        
        // Update UI
        tvSettingsMemoryValue?.text = if (isIndependentMemoryOn) "专属配置" else "跟随系统"
        tvSettingsMemoryValue?.setTextColor(android.graphics.Color.parseColor(if (isIndependentMemoryOn) "#00E5FF" else "#AAAAAA"))
        
        findViewById<TextView>(R.id.tvSettingsDecoderValue)?.text = when (currentDecoderMode) {
            Prefs.DECODER_MODE_HARDWARE -> "强制硬解"
            Prefs.DECODER_MODE_SOFTWARE -> "强制软解"
            else -> "自动识别"
        }
        
        findViewById<TextView>(R.id.tvSettingsCoreValue)?.text = when (currentCore) {
            Prefs.PLAYER_CORE_EXO -> "ExoPlayer"
            Prefs.PLAYER_CORE_VLC -> "VLC"
            Prefs.PLAYER_CORE_IJK -> "IJKPlayer"
            else -> "智能切换"
        }
    }

    private fun setupSettingsViews() {
        val prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        val url = prefs.getString(Prefs.KEY_SERVER_URL, Prefs.DEFAULT_SERVER_URL)
        
        btnSettingsDecoder = findViewById(R.id.btnSettingsDecoder)
        btnSettingsCore = findViewById(R.id.btnSettingsCore)
        btnSettingsScale = findViewById(R.id.btnSettingsScale)
        btnSettingsMemory = findViewById(R.id.btnSettingsMemory)
        tvSettingsMemoryValue = findViewById(R.id.tvSettingsMemoryValue)
        
        val btnSettingsAutoStart = findViewById<View>(R.id.btnSettingsAutoStart)
        val btnSettingsAudioPassthrough = findViewById<View>(R.id.btnSettingsAudioPassthrough)
        val tvSettingsAudioPassthroughValue = findViewById<TextView>(R.id.tvSettingsAudioPassthroughValue)
        val btnSettingsReverseChannels = findViewById<View>(R.id.btnSettingsReverseChannels)
        val btnSettingsCheckUpdate = findViewById<View>(R.id.btnSettingsCheckUpdate)
        val btnSettingsAbout = findViewById<View>(R.id.btnSettingsAbout)
        
        val btnSettingsGestureBrightness = findViewById<View>(R.id.btnSettingsGestureBrightness)
        val tvSettingsGestureBrightnessValue = findViewById<TextView>(R.id.tvSettingsGestureBrightnessValue)
        val btnSettingsGestureVolume = findViewById<View>(R.id.btnSettingsGestureVolume)
        val tvSettingsGestureVolumeValue = findViewById<TextView>(R.id.tvSettingsGestureVolumeValue)
        
        val btnSettingsGlobalProgress = findViewById<View>(R.id.btnSettingsGlobalProgress)
        val tvSettingsGlobalProgressValue = findViewById<TextView>(R.id.tvSettingsGlobalProgressValue)
        
        fun updateGlobalProgressText(mode: Int) {
            tvSettingsGlobalProgressValue?.text = when (mode) {
                Prefs.GLOBAL_PROGRESS_TOP -> "顶部"
                Prefs.GLOBAL_PROGRESS_BOTTOM -> "底部"
                else -> "关闭"
            }
        }
        
        var currentGlobalProgress = prefs.getInt(Prefs.KEY_GLOBAL_PROGRESS_BAR, Prefs.GLOBAL_PROGRESS_OFF)
        updateGlobalProgressText(currentGlobalProgress)
        
        btnSettingsGlobalProgress?.setOnClickListener {
            currentGlobalProgress = (currentGlobalProgress + 1) % 3
            updateGlobalProgressText(currentGlobalProgress)
            prefs.edit().putInt(Prefs.KEY_GLOBAL_PROGRESS_BAR, currentGlobalProgress).apply()
            startGlobalProgressTicker()
        }
        
        fun updateDecoderText(mode: Int) {
            findViewById<TextView>(R.id.tvSettingsDecoderValue)?.text = when (mode) {
                Prefs.DECODER_MODE_HARDWARE -> "强制硬解"
                Prefs.DECODER_MODE_SOFTWARE -> "强制软解"
                else -> "自动识别"
            }
        }


        fun updateScaleText(mode: Int) {
            findViewById<TextView>(R.id.tvSettingsScaleValue)?.text = when (mode) {
                Prefs.SCALE_MODE_STRETCH -> "强制 16:9"
                Prefs.SCALE_MODE_4_3 -> "强制 4:3"
                Prefs.SCALE_MODE_16_10 -> "强制 16:10"
                Prefs.SCALE_MODE_CROP -> "放大裁剪"
                Prefs.SCALE_MODE_FILL -> "铺满全屏"
                else -> "原始比例"
            }
        }

        fun updateAutoStartText(enabled: Boolean) {
            findViewById<TextView>(R.id.tvSettingsAutoStartValue)?.text = if (enabled) "开" else "关"
        }
        
        fun updateReverseChannelsText(enabled: Boolean) {
            findViewById<TextView>(R.id.tvSettingsReverseChannelsValue)?.text = if (enabled) "开" else "关"
        }
        
        fun updateGestureBrightnessText(enabled: Boolean) {
            tvSettingsGestureBrightnessValue?.text = if (enabled) "开" else "关"
        }
        
        fun updateGestureVolumeText(enabled: Boolean) {
            tvSettingsGestureVolumeValue?.text = if (enabled) "开" else "关"
        }
        
        
        currentDecoderMode = prefs.getInt(Prefs.KEY_DECODER_MODE, Prefs.DECODER_MODE_AUTO)
        currentCore = prefs.getInt(Prefs.KEY_PLAYER_CORE, Prefs.PLAYER_CORE_AUTO)
        // 迁移旧版本中 X5 内核的选择（X5 已移除，自动回退到智能切换）
        if (currentCore == 4) {
            currentCore = Prefs.PLAYER_CORE_AUTO
            prefs.edit().putInt(Prefs.KEY_PLAYER_CORE, currentCore).apply()
        }
        var currentScaleMode = prefs.getInt(Prefs.KEY_SCALE_MODE, Prefs.SCALE_MODE_DEFAULT)
        var currentAutoStart = prefs.getBoolean(Prefs.KEY_AUTO_START, false)
        var currentShowLogo = prefs.getBoolean(Prefs.KEY_SHOW_CHANNEL_LOGO, true)
        var currentReverseChannels = prefs.getBoolean(Prefs.KEY_REVERSE_CHANNEL_KEYS, false)
        var currentAudioPassthrough = prefs.getBoolean(Prefs.KEY_AUDIO_PASSTHROUGH, false)
        var currentEnablePip = prefs.getBoolean(Prefs.KEY_ENABLE_PIP, false)
        var currentGestureBrightness = prefs.getBoolean(Prefs.KEY_GESTURE_BRIGHTNESS, true)
        var currentGestureVolume = prefs.getBoolean(Prefs.KEY_GESTURE_VOLUME, true)

        fun updateAudioPassthroughText(enabled: Boolean) {
            tvSettingsAudioPassthroughValue?.text = if (enabled) "开" else "关"
        }

        fun updatePipText(enabled: Boolean) {
            findViewById<TextView>(R.id.tvSettingsPipValue)?.text = if (enabled) "开" else "关"
        }

        updateDecoderText(currentDecoderMode)
        updateCoreText(currentCore)
        updateScaleText(currentScaleMode)
        updateAutoStartText(currentAutoStart)
        updateShowLogoText(currentShowLogo)
        updateReverseChannelsText(currentReverseChannels)
        updateAudioPassthroughText(currentAudioPassthrough)
        updatePipText(currentEnablePip)
        updateGestureBrightnessText(currentGestureBrightness)
        updateGestureVolumeText(currentGestureVolume)
        
        btnSettingsGestureBrightness?.setOnClickListener {
            currentGestureBrightness = !currentGestureBrightness
            updateGestureBrightnessText(currentGestureBrightness)
            prefs.edit().putBoolean(Prefs.KEY_GESTURE_BRIGHTNESS, currentGestureBrightness).apply()
        }
        
        btnSettingsGestureVolume?.setOnClickListener {
            currentGestureVolume = !currentGestureVolume
            updateGestureVolumeText(currentGestureVolume)
            prefs.edit().putBoolean(Prefs.KEY_GESTURE_VOLUME, currentGestureVolume).apply()
        }
        
        btnSettingsAudioPassthrough?.setOnClickListener {
            currentAudioPassthrough = !currentAudioPassthrough
            updateAudioPassthroughText(currentAudioPassthrough)
            prefs.edit().putBoolean(Prefs.KEY_AUDIO_PASSTHROUGH, currentAudioPassthrough).apply()
            Toast.makeText(this, "音频直通设置已保存，下次播放生效", Toast.LENGTH_SHORT).show()
        }

        val btnSettingsPip = findViewById<View>(R.id.btnSettingsPip)
        btnSettingsPip?.setOnClickListener {
            if (!currentEnablePip) {
                // 准备开启时，检查是否具有画中画权限
                var hasPermission = false
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val appOps = getSystemService(android.content.Context.APP_OPS_SERVICE) as android.app.AppOpsManager
                    val mode = appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), packageName)
                    hasPermission = (mode == android.app.AppOpsManager.MODE_ALLOWED)
                }

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && !hasPermission) {
                    Toast.makeText(this, "请在此页面允许“画中画”权限", Toast.LENGTH_LONG).show()
                    try {
                        // 尝试打开专属的画中画权限设置页
                        val intent = Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS")
                        intent.data = android.net.Uri.parse("package:$packageName")
                        startActivity(intent)
                    } catch (e: Exception) {
                        // 如果系统不支持专属页面，则打开应用详情页
                        try {
                            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            intent.data = android.net.Uri.parse("package:$packageName")
                            startActivity(intent)
                        } catch (e2: Exception) {
                            com.mediaplayer.app.util.RemoteLogger.e("MainActivity", "无法跳转到设置页面", e2)
                        }
                    }
                    // 权限未给，拦截此次开关切换，让用户下次再点
                    return@setOnClickListener
                }
            }
            currentEnablePip = !currentEnablePip
            updatePipText(currentEnablePip)
            prefs.edit().putBoolean(Prefs.KEY_ENABLE_PIP, currentEnablePip).apply()
            pipController.updatePipParams(playerHelper?.isPlaying() == true)
            Toast.makeText(this, "画中画模式已" + (if (currentEnablePip) "开启" else "关闭"), Toast.LENGTH_SHORT).show()
        }
        
        fun updateMemoryText(enabled: Boolean) {
            if (enabled) {
                tvSettingsMemoryValue?.text = "专属配置"
                tvSettingsMemoryValue?.setTextColor(android.graphics.Color.parseColor("#00E5FF"))
            } else {
                tvSettingsMemoryValue?.text = "跟随系统"
                tvSettingsMemoryValue?.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
            }
        }
        
        btnSettingsMemory?.setOnClickListener {
            val channel = allChannels.getOrNull(currentChannelIndex)
            if (channel == null) {
                Toast.makeText(this, "当前无频道，无法设置独立记忆", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            isIndependentMemoryOn = !isIndependentMemoryOn
            updateMemoryText(isIndependentMemoryOn)
            
            if (isIndependentMemoryOn) {
                com.mediaplayer.app.data.ChannelMemoryManager.updateDecoder(channel.id, currentDecoderMode)
                com.mediaplayer.app.data.ChannelMemoryManager.updateCore(channel.id, currentCore)
                Toast.makeText(this, "已开启独立记忆", Toast.LENGTH_SHORT).show()
            } else {
                com.mediaplayer.app.data.ChannelMemoryManager.clearDecoderAndCore(channel.id)
                currentDecoderMode = prefs.getInt(Prefs.KEY_DECODER_MODE, Prefs.DECODER_MODE_AUTO)
                currentCore = prefs.getInt(Prefs.KEY_PLAYER_CORE, Prefs.PLAYER_CORE_AUTO)
                updateDecoderText(currentDecoderMode)
                updateCoreText(currentCore)
                Toast.makeText(this, "已恢复系统默认", Toast.LENGTH_SHORT).show()
            }
        }
        
        btnSettingsDecoder?.setOnClickListener {
            val channel = allChannels.getOrNull(currentChannelIndex)
            currentDecoderMode = when (currentDecoderMode) {
                Prefs.DECODER_MODE_AUTO -> Prefs.DECODER_MODE_HARDWARE
                Prefs.DECODER_MODE_HARDWARE -> Prefs.DECODER_MODE_SOFTWARE
                else -> Prefs.DECODER_MODE_AUTO
            }
            updateDecoderText(currentDecoderMode)
            
            if (isIndependentMemoryOn && channel != null) {
                com.mediaplayer.app.data.ChannelMemoryManager.updateDecoder(channel.id, currentDecoderMode)
                Toast.makeText(this, "专属解码模式已保存", Toast.LENGTH_SHORT).show()
            } else {
                prefs.edit().putInt(Prefs.KEY_DECODER_MODE, currentDecoderMode).apply()
                Toast.makeText(this, "全局解码模式已保存，下次播放生效", Toast.LENGTH_SHORT).show()
            }
        }
        
        btnSettingsCore?.setOnClickListener {
            val channel = allChannels.getOrNull(currentChannelIndex)
            currentCore = when (currentCore) {
                Prefs.PLAYER_CORE_AUTO -> Prefs.PLAYER_CORE_EXO
                Prefs.PLAYER_CORE_EXO -> Prefs.PLAYER_CORE_VLC
                Prefs.PLAYER_CORE_VLC -> Prefs.PLAYER_CORE_IJK
                else -> Prefs.PLAYER_CORE_AUTO
            }
            updateCoreText(currentCore)
            
            if (isIndependentMemoryOn && channel != null) {
                com.mediaplayer.app.data.ChannelMemoryManager.updateCore(channel.id, currentCore)
                Toast.makeText(this, "专属播放内核已保存", Toast.LENGTH_SHORT).show()
            } else {
                prefs.edit().putInt(Prefs.KEY_PLAYER_CORE, currentCore).apply()
                Toast.makeText(this, "全局播放内核已保存，下次播放生效", Toast.LENGTH_SHORT).show()
            }
        }
        
        val btnSettingsShowLogo = findViewById<View>(R.id.btnSettingsShowLogo)
        btnSettingsShowLogo?.setOnClickListener {
            currentShowLogo = !currentShowLogo
            updateShowLogoText(currentShowLogo)
            prefs.edit().putBoolean(Prefs.KEY_SHOW_CHANNEL_LOGO, currentShowLogo).apply()
            
            // 立即生效
            if (::channelAdapter.isInitialized) {
                channelAdapter.showLogo = currentShowLogo
                channelAdapter.notifyDataSetChanged()
            }
        }

        val btnSettingsTimeMode = findViewById<View>(R.id.btnSettingsTimeMode)
        val tvSettingsTimeModeValue = findViewById<TextView>(R.id.tvSettingsTimeModeValue)
        val timeOverlayView = findViewById<com.mediaplayer.app.ui.widget.TimeOverlayView>(R.id.timeOverlayView)
        var currentTimeMode = prefs.getInt(Prefs.KEY_TIME_SHOW_MODE, Prefs.TIME_SHOW_MODE_HIDDEN)
        
        fun updateTimeModeText() {
            tvSettingsTimeModeValue?.text = when (currentTimeMode) {
                Prefs.TIME_SHOW_MODE_HIDDEN -> "隐藏"
                Prefs.TIME_SHOW_MODE_ALWAYS -> "常显"
                Prefs.TIME_SHOW_MODE_EVERY_HOUR -> "整点"
                Prefs.TIME_SHOW_MODE_HALF_HOUR -> "半点"
                else -> "未知"
            }
        }
        updateTimeModeText()

        btnSettingsTimeMode?.setOnClickListener {
            currentTimeMode = (currentTimeMode + 1) % 4
            updateTimeModeText()
            prefs.edit().putInt(Prefs.KEY_TIME_SHOW_MODE, currentTimeMode).apply()
            timeOverlayView?.refreshMode()
        }
        
        btnSettingsScale?.setOnClickListener {
            currentScaleMode = when (currentScaleMode) {
                Prefs.SCALE_MODE_DEFAULT -> Prefs.SCALE_MODE_FILL
                Prefs.SCALE_MODE_FILL -> Prefs.SCALE_MODE_STRETCH
                Prefs.SCALE_MODE_STRETCH -> Prefs.SCALE_MODE_16_10
                Prefs.SCALE_MODE_16_10 -> Prefs.SCALE_MODE_4_3
                Prefs.SCALE_MODE_4_3 -> Prefs.SCALE_MODE_CROP
                else -> Prefs.SCALE_MODE_DEFAULT
            }
            updateScaleText(currentScaleMode)
            prefs.edit().putInt(Prefs.KEY_SCALE_MODE, currentScaleMode).apply()
            
            // 立即生效
            playerHelper?.setAspectRatio(currentScaleMode)
        }

        btnSettingsAutoStart?.setOnClickListener {
            currentAutoStart = !currentAutoStart
            updateAutoStartText(currentAutoStart)
            prefs.edit().putBoolean(Prefs.KEY_AUTO_START, currentAutoStart).apply()
        }

        btnSettingsReverseChannels?.setOnClickListener {
            currentReverseChannels = !currentReverseChannels
            updateReverseChannelsText(currentReverseChannels)
            prefs.edit().putBoolean(Prefs.KEY_REVERSE_CHANNEL_KEYS, currentReverseChannels).apply()
        }

        btnSettingsCheckUpdate?.setOnClickListener {
            com.mediaplayer.app.util.UpdateManager.checkUpdate(this, lifecycleScope, true)
        }

        etSettingsUrl?.setText(url)
        
        val cacheMs = prefs.getInt(Prefs.KEY_NETWORK_CACHE, Prefs.DEFAULT_NETWORK_CACHE)
        val progress = if (cacheMs == 0) 0 else (cacheMs / 50).coerceIn(1, 100)
        sbSettingsCache?.progress = progress
        tvSettingsCacheValue?.text = if (cacheMs == 0) " 自动" else " ${"%.2f".format(cacheMs / 1000f)} 秒"

        sbSettingsCache?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val newCacheMs = if (progress == 0) 0 else progress * 50
                tvSettingsCacheValue?.text = if (newCacheMs == 0) " 自动" else " ${"%.2f".format(newCacheMs / 1000f)} 秒"
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                val p = seekBar?.progress ?: 0
                val newCacheMs = if (p == 0) 0 else p * 50
                prefs.edit().putInt(Prefs.KEY_NETWORK_CACHE, newCacheMs).apply()
                playerHelper?.setCacheDuration(newCacheMs)
                Toast.makeText(this@MainActivity, "网络缓存已保存，下次播放生效", Toast.LENGTH_SHORT).show()
            }
        })

        // 音量设置
        val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val sbVolume = findViewById<SeekBar>(R.id.sbSettingsVolume)
        sbVolume?.max = maxVolume
        sbVolume?.progress = currentVolume
        sbVolume?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, progress, 0)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // 亮度设置
        val sbBrightness = findViewById<SeekBar>(R.id.sbSettingsBrightness)
        val tvBrightnessLabel = findViewById<TextView>(R.id.tvSettingsBrightnessLabel)
        sbBrightness?.max = 101
        
        val currentBrightness = window.attributes.screenBrightness
        if (currentBrightness < 0) {
            sbBrightness?.progress = 101
            tvBrightnessLabel?.text = "画面亮度 (自动)"
        } else {
            sbBrightness?.progress = ((currentBrightness.coerceAtLeast(0.01f)) * 100).toInt().coerceIn(1, 100)
            tvBrightnessLabel?.text = "画面亮度"
        }

        sbBrightness?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val lp = window.attributes
                    if (progress == 101) {
                        lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        tvBrightnessLabel?.text = "画面亮度 (自动)"
                    } else {
                        lp.screenBrightness = max(0.01f, progress / 100f)
                        tvBrightnessLabel?.text = "画面亮度"
                    }
                    window.attributes = lp
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        
        findViewById<TextView>(R.id.tvQQGroup)?.setOnClickListener {
            try {
                // 使用 mqqapi 协议直接唤起手机 QQ 加群页面
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("mqqapi://card/show_pslcard?src_type=internal&version=1&uin=864744268&card_type=group&source=qrcode"))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: Exception) {
                // 未安装 QQ 或拉起失败，复制群号到剪贴板
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("QQ群", "864744268")
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@MainActivity, "未检测到QQ应用，已复制群号: 864744268", Toast.LENGTH_SHORT).show()
            }
        }
        
        btnSettingsAbout?.setOnClickListener {
            showAboutDevice()
        }
    }

    private fun showAboutDevice() {
        val layoutAboutDevice = findViewById<View>(R.id.layoutAboutDevice)
        if (layoutAboutDevice?.visibility == View.VISIBLE) return
        layoutSettingsMenu?.visibility = View.GONE
        layoutAboutDevice?.visibility = View.VISIBLE
        layoutAboutDevice?.requestFocus()

        findViewById<TextView>(R.id.tvAboutOs)?.text = "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
        findViewById<TextView>(R.id.tvAboutHardware)?.text = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        
        val cpuAbi = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            android.os.Build.SUPPORTED_ABIS.joinToString(", ")
        } else {
            @Suppress("DEPRECATION")
            android.os.Build.CPU_ABI
        }
        findViewById<TextView>(R.id.tvAboutCpu)?.text = cpuAbi
        
        val am = getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val totalMemGb = String.format("%.1f GB", mi.totalMem / (1024.0 * 1024.0 * 1024.0))
        findViewById<TextView>(R.id.tvAboutRam)?.text = totalMemGb
        
        findViewById<TextView>(R.id.tvAboutIp)?.text = com.mediaplayer.app.util.NetworkUtils.getLocalIpAddress() ?: "未知"
        
        val deviceId = com.mediaplayer.app.data.api.ClientAuthManager(this).getDeviceId()
        findViewById<View>(R.id.rowAboutMac)?.visibility = View.VISIBLE
        findViewById<TextView>(R.id.tvAboutMac)?.text = deviceId
        
        layoutAboutDevice?.setOnClickListener {
            hideAboutDevice()
        }
    }

    private fun hideAboutDevice() {
        val layoutAboutDevice = findViewById<View>(R.id.layoutAboutDevice)
        if (layoutAboutDevice?.visibility == View.GONE) return
        layoutAboutDevice?.visibility = View.GONE
        showSettingsMenu()
    }

    private fun showSettingsMenu() {
        if (layoutSettingsMenu?.visibility == View.VISIBLE) return
        
        // 隐藏左侧菜单
        layoutZappingMenu?.visibility = View.GONE
        
        layoutSettingsMenu?.visibility = View.VISIBLE
        
        syncSettingsMemoryState()
        
        // 填充关于信息
        val authManager = ClientAuthManager(this)
        var versionText = "1.0.0"
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val vCode = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pInfo)
            versionText = "${pInfo.versionName} ($vCode)"
        } catch (_: Exception) {}
        
        val authStatus = when (authManager.getStatus()) {
            "approved" -> {
                val planName = authManager.getPlanName()
                var expiresAt = authManager.getExpiresAt()
                
                // 格式化时间字符串：只截取到“日” (YYYY-MM-DD)
                if (!expiresAt.isNullOrEmpty()) {
                    if (expiresAt.startsWith("0001-01-01")) {
                        expiresAt = "永久"
                    } else if (expiresAt.length >= 10) {
                        expiresAt = expiresAt.substring(0, 10)
                    }
                }
                
                val pName = if (planName.isNullOrEmpty()) "无" else planName
                val expTime = if (expiresAt.isNullOrEmpty()) "永久" else expiresAt
                val serverName = authManager.getServerName()
                val serverLine = if (!serverName.isNullOrEmpty()) "$serverName\n" else ""
                "${serverLine}套餐: $pName\n过期时间: $expTime"
            }
            "pending" -> "授权状态: 等待审批"
            "rejected" -> "授权状态: 已拒绝"
            "banned" -> "授权状态: 已封禁"
            "expired" -> "授权状态: 已过期"
            else -> "授权状态: 未注册"
        }
        tvSettingsInfo?.text = "应用版本: $versionText\n设备 ID: ${authManager.getDeviceId()}\n$authStatus"
        
        // --- QR Code Logic ---
        val ip = com.mediaplayer.app.util.NetworkUtils.getLocalIpAddress()
        if (ip != null) {
            setupQrConfigServer {
                hideSettingsMenu()
                Toast.makeText(this@MainActivity, "配置已保存，重新加载中...", Toast.LENGTH_LONG).show()
                authFlowManager.startAuthFlow()
            }
            val qrPort = configWebServer?.actualPort ?: 9528
            val qrUrl = "http://$ip:$qrPort/"
            val bitmap = com.mediaplayer.app.util.QRCodeHelper.generateQRCode(qrUrl, 400)
            ivQrCode?.setImageBitmap(bitmap)
            tvQrConfigHint?.text = "手机扫码快速配置服务器\n或者访问: $qrUrl"
            tvQrConfigHint?.setOnClickListener {
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(qrUrl))
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            layoutQrConfig?.visibility = View.VISIBLE
        } else {
            layoutQrConfig?.visibility = View.GONE
        }
        
        findViewById<View>(R.id.btnSettingsScale)?.requestFocus()
    }

    private fun initPlayerWithCore(core: Int) {
        val listener = object : com.mediaplayer.app.util.IPlayerHelper.PlayerListener {
            override fun onBuffering(percent: Float) {
                uiHandler.post {
                    if (currentPlaybackState != PlaybackState.BUFFERING) {
                        currentPlaybackState = PlaybackState.BUFFERING
                        stateStartTime = System.currentTimeMillis()
                    }
                    if (percent >= 100f || percent == 0f) {
                        progressBuffering?.visibility = View.GONE
                        dismissSnapshot() // 缓冲完成，移除截帧（兜底音频流）
                    } else {
                        progressBuffering?.visibility = View.VISIBLE
                    }
                }
            }
            override fun onPlaying(resolution: String) {
                uiHandler.post {
                    currentPlaybackState = PlaybackState.PLAYING
                    stateStartTime = System.currentTimeMillis()
                    progressBuffering?.visibility = View.GONE
                    dismissSnapshot() // 新流首帧到来，移除截帧占位图
                    continuousSkipCount = 0
                    pipController.updatePipParams(true)

                    // VOD 模式设置：根据 content_type 判断是否为点播
                    val isVod = isCurrentChannelVod()
                    osdOverlayView?.setVodMode(isVod)
                    if (isVod) {
                        osdOverlayView?.setVodSeekListener { seekMs ->
                            playerHelper?.setTime(seekMs)
                        }
                        osdOverlayView?.startVodProgressUpdater(
                            positionProvider = { playerHelper?.getTime() ?: 0L },
                            durationProvider = { playerHelper?.getDuration() ?: 0L }
                        )
                    }

                    if (resolution.isNotEmpty()) {
                        val prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
                        val decoderMode = prefs.getInt(Prefs.KEY_DECODER_MODE, Prefs.DECODER_MODE_AUTO)
                        val decoderStr = when (decoderMode) {
                            Prefs.DECODER_MODE_HARDWARE -> "HW"
                            Prefs.DECODER_MODE_SOFTWARE -> "SW"
                            else -> "Auto"
                        }
                        val coreStr = when (core) {
                            Prefs.PLAYER_CORE_EXO -> "ExoPlayer"
                            Prefs.PLAYER_CORE_IJK -> "IJKPlayer"
                            else -> "VLC"
                        }
                        
                        val fullInfo = buildString {
                            if (resolution.isNotEmpty()) append(resolution)
                            if (decoderStr.isNotEmpty()) {
                                if (isNotEmpty()) append(" | ")
                                append(decoderStr)
                            }
                            if (coreStr.isNotEmpty()) {
                                if (isNotEmpty()) append(" | ")
                                append(coreStr)
                            }
                        }
                        osdOverlayView?.setInfoText(fullInfo.toString())
                        com.mediaplayer.app.util.RemoteLogger.i("Player", "Playback started successfully. Stream info: $fullInfo")
                    }
                }
            }
            override fun onError() {
                uiHandler.post { 
                    currentPlaybackState = PlaybackState.IDLE
                    pipController.updatePipParams(false)
                    dismissSnapshot() // 播放失败也移除截帧
                    handlePlaybackError(isNetworkTimeout = false) 
                }
            }
            override fun onPlaybackCompleted() {
                uiHandler.post {
                    currentPlaybackState = PlaybackState.IDLE
                    pipController.updatePipParams(false)
                    handlePlaybackCompleted()
                }
            }
            override fun onMediaInfoReady(badgeInfo: com.mediaplayer.app.util.StreamBadgeInfo) {
                // 面向发烧友/PT玩家：在此丢弃通俗的中文标签，保留 onPlaying 时最初提取的底层原始媒体流参数。
            }
            override fun onTracksChanged(
                audioTracks: List<AudioTrackInfo>,
                subtitleTracks: List<SubtitleTrackInfo>
            ) {
                uiHandler.post {
                    cachedAudioTracks = audioTracks
                    cachedSubtitleTracks = subtitleTracks
                    updateTrackButtonVisibility()
                    
                    // 应用独立记忆（音轨/字幕）
                    val channel = allChannels.getOrNull(currentChannelIndex)
                    if (channel != null) {
                        val mem = com.mediaplayer.app.data.ChannelMemoryManager.getMemory(channel.id)
                        if (mem != null) {
                            // 延时 500ms 应用记忆，避免 ExoPlayer 正在初始化渲染器时发生时钟冲突 (Multiple renderer media clocks enabled)
                            uiHandler.postDelayed({
                                // 重新校验频道是否没变
                                val currentChan = allChannels.getOrNull(currentChannelIndex)
                                if (currentChan?.id == channel.id) {
                                    if (mem.audioTrackId != null) {
                                        val target = cachedAudioTracks?.find { it.id == mem.audioTrackId }
                                        if (target != null && !target.isSelected) {
                                            playerHelper?.selectAudioTrack(target.index)
                                        }
                                    }
                                    if (mem.subtitleTrackId != null) {
                                        if (mem.subtitleTrackId == "disable") {
                                            val currentSelected = cachedSubtitleTracks?.find { it.isSelected }
                                            if (currentSelected != null) {
                                                playerHelper?.disableSubtitle()
                                            }
                                        } else {
                                            val target = cachedSubtitleTracks?.find { it.id == mem.subtitleTrackId }
                                            if (target != null && !target.isSelected) {
                                                playerHelper?.selectSubtitleTrack(target.index)
                                            }
                                        }
                                    }
                                }
                            }, 500)
                        }
                    }
                }
            }
        }

        try {
            when (core) {
                Prefs.PLAYER_CORE_EXO -> {
                    playerHelper = com.mediaplayer.app.util.ExoPlayerHelper(this, videoLayout as android.view.ViewGroup, listener)
                }
                Prefs.PLAYER_CORE_IJK -> {
                    playerHelper = com.mediaplayer.app.util.IjkPlayerHelper(this, videoLayout as android.view.ViewGroup, listener)
                }
                else -> {
                    val vlcVideoLayout = org.videolan.libvlc.util.VLCVideoLayout(this)
                    vlcVideoLayout.layoutParams = android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
                    videoLayout?.addView(vlcVideoLayout)
                    
                    playerHelper = com.mediaplayer.app.util.VlcPlayerHelper(this, vlcVideoLayout, listener)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "播放内核初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
            // 回退到默认状态或触发错误逻辑
            listener.onError()
        }

        // 创建播放器后，应用保存的画面比例设置
        val scalePrefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        val savedScaleMode = scalePrefs.getInt(Prefs.KEY_SCALE_MODE, Prefs.SCALE_MODE_DEFAULT)
        playerHelper?.setAspectRatio(savedScaleMode)
    }

    private fun setupQrConfigServer(onUrlUpdated: () -> Unit) {
        if (configWebServer == null) {
            configWebServer = com.mediaplayer.app.server.ConfigWebServer(this, 0) { urls ->
                runOnUiThread {
                    val prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
                    saveServerList(urls)
                    prefs.edit().putString(Prefs.KEY_SERVER_URL, urls.first()).apply()
                    com.mediaplayer.app.data.api.ApiClient.init(urls.first())
                    authManager.clearAuth()
                    onUrlUpdated()
                }
            }
            try {
                configWebServer?.start()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "配置服务器启动失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveServerList(urls: List<String>) {
        val json = com.google.gson.Gson().toJson(urls)
        getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
            .edit()
            .putString(Prefs.KEY_SERVER_URLS, json)
            .apply()
    }

    private fun getServerList(): List<String> {
        val json = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
            .getString(Prefs.KEY_SERVER_URLS, null) ?: return emptyList()
        return try {
            val arr = com.google.gson.Gson().fromJson(json, Array<String>::class.java)
            arr.toList().filter { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun hideSettingsMenu() {
        layoutSettingsMenu?.visibility = View.GONE
        activeListArea = "channels"
    }

    private fun updateCoreText(core: Int) {
        findViewById<TextView>(R.id.tvSettingsCoreValue)?.text = when (core) {
            Prefs.PLAYER_CORE_EXO -> "ExoPlayer"
            Prefs.PLAYER_CORE_VLC -> "VLC"
            Prefs.PLAYER_CORE_IJK -> "IJKPlayer"
            else -> "智能切换"
        }
    }

    private fun updateShowLogoText(show: Boolean) {
        findViewById<TextView>(R.id.tvSettingsShowLogoValue)?.text = if (show) "显示" else "隐藏"
    }

    private fun handlePlaybackCompleted() {
        currentPlaybackState = PlaybackState.IDLE
        progressBuffering?.visibility = View.GONE

        // 回看流自然结束
        if (currentCatchupStartTime != null) {
            osdOverlayView?.setInfoText("回看播放完毕".toString())
            showOsd()
            currentCatchupStartTime = null
            currentCatchupChannelIndex = -1
            val channel = allChannels.getOrNull(currentChannelIndex)
            if (channel != null) loadEpgForChannel(channel)
            com.mediaplayer.app.util.RemoteLogger.i("Player", "Catchup playback completed naturally.")
            return
        }

        // VOD 点播自然播放完毕
        if (isCurrentChannelVod()) {
            stopVodSeek()
            osdOverlayView?.stopVodProgressUpdater()
            osdOverlayView?.setVodPlaying(false)
            osdOverlayView?.setInfoText("播放完毕")
            showOsd()
            com.mediaplayer.app.util.RemoteLogger.i("Player", "VOD playback completed naturally.")
            return
        }

        // 直播流遇到结束事件，说明网络流已断开(TCP EOF)，执行断线重连 (静默重连，不打扰用户)
        com.mediaplayer.app.util.RemoteLogger.i("Player", "Live stream STATE_ENDED unexpectedly. Auto reconnecting...")
        
        
        uiHandler.postDelayed({
            playCurrentLineInTv()
        }, 1000)
    }

    private fun handlePlaybackError(isNetworkTimeout: Boolean = false) {
        currentPlaybackState = PlaybackState.IDLE
        progressBuffering?.visibility = View.GONE

        val prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        val globalCore = prefs.getInt(Prefs.KEY_PLAYER_CORE, Prefs.PLAYER_CORE_AUTO)

        val channel = allChannels.getOrNull(currentChannelIndex)
        val lines = channel?.getLinesSafely() ?: emptyList()

        // ===== 手动指定内核模式：尝试切换线路（不做内核切换） =====
        if (globalCore != Prefs.PLAYER_CORE_AUTO) {
            if (lines.isNotEmpty() && currentLineIndex < lines.size - 1) {
                // 还有其他线路，按顺序尝试
                currentLineIndex++
                val coreName = when (globalCore) {
                    Prefs.PLAYER_CORE_EXO -> "ExoPlayer"
                    Prefs.PLAYER_CORE_IJK -> "IJKPlayer"
                    else -> "VLC"
                }
                Toast.makeText(this, "当前线路无法播放，切换线路 ${currentLineIndex + 1}...", Toast.LENGTH_SHORT).show()
                com.mediaplayer.app.util.RemoteLogger.i("Player", "Manual core ($coreName) failed. Switching to line ${currentLineIndex + 1}")
                playCurrentLineInTv()
                return
            } else {
                // 所有线路都失败
                val coreName = when (globalCore) {
                    Prefs.PLAYER_CORE_EXO -> "ExoPlayer"
                    Prefs.PLAYER_CORE_IJK -> "IJKPlayer"
                    else -> "VLC"
                }
                currentLineIndex = 0
                osdOverlayView?.setInfoText("所有线路均无法播放，请切换为智能模式或更换其他频道".toString())
                showOsd()
                com.mediaplayer.app.util.RemoteLogger.e("Player", "Manual core ($coreName) failed on all lines.")
                return
            }
        }

        // ===== 智能模式：先切换线路，所有线路失败再切换内核 =====

        // 1. 优先切换线路
        if (lines.isNotEmpty() && currentLineIndex < lines.size - 1) {
            currentLineIndex++
            coreRetryLevel = 0  // 切换线路后重置内核重试计数
            Toast.makeText(this@MainActivity, "当前线路失效，切换线路 ${currentLineIndex + 1}...", Toast.LENGTH_SHORT).show()
            com.mediaplayer.app.util.RemoteLogger.i("Player", "Switching to line ${currentLineIndex + 1}")
            playCurrentLineInTv()
            return
        }

        // 2. 所有线路都失败后，尝试切换内核（非网络超时情况）
        if (!isNetworkTimeout && coreRetryLevel < 2) {
            currentLineIndex = 0  // 重置线路索引，用新内核重新遍历所有线路
            coreRetryLevel++
            val coreName = when (coreRetryLevel) {
                1 -> "VLC"
                2 -> "IJKPlayer"
                else -> "ExoPlayer"
            }
            Toast.makeText(this, "所有线路失败，尝试使用 $coreName 重试...", Toast.LENGTH_SHORT).show()
            com.mediaplayer.app.util.RemoteLogger.i("Player", "All lines failed. Retrying with core: $coreName")
            playCurrentLineInTv()
            return
        }

        // 3. 所有线路和内核都失败
        coreRetryLevel = 0
        currentLineIndex = 0
        
        val msg = if (isNetworkTimeout) "网络超时，播放失败" else "所有线路和内核均已失效"
        com.mediaplayer.app.util.RemoteLogger.e("Player", "All lines and cores failed for channel: ${channel?.name ?: "Unknown"}. Reason: $msg")
        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
        
        continuousSkipCount++
        if (continuousSkipCount >= maxAutoSkips) {
            Toast.makeText(this@MainActivity, "多个频道连续播放失败，已停止自动换台", Toast.LENGTH_LONG).show()
            continuousSkipCount = 0
        } else {
            Toast.makeText(this@MainActivity, "当前频道失效，自动为您跳过", Toast.LENGTH_SHORT).show()
            if (allChannels.isNotEmpty()) {
                val nextChannelIndex = (currentChannelIndex + 1) % allChannels.size
                uiHandler.postDelayed({
                    playTvChannel(nextChannelIndex, isAutoSkip = true)
                }, 1000)
            }
        }
    }

    private var resolveJob: kotlinx.coroutines.Job? = null
    private var playGeneration: Int = 0
    private var snapshotGeneration: Int = 0

    /**
     * 截取当前 videoLayout 画面作为切台占位图，覆盖在最上层，消除切台黑屏。
     */
    private suspend fun captureSnapshotAsync(timeoutMs: Long = 50L) {
        val prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        if (prefs.getInt(Prefs.KEY_RENDER_VIEW, Prefs.RENDER_VIEW_SURFACE) == Prefs.RENDER_VIEW_TEXTURE) {
            return
        }

        val vl = videoLayout ?: return
        val iv = snapshotOverlay ?: return
        val currentGen = ++snapshotGeneration
        try {
            // 使用 PixelCopy（API 26+）获取 SurfaceView 的截图
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                val bitmap = android.graphics.Bitmap.createBitmap(vl.width, vl.height, android.graphics.Bitmap.Config.ARGB_8888)
                kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                    kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                        val copyListener = android.view.PixelCopy.OnPixelCopyFinishedListener { result ->
                            if (continuation.isActive) {
                                if (result == android.view.PixelCopy.SUCCESS) {
                                    if (currentGen == snapshotGeneration) {
                                        iv.setImageBitmap(bitmap)
                                        iv.visibility = View.VISIBLE
                                    }
                                }
                                continuation.resume(Unit)
                            }
                        }
                        android.view.PixelCopy.request(this@MainActivity.window, bitmap, copyListener, android.os.Handler(android.os.Looper.getMainLooper()))
                    }
                }
            } else {
                val bitmap = android.graphics.Bitmap.createBitmap(vl.width, vl.height, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                vl.draw(canvas)
                if (currentGen == snapshotGeneration) {
                    iv.setImageBitmap(bitmap)
                    iv.visibility = View.VISIBLE
                }
            }
        } catch (_: Exception) {
            // 截图失败不影响后台
        }
    }

    /**
     * 移除后台占位图显示。
     */
    private fun dismissSnapshot() {
        snapshotGeneration++ // 增加代数，使得任何尚未完成的 PixelCopy 回调作废
        snapshotOverlay?.let {
            it.visibility = View.GONE
            it.setImageBitmap(null)
        }
    }

    private fun playTvChannel(index: Int, isAutoSkip: Boolean = false) {
        if (!isAutoSkip) {
            continuousSkipCount = 0
        }
        if (allChannels.isEmpty() || index < 0 || index >= allChannels.size) return

        // 防止重复起播：如果已经在播放同一个频道，跳过
        if (index == currentChannelIndex && playerHelper?.isPlaying() == true) {
            return
        }

        // 切换频道时先退出 VOD 模式，播放成功后再根据 content_type 设置
        osdOverlayView?.setVodMode(false)

        // 关闭音轨/字幕选择面板并清空缓存
        if (isTrackPanelOpen) hideTrackSelectionPanel()
        cachedAudioTracks = null
        cachedSubtitleTracks = null
        
        // 取消上一个频道的 URL 解析协程，各播放器 play() 内部会自动 stop() 旧流。
        resolveJob?.cancel()
        resolveJob = null

        currentCatchupStartTime = null
        currentCatchupChannelIndex = -1
        
        if (currentChannelIndex != index) {
            val channel = allChannels.getOrNull(index)
            val mem = channel?.let { com.mediaplayer.app.data.ChannelMemoryManager.getMemory(it.id) }
            currentLineIndex = mem?.lineIndex ?: 0
            coreRetryLevel = 0
        }
        currentChannelIndex = index
        playCurrentLineInTv()
    }
    
    private fun playCurrentLineInTv() {
        val channel = allChannels.getOrNull(currentChannelIndex) ?: return

        // 线路切换时停止 VOD 进度更新，避免在过渡期读到不一致的状态
        stopVodSeek()
        osdOverlayView?.stopVodProgressUpdater()

        osdOverlayView?.setChannelNum(String.format("%03d", channel.globalIndex + 1).toString())
        osdOverlayView?.setChannelName(channel.name.toString())
        
        val lines = channel.getLinesSafely()
        if (lines.isEmpty()) return
        if (currentLineIndex >= lines.size) currentLineIndex = 0
        val line = lines[currentLineIndex]
        
        osdOverlayView?.setLineInfo("${currentLineIndex + 1}/${lines.size}".toString())
        
        osdOverlayView?.setInfoText(if (lines.size > 1) "连接中... (线路 ${currentLineIndex + 1}/${lines.size})" else "连接中...".toString())
        
        // 记忆功能：保存最后播放的频道 ID 和分组 ID
        val prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        prefs.edit()
            .putLong("last_channel_id", channel.id)
            .putLong("last_group_id", channel.groupId)
            .apply()
        
        loadEpgForChannel(channel)
        showOsd()

        // 获取频道独立记忆
        val mem = com.mediaplayer.app.data.ChannelMemoryManager.getMemory(channel.id)

        // 同步当前频道的解码模式记忆
        currentDecoderMode = mem?.decoderMode ?: prefs.getInt(Prefs.KEY_DECODER_MODE, Prefs.DECODER_MODE_AUTO)

        // 核心匹配逻辑（优先使用独立记忆的内核）
        var globalCore = mem?.playerCore ?: prefs.getInt(Prefs.KEY_PLAYER_CORE, Prefs.PLAYER_CORE_AUTO)
        if (globalCore == 4) {
            globalCore = Prefs.PLAYER_CORE_AUTO
            // 注意：这里不要覆盖 prefs，因为这可能是某个频道的临时回退
        }
        
        var desiredCore = globalCore
        var coreText = ""
        
        if (globalCore == Prefs.PLAYER_CORE_AUTO) {
            if (coreRetryLevel > 0) {
                desiredCore = when (coreRetryLevel) {
                    1 -> { coreText = "容灾 (VLC)"; Prefs.PLAYER_CORE_VLC }
                    2 -> { coreText = "容灾 (IJK)"; Prefs.PLAYER_CORE_IJK }
                    else -> desiredCore
                }
            } else {
                desiredCore = when (line.streamType.lowercase()) {
                    "vlc" -> {
                        coreText = "智能 (VLC)"
                        Prefs.PLAYER_CORE_VLC
                    }
                    "ijk" -> {
                        coreText = "智能 (IJK)"
                        Prefs.PLAYER_CORE_IJK
                    }
                    "x5" -> {
                        coreText = "智能 (VLC)"
                        Prefs.PLAYER_CORE_VLC
                    }
                    "ts", "rtp", "udp" -> {
                        coreText = "智能 (Exo)"
                        Prefs.PLAYER_CORE_EXO
                    }
                    else -> {
                        coreText = "智能 (Exo)"
                        Prefs.PLAYER_CORE_EXO
                    }
                }
            }
        } else {
            coreText = when (desiredCore) {
                Prefs.PLAYER_CORE_EXO -> "ExoPlayer"
                Prefs.PLAYER_CORE_IJK -> "IJKPlayer"
                else -> "VLC"
            }
        }
        val displayType = if (line.streamType.isEmpty()) "AUTO" else line.streamType.uppercase()
        findViewById<android.widget.TextView>(com.mediaplayer.app.R.id.tvStreamType)?.text = "$displayType ($coreText)"

        // 判断当前已经实例化的 playerHelper 是否与所需的一致
        val isCoreMatch = when (desiredCore) {
            Prefs.PLAYER_CORE_EXO -> playerHelper is com.mediaplayer.app.util.ExoPlayerHelper
            Prefs.PLAYER_CORE_IJK -> playerHelper is com.mediaplayer.app.util.IjkPlayerHelper
            else -> playerHelper is com.mediaplayer.app.util.VlcPlayerHelper
        }

        val isCoreChanged = playerHelper == null || !isCoreMatch
        
        resolveJob = lifecycleScope.launch {
            val gen = ++playGeneration
            
            // 1. 方案一：异步截图，掩盖黑屏，且为方案二预留豁免逻辑
            if (!isCurrentChannelVod()) {
                captureSnapshotAsync(50L)
            }
            
            if (gen != playGeneration) return@launch
            
            // 2. 清理与重建播放器画布
            if (isCoreChanged) {
                playerHelper?.release()
                videoLayout?.removeAllViews()
                initPlayerWithCore(desiredCore)
            }
            
            // 确保遮罩在最顶层
            snapshotOverlay?.let { iv ->
                if (iv.parent == null) {
                    videoLayout?.addView(iv)
                }
                iv.bringToFront()
            }
            
            playerHelper?.setDecoderMode(currentDecoderMode)
            progressBuffering?.visibility = View.VISIBLE

            // 切换延迟，如果是新老内核重建，给予硬解缓冲时间
            if (isCoreChanged) {
                kotlinx.coroutines.delay(200)
            }
            val finalUrl = com.mediaplayer.app.util.StreamResolver.resolve(line.streamUrl, line.userAgent, line.customHeaders)
            
            // 如果在此期间又发生了切台，放弃本次播放，防止旧 resolve 协程覆盖新频道
            if (gen != playGeneration) {
                com.mediaplayer.app.util.RemoteLogger.i("Player", "Discarded stale play() for generation $gen (current: $playGeneration)")
                return@launch
            }
            
            val lowerUrl = finalUrl.lowercase()
            val streamTypeLower = line.streamType.lowercase()
            val isMulticastOrLive = lowerUrl.startsWith("udp://") || 
                                    lowerUrl.startsWith("rtp://") || 
                                    lowerUrl.contains(".ts") || 
                                    lowerUrl.contains(".flv") || 
                                    lowerUrl.contains(".m3u8") || 
                                    lowerUrl.contains("/stream/proxy/") || 
                                    streamTypeLower in listOf("ts", "rtp", "udp", "flv", "hls", "m3u8")
            // 对于组播、ts、flv等特殊流，放行看门狗（不检测假死）
            isWatchdogEnabledForCurrentStream = !isMulticastOrLive
            
            currentPlaybackState = PlaybackState.BUFFERING
            stateStartTime = System.currentTimeMillis()
            playerHelper?.play(finalUrl, line.userAgent, line.customHeaders)
        }
        
        // 启动/重置看门狗
        currentPlaybackState = PlaybackState.BUFFERING
        stateStartTime = System.currentTimeMillis()
        lastPlaybackTime = 0L
        frozenTimeCounter = 0
        uiHandler.removeCallbacks(watchdogRunnable)
        uiHandler.postDelayed(watchdogRunnable, 2000)
        
        // 频道列表中高亮当前播放频道
        channelAdapter.setPlayingChannelId(channel.id)
    }

    private fun showLineSelectionMenu() {
        val channel = allChannels.getOrNull(currentChannelIndex) ?: return
        val lines = channel.getLinesSafely()
        if (lines.size <= 1) {
            Toast.makeText(this, "当前频道只有一条线路", Toast.LENGTH_SHORT).show()
            return
        }
        
        layoutZappingMenu?.visibility = View.GONE
        layoutSettingsMenu?.visibility = View.GONE
        layoutEpgMenu?.visibility = View.GONE
        
        tvLineMenuTitle?.text = "线路"
        containerLines?.removeAllViews()
        
        var firstFocusableView: View? = null
        
        lines.forEachIndexed { index, line ->
            val tv = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    val margin8 = resources.getDimensionPixelSize(R.dimen.dp_8)
                    setMargins(0, margin8, 0, margin8)
                }
                val displayType = if (line.streamType.isEmpty()) "AUTO" else line.streamType.uppercase()
                text = "线路 ${index + 1} ($displayType)"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.sp_18))
                val pad32 = resources.getDimensionPixelSize(R.dimen.dp_32)
                val pad24 = resources.getDimensionPixelSize(R.dimen.dp_24)
                setPadding(pad32, pad24, pad32, pad24)
                isFocusable = true
                isClickable = true
                isFocusableInTouchMode = true
                setBackgroundResource(R.drawable.selector_channel_item)
                
                if (index == currentLineIndex) {
                    val displayType = if (line.streamType.isEmpty()) "AUTO" else line.streamType.uppercase()
                    setTextColor(android.graphics.Color.parseColor("#FFC107"))
                    text = "线路 ${index + 1} ($displayType) - 当前"
                } else {
                    setTextColor(android.graphics.Color.WHITE)
                }
                
                setOnClickListener {
                    if (currentLineIndex != index) {
                        currentLineIndex = index
                        val channel = allChannels.getOrNull(currentChannelIndex)
                        if (channel != null) {
                            com.mediaplayer.app.data.ChannelMemoryManager.updateLineIndex(channel.id, index)
                        }
                        Toast.makeText(this@MainActivity, "已手动切换至线路 ${index + 1}", Toast.LENGTH_SHORT).show()
                        playCurrentLineInTv()
                    }
                    hideLineSelectionMenu()
                }
            }
            containerLines?.addView(tv)
            if (index == currentLineIndex) {
                firstFocusableView = tv
            }
        }
        
        layoutLineMenu?.visibility = View.VISIBLE
        layoutLineMenu?.post {
            val target = firstFocusableView ?: containerLines?.getChildAt(0)
            target?.requestFocus()
        }
    }

    private fun hideLineSelectionMenu() {
        layoutLineMenu?.visibility = View.GONE
        activeListArea = "channels"
    }

    /**
     * 关闭所有活动面板（互斥逻辑）
     */
    private fun hideOtherActivePanels() {
        if (layoutLineMenu?.visibility == View.VISIBLE) {
            layoutLineMenu?.visibility = View.GONE
        }
        if (layoutEpgMenu?.visibility == View.VISIBLE) {
            hideEpgMenu()
        }
        if (layoutSettingsMenu?.visibility == View.VISIBLE) {
            hideSettingsMenu()
        }
        if (layoutZappingMenu?.visibility == View.VISIBLE) {
            uiHandler.removeCallbacks(hideZappingRunnable)
            hideZappingRunnable.run()
        }
        if (isTrackPanelOpen) {
            hideTrackSelectionPanel()
        }
        activeListArea = "channels"
    }

    /**
     * 判断当前播放频道是否为 VOD（点播）内容
     * 优先使用服务端下发的 content_type 字段，兜底用 stream_type 推断
     */
    private fun isCurrentChannelVod(): Boolean {
        val channel = allChannels.getOrNull(currentChannelIndex) ?: return false
        val lines = channel.getLinesSafely()
        val line = lines.getOrNull(currentLineIndex) ?: return false
        val ct = line.contentType.lowercase().trim()
        if (ct == "vod") return true
        if (ct == "live") return false
        // 自动推断：本地文件或明确的点播容器格式
        val st = line.streamType.lowercase()
        return st in listOf("mp4", "mkv", "avi", "mov", "webm")
    }

    private fun stopVodSeek() {
        vodSeekActive = false
        vodSeekDirection = 0
        vodSeekHandler.removeCallbacks(vodSeekRunnable)
        osdOverlayView?.isVodSeeking = false
        // 刷新 OSD 为最终状态
        showOsd()
    }

    private fun toggleVodPauseResume() {
        val player = playerHelper ?: return
        if (player.isPlaying()) {
            player.pause()
            osdOverlayView?.setVodPlaying(false)
        } else {
            player.resume()
            osdOverlayView?.setVodPlaying(true)
        }
    }

    /**
     * 根据缓存的轨道列表更新 OSD 按钮文本和可见性
     */
    private fun updateTrackButtonVisibility() {
        val audioTracks = cachedAudioTracks
        val subtitleTracks = cachedSubtitleTracks

        // 音轨按钮：仅在有 ≥2 条音轨时显示固定文案"音轨"
        if (audioTracks != null && audioTracks.size >= 2) {
            osdOverlayView?.updateAudioButton("音轨")
        } else {
            osdOverlayView?.updateAudioButton("")
        }

        // 字幕按钮：有 ≥1 条内嵌字幕时显示固定文案"字幕"
        if (subtitleTracks != null && subtitleTracks.any { it.index >= 0 }) {
            osdOverlayView?.updateSubtitleButton("字幕")
        } else {
            osdOverlayView?.updateSubtitleButton("")
        }

        // IJK 降级检测：内核不支持音轨切换时禁用按钮
        val isIjk = playerHelper is com.mediaplayer.app.util.IjkPlayerHelper
        osdOverlayView?.setTrackButtonsEnabled(!isIjk)
    }

    // ── 音轨/字幕选择面板 ──

    private fun showTrackPanel(type: String) {
        if (!isCurrentChannelVod() && currentCatchupChannelIndex < 0) return

        // 互斥：关闭其他面板
        hideOtherActivePanels()

        val tracks: List<Any>
        val title: String
        val isAudio = type == "audio"

        if (isAudio) {
            cachedAudioTracks = playerHelper?.getAudioTracks()
            tracks = cachedAudioTracks.orEmpty()
            title = "选择音轨"
        } else {
            cachedSubtitleTracks = playerHelper?.getSubtitleTracks()
            tracks = cachedSubtitleTracks.orEmpty()
            title = "选择字幕"
        }

        if (tracks.isEmpty()) return

        tvTrackPanelTitle?.text = title
        val selectedIndex = populateTrackListItems(containerTracks, tracks, isAudio)

        // 暂停 OSD 自动隐藏
        osdOverlayView?.removeCallbacks()
        layoutTrackMenu?.visibility = View.VISIBLE
        isTrackPanelOpen = true
        activeListArea = "track"

        // 自动聚焦当前选中的项目
        if (selectedIndex in 0 until (containerTracks?.childCount ?: 0)) {
            containerTracks?.getChildAt(selectedIndex)?.requestFocus()
        } else {
            containerTracks?.getChildAt(0)?.requestFocus()
        }
    }

    fun hideTrackSelectionPanel() {
        layoutTrackMenu?.visibility = View.GONE
        isTrackPanelOpen = false
        // 显示 OSD 并重新启动 5 秒倒计时
        osdOverlayView?.showOsd()

        // 隐藏后取消 focusable 避免残留焦点干扰
        containerTracks?.removeAllViews()
    }

    private fun populateTrackListItems(
        container: LinearLayout?,
        tracks: List<Any>,
        isAudio: Boolean
    ): Int {
        container?.removeAllViews()
        var focusIndex = 0

        @Suppress("UNCHECKED_CAST")
        val audioTracks = if (isAudio) tracks as? List<AudioTrackInfo> else null
        @Suppress("UNCHECKED_CAST")
        val subtitleTracks = if (!isAudio) tracks as? List<SubtitleTrackInfo> else null

        val maxIndex = if (isAudio) audioTracks?.size?.minus(1) ?: 0
                       else subtitleTracks?.size?.minus(1) ?: 0

        for (i in 0..maxIndex) {
            val isSelected: Boolean
            val label: String

            if (isAudio) {
                val t = audioTracks!![i]
                isSelected = t.isSelected
                val codecInfo = if (t.codec.isNotEmpty() || t.channelCount > 0) {
                    " (${t.codec}${if (t.channelCount > 0) " ${t.channelCount}.0" else ""})"
                } else ""
                label = t.label + codecInfo
            } else {
                val t = subtitleTracks!![i]
                isSelected = t.isSelected
                label = if (t.index < 0) t.label else t.label
            }
            
            if (isSelected) focusIndex = i

            val item = TextView(this).apply {
                text = if (isSelected) "✅ $label" else "   $label"
                setTextColor(if (isSelected) resources.getColor(R.color.accent) else android.graphics.Color.WHITE)
                textSize = 18f
                isFocusable = true
                isClickable = true
                setBackgroundResource(R.drawable.selector_channel_item)
                setPadding(
                    resources.getDimensionPixelSize(R.dimen.dp_16),
                    resources.getDimensionPixelSize(R.dimen.dp_12),
                    resources.getDimensionPixelSize(R.dimen.dp_16),
                    resources.getDimensionPixelSize(R.dimen.dp_12)
                )
                setOnClickListener {
                    val channel = allChannels.getOrNull(currentChannelIndex)
                    if (isAudio) {
                        val trackInfo = audioTracks!![i]
                        playerHelper?.selectAudioTrack(trackInfo.index)
                        if (channel != null) {
                            com.mediaplayer.app.data.ChannelMemoryManager.updateTracks(channel.id, trackInfo.id, null)
                        }
                    } else {
                        val trackInfo = subtitleTracks!![i]
                        val subIndex = trackInfo.index
                        if (subIndex < 0) {
                            playerHelper?.disableSubtitle()
                            if (channel != null) {
                                com.mediaplayer.app.data.ChannelMemoryManager.updateTracks(channel.id, null, "disable")
                            }
                        } else {
                            playerHelper?.selectSubtitleTrack(subIndex)
                            if (channel != null) {
                                com.mediaplayer.app.data.ChannelMemoryManager.updateTracks(channel.id, null, trackInfo.id)
                            }
                        }
                    }
                    hideTrackSelectionPanel()
                    // 延迟更新标签（等播放器回调）
                    android.os.Handler(mainLooper).postDelayed({
                        cachedAudioTracks = playerHelper?.getAudioTracks()
                        cachedSubtitleTracks = playerHelper?.getSubtitleTracks()
                        updateTrackButtonVisibility()
                    }, 300)
                }
            }
            container?.addView(item)
        }
        return focusIndex
    }

    private fun showOsd() {
        checkAndRefreshEpgBg()
        // 刷新频道号码和名称（来自当前频道，不依赖播放器回调）
        val channel = allChannels.getOrNull(currentChannelIndex)
        if (channel != null) {
            osdOverlayView?.setChannelNum(String.format("%03d", channel.globalIndex + 1).toString())
            osdOverlayView?.setChannelName(channel.name.toString())
            
            val lines = channel.getLinesSafely()
            if (lines.isNotEmpty()) {
                val safeIndex = if (currentLineIndex < lines.size) currentLineIndex else 0
                osdOverlayView?.setLineInfo("${safeIndex + 1}/${lines.size}".toString())
            } else {
                osdOverlayView?.setLineInfo("".toString())
            }
        }
        
        // 如果正在播放但 tvOsdInfo 仍卡在"连接中"（播放器未上报分辨率/编码信息），
        // 至少显示解码模式和播放内核作为回退信息
        // 使用 playerHelper?.isPlaying() 而非 currentPlaybackState，以兼容 ExoPlayer
        // （ExoPlayerHelper 只在 onVideoSizeChanged 中调用 onPlaying，无视频尺寸时不回调）
        if (playerHelper?.isPlaying() == true) {
            val infoText = osdOverlayView?.getInfoText() ?: "" ?: ""
            if (infoText.contains("连接中") || infoText.isEmpty()) {
                val decoderStr = when (currentDecoderMode) {
                    Prefs.DECODER_MODE_HARDWARE -> "HW"
                    Prefs.DECODER_MODE_SOFTWARE -> "SW"
                    else -> "Auto"
                }
                val coreStr = playerHelper?.let {
                    when (it) {
                        is com.mediaplayer.app.util.ExoPlayerHelper -> "ExoPlayer"
                        is com.mediaplayer.app.util.IjkPlayerHelper -> "IJKPlayer"
                        is com.mediaplayer.app.util.VlcPlayerHelper -> "VLC"
                        else -> ""
                    }
                } ?: ""
                osdOverlayView?.setInfoText(if (coreStr.isNotEmpty()) "$decoderStr | $coreStr" else decoderStr.toString())
            }
        }

        osdOverlayView?.showOsd()
        findViewById<com.mediaplayer.app.ui.widget.TimeOverlayView>(R.id.timeOverlayView)?.forceShowByOsd = true
        com.mediaplayer.app.util.RemoteLogger.i("PanelTrace", "OSD VISIBLE")
        osdOverlayView?.removeCallbacks()
        osdOverlayView?.showOsd()
        
        // VOD 模式：默认焦点落在播放进度图标，按 OK 暂停/恢复，按 DOWN 移到音轨按钮
        if (isCurrentChannelVod()) {
            findViewById<View>(R.id.tvVodIcon)?.post {
                findViewById<View>(R.id.tvVodIcon)?.requestFocus()
            }
        }
        
        // 换台时同步触发跑马灯
        if (!sysAnnouncement.isNullOrEmpty() && !marqueeIsVisible) {
            triggerMarquee()
        }
    }
    

    private fun loadEpgForChannel(channel: Channel) {
        if (channel.currentEpg.isNotEmpty()) {
            osdOverlayView?.setEpgText("正在播放: ${channel.currentEpg}".toString())
            osdOverlayView?.setEpgProgress(channel.getDynamicEpgPercent())
        } else {
            osdOverlayView?.setEpgText("暂无当前节目信息".toString())
            osdOverlayView?.setEpgProgress(0)
        }

        if (channel.nextEpg.isNotEmpty()) {
            osdOverlayView?.setNextEpgText("接下来: ${channel.nextEpg}".toString())
            } else {
            osdOverlayView?.setNextEpgText("".toString())
            }
    }

    private fun checkAndRefreshEpgBg(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force) {
            if (lastEpgBgRefreshTime == 0L) {
                lastEpgBgRefreshTime = now
                return
            }
            if (now - lastEpgBgRefreshTime < 600_000L) { // 10 minutes
                return
            }
        }
        lastEpgBgRefreshTime = now
        val gId = currentGroupId ?: return
            
            lifecycleScope.launch(Dispatchers.IO) {
                var page = 1
                val latestChannels = mutableListOf<Channel>()
                while (true) {
                    val resp = try {
                        ApiClient.getService().getChannels(groupId = gId, page = page, pageSize = 200)
                    } catch (e: Exception) {
                        break
                    }
                    if (!resp.isSuccessful || resp.body()?.code != 0) break
                    val pageData = resp.body()!!.data ?: break
                    val items = pageData.items ?: emptyList()
                    if (items.isEmpty()) break
                    latestChannels.addAll(items)
                    if (items.size < 200 || latestChannels.size >= pageData.total) break
                    page++
                }
                
                withContext(Dispatchers.Main) {
                    var updated = false
                    for (latest in latestChannels) {
                        // 使用 HashMap O(1) 查找替代 List O(n) 查找
                        val existing = channelIndexById[latest.id]
                        if (existing != null) {
                            if (existing.currentEpg != latest.currentEpg || existing.epgPercent != latest.epgPercent) {
                                existing.currentEpg = latest.currentEpg
                                existing.nextEpg = latest.nextEpg
                                existing.epgPercent = latest.epgPercent
                                updated = true
                            }
                        }
                    }
                    if (updated) {
                        channelAdapter.notifyItemRangeChanged(0, filteredChannels.size, "epg_update")
                        allChannels.getOrNull(currentChannelIndex)?.let { loadEpgForChannel(it) }
                    }
                }
            }
        }

    // ═══════════════════════════════════════════════════
    // SHARED LOGIC
    // ═══════════════════════════════════════════════════

    private fun setupAdapters() {
        groupAdapter = GroupAdapter(
            onClick = { group ->
                currentGroupId = group.id
                filterChannels(scrollToTop = true)
                groupAdapter.setSelected(group.id)
                tvChannelsRv?.requestFocus()
            },
            onFocus = { group ->
                android.util.Log.d("TV_FOCUS", "MainActivity received onFocus for: ${group.name}, isTvMode: $isTvMode")
                if (isTvMode) {
                    // 立即更新左侧分组的选中UI状态，不要有延迟
                    currentGroupId = group.id
                    groupAdapter.setSelected(group.id)
                    
                    focusDebounceRunnable?.let { focusDebounceHandler.removeCallbacks(it) }
                    val r = Runnable {
                        android.util.Log.d("TV_FOCUS", "Executing runnable for: ${group.name}")
                        filterChannels(scrollToTop = true)
                    }
                    focusDebounceRunnable = r
                    focusDebounceHandler.postDelayed(r, 150)
                }
            }
        )

        channelAdapter = ChannelAdapter(
            isTvMode = isTvMode,
            onClick = { channel, _ ->
                    val realIndex = allChannels.indexOf(channel)
                    playTvChannel(realIndex)
                    uiHandler.postDelayed(hideZappingRunnable, 500)
            }
        )
        
        val prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        channelAdapter.showLogo = prefs.getBoolean(Prefs.KEY_SHOW_CHANNEL_LOGO, true)

        if (isTvMode) {
            tvGroupsRv?.apply {
                layoutManager = object : LinearLayoutManager(this@MainActivity) {
                    override fun onFocusSearchFailed(focused: View, focusDirection: Int, recycler: androidx.recyclerview.widget.RecyclerView.Recycler, state: androidx.recyclerview.widget.RecyclerView.State): View? {
                        val next = super.onFocusSearchFailed(focused, focusDirection, recycler, state)
                        if (next == null && (focusDirection == View.FOCUS_DOWN || focusDirection == View.FOCUS_UP)) {
                            return focused // 捕获焦点
                        }
                        return next
                    }
                    override fun requestChildRectangleOnScreen(parent: androidx.recyclerview.widget.RecyclerView, child: View, rect: android.graphics.Rect, immediate: Boolean, focusedChildVisible: Boolean): Boolean {
                        rect.top -= child.height * 2
                        rect.bottom += child.height * 2
                        return super.requestChildRectangleOnScreen(parent, child, rect, immediate, focusedChildVisible)
                    }
                }
                adapter = groupAdapter
            }
            tvChannelsRv?.apply {
                setHasFixedSize(true)
                layoutManager = object : LinearLayoutManager(this@MainActivity) {
                    override fun onFocusSearchFailed(focused: View, focusDirection: Int, recycler: androidx.recyclerview.widget.RecyclerView.Recycler, state: androidx.recyclerview.widget.RecyclerView.State): View? {
                        val next = super.onFocusSearchFailed(focused, focusDirection, recycler, state)
                        if (next == null && (focusDirection == View.FOCUS_DOWN || focusDirection == View.FOCUS_UP)) {
                            return focused // 捕获焦点，防止快速滚动时意外逃逸到侧边栏
                        }
                        return next
                    }
                    override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
                        if (direction == View.FOCUS_LEFT) {
                            val groupIndex = groupAdapter.currentList.indexOfFirst { it.id == currentGroupId }
                            if (groupIndex >= 0) {
                                val groupLm = tvGroupsRv?.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                                val targetView = groupLm?.findViewByPosition(groupIndex)
                                return targetView ?: tvGroupsRv
                            }
                        }
                        return super.onInterceptFocusSearch(focused, direction)
                    }
                    override fun requestChildRectangleOnScreen(parent: androidx.recyclerview.widget.RecyclerView, child: View, rect: android.graphics.Rect, immediate: Boolean, focusedChildVisible: Boolean): Boolean {
                        rect.top -= child.height * 2
                        rect.bottom += child.height * 2
                        return super.requestChildRectangleOnScreen(parent, child, rect, immediate, focusedChildVisible)
                    }
                }
                adapter = channelAdapter
            }
        }
    }

    private fun resolveUrl(input: String): String {
        try {
            val bytes = android.util.Base64.decode(input, android.util.Base64.DEFAULT)
            val decoded = String(bytes, Charsets.UTF_8).trim()
            if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                return decoded
            }
        } catch (e: Exception) {
            // 解码失败（说明本来就是明文或格式不对），忽略异常
        }
        return input.trim()
    }

    private fun handleAuthSuccess(sysAnnouncement: String?, sysAnnouncementInterval: Int, startupMediaEnabled: Boolean, startupMediaUrl: String?, startupMediaType: String, startupDuration: Int, startupSkipAfter: Int, globalMaintenance: Boolean, backupServers: List<String>?, isTester: Boolean) {
        if (!backupServers.isNullOrEmpty()) {
            val localList = getServerList().toMutableList()
            val currentServer = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
                .getString(Prefs.KEY_SERVER_URL, Prefs.DEFAULT_SERVER_URL) ?: Prefs.DEFAULT_SERVER_URL

            // 优先级 1. 当前正在使用的主服务器 (currentServer)
            // 优先级 2. 用户原来手动输入的本地列表 (localList)
            // 优先级 3. 服务器最新下发的备用列表，追加在最后 (backupServers)
            val mergedList = mutableListOf<String>()
            mergedList.add(currentServer)
            mergedList.addAll(localList)
            
            // 对服务器下发的每一个节点进行智能解码转换 (支持 Base64 或明文)
            val resolvedBackupServers = backupServers.map { resolveUrl(it) }
            mergedList.addAll(resolvedBackupServers)
            
            // 去重操作 (distinct 会保留第一次出现的元素，抛弃后面的重复项)
            // 这完美保证了“用户原来的优先，服务器追加在后”的规则
            val finalList = mergedList.filter { it.isNotBlank() }.distinct()
            saveServerList(finalList)
        }

        if (globalMaintenance && !isTester) {
            showAuthWaiting("系统维护中，正在寻找可用服务器...", showQr = true)
            // 如果已在播放，停止播放并清除UI
            playerHelper?.release()
            playerHelper = null
            authPollRunnable?.let { authPollHandler.removeCallbacks(it) }
            val retryRunnable = Runnable { authFlowManager.startAuthFlow() }
            authPollRunnable = retryRunnable
            authPollHandler.postDelayed(retryRunnable, 15000)
            return
        }

        this.sysAnnouncement = sysAnnouncement
        this.sysAnnouncementInterval = sysAnnouncementInterval
        
        if (startupMediaEnabled && !startupMediaUrl.isNullOrEmpty() && !hasShownSplash) {
            hasShownSplash = true
            val intent = android.content.Intent(this, com.mediaplayer.app.ui.splash.SplashMediaActivity::class.java).apply {
                putExtra(com.mediaplayer.app.ui.splash.SplashMediaActivity.EXTRA_MEDIA_URL, startupMediaUrl)
                putExtra(com.mediaplayer.app.ui.splash.SplashMediaActivity.EXTRA_MEDIA_TYPE, startupMediaType)
                putExtra(com.mediaplayer.app.ui.splash.SplashMediaActivity.EXTRA_DURATION, startupDuration)
                putExtra(com.mediaplayer.app.ui.splash.SplashMediaActivity.EXTRA_SKIP_AFTER, startupSkipAfter)
            }
            startActivityForResult(intent, 1001)
        } else {
            showContent()
        }
    }

    private fun startAuthPolling() {
        authPollRunnable?.let { authPollHandler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                lifecycleScope.launch {
                    authManager.checkStatus().onSuccess { status ->
                        if (status == "approved") { 
                            authManager.verify().onSuccess { resp ->
                                if (resp != null) {
                                    handleAuthSuccess(resp.announcement, resp.announcementInterval, resp.startupMediaEnabled, resp.startupMedia, resp.startupMediaType, resp.startupDuration, resp.startupSkipAfter, resp.globalMaintenance, resp.backupServers, resp.isTester)
                                } else {
                                    // 即使获取配置失败，既然通过了 checkStatus 也要保存当前成功的备用节点
                                    getSharedPreferences(Prefs.FILE, MODE_PRIVATE).edit()
                                        .putString(Prefs.KEY_SERVER_URL, com.mediaplayer.app.data.api.ApiClient.getServerUrl())
                                        .apply()
                                    showContent()
                                }
                            }.onFailure { 
                                // 如果验证失败（如网络抖动），继续轮询不要停
                                authPollRunnable?.let { authPollHandler.postDelayed(it, 10000) }
                            }
                            return@launch 
                        }
                        authPollRunnable?.let { authPollHandler.postDelayed(it, 10000) }
                    }.onFailure { authPollRunnable?.let { authPollHandler.postDelayed(it, 15000) } }
                }
            }
        }
        authPollRunnable = runnable
        authPollHandler.postDelayed(runnable, 10000)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            // 开机广告结束或跳过，继续加载内容
            showContent()
        }
    }

    private fun showAuthWaiting(message: String, showQr: Boolean? = null) {
        // 更新状态文字（始终执行）
        tvAuthWaiting?.visibility = View.VISIBLE
        findViewById<TextView>(R.id.tvAuthStatus)?.text = message
        
        val actualShowQr = showQr ?: (layoutAuthQrConfig?.visibility == View.VISIBLE)
        
        // 面板已可见时，跳过二维码生成，避免闪烁
        if (actualShowQr && layoutAuthQrConfig?.visibility == View.VISIBLE) {
            return
        }
        
        if (actualShowQr) {
            val ip = com.mediaplayer.app.util.NetworkUtils.getLocalIpAddress()
            if (ip != null) {
                setupQrConfigServer {
                    Toast.makeText(this@MainActivity, "配置已保存，正在重试...", Toast.LENGTH_LONG).show()
                    authFlowManager.startAuthFlow()
                }
                
                val qrPort = configWebServer?.actualPort ?: 9528
                val qrUrl = "http://$ip:$qrPort/"
                val bitmap = com.mediaplayer.app.util.QRCodeHelper.generateQRCode(qrUrl, 400)
                ivAuthQrCode?.setImageBitmap(bitmap)
                tvAuthQrConfigHint?.text = "手机扫码设置服务器\n或访问: $qrUrl"
                tvAuthQrConfigHint?.setOnClickListener {
                    try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(qrUrl))
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                layoutAuthQrConfig?.visibility = View.VISIBLE
            }
        } else {
            layoutAuthQrConfig?.visibility = View.GONE
        }
    }

    private fun showContent() {
        tvAuthWaiting?.visibility = View.GONE

        // 初始触发跑马灯
        if (!sysAnnouncement.isNullOrEmpty()) {
            triggerMarquee()
        }

        loadData()
        startHeartbeat()
        startEpgTicker()
        startGlobalProgressTicker()
    }

    private fun triggerMarquee() {
        uiHandler.removeCallbacks(marqueeRunnable)
        uiHandler.removeCallbacks(hideMarqueeRunnable)
        
        val layoutAnnouncement = findViewById<android.view.View>(R.id.layoutAnnouncement) ?: return
        val tvAnnouncement = findViewById<android.widget.TextView>(R.id.tvAnnouncement) ?: return
        
        if (sysAnnouncement.isNullOrEmpty()) {
            layoutAnnouncement.visibility = View.GONE
            marqueeIsVisible = false
            return
        }

        layoutAnnouncement.visibility = View.VISIBLE
        marqueeIsVisible = true
        
        // 强制原生跑马灯滚动：如果文字太短，补齐空格直到超过屏幕宽度
        var text = sysAnnouncement!!
        tvAnnouncement.text = text
        
        layoutAnnouncement.post {
            tvAnnouncement.measure(0, 0)
            val screenWidth = layoutAnnouncement.width
            val textWidth = tvAnnouncement.measuredWidth
            
            if (textWidth < screenWidth && textWidth > 0) {
                val spaceWidth = tvAnnouncement.paint.measureText(" ")
                if (spaceWidth > 0) {
                    val spacesNeeded = ((screenWidth - textWidth) / spaceWidth).toInt() + 10
                    text = text + " ".repeat(spacesNeeded) + text
                    tvAnnouncement.text = text
                }
            }
            tvAnnouncement.isSelected = true
            
            // 计算原生跑马灯需要多长时间才能跑完一遍
            // Android 底层源码设定的跑马灯速度固定为 30dp/秒
            val density = resources.displayMetrics.density
            val speedPxPerSec = 30f * density
            // 跑完一整圈需要走过的总距离 = 屏幕宽度 + 文字总长度
            val requiredTimeMs = ((screenWidth + textWidth) / speedPxPerSec * 1000f).toLong()
            
            // 取 25 秒和实际需要时间中的最大值，确保哪怕是超长文本也能至少被完整看完一遍
            val displayDuration = maxOf(25000L, requiredTimeMs)
            
            // 原生跑马灯运行完毕后自动隐藏，并进入下一轮间隔排期
            uiHandler.postDelayed(hideMarqueeRunnable, displayDuration)
        }
    }

    private fun startGlobalProgressTicker() {
        val prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        val prefValue = prefs.getInt(Prefs.KEY_GLOBAL_PROGRESS_BAR, Prefs.GLOBAL_PROGRESS_OFF)
        
        if (prefValue == Prefs.GLOBAL_PROGRESS_OFF) {
            stopGlobalProgressTicker()
            globalProgressBar?.visibility = View.GONE
            return
        }

        globalProgressBar?.let { pb ->
            val lp = pb.layoutParams as? android.widget.FrameLayout.LayoutParams
            if (lp != null) {
                lp.gravity = if (prefValue == Prefs.GLOBAL_PROGRESS_BOTTOM) android.view.Gravity.BOTTOM else android.view.Gravity.TOP
                pb.layoutParams = lp
            }
        }

        globalProgressRunnable?.let { globalProgressHandler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                if (osdOverlayView?.isOsdVisible() == true) {
                    globalProgressBar?.visibility = View.GONE
                    globalProgressHandler.postDelayed(this, 1000)
                    return
                }

                if (isCurrentChannelVod()) {
                    val duration = playerHelper?.getDuration() ?: 0
                    val time = playerHelper?.getTime() ?: 0
                    if (duration > 0) {
                        globalProgressBar?.progress = ((time * 1000) / duration).toInt()
                        globalProgressBar?.visibility = View.VISIBLE
                    } else {
                        globalProgressBar?.visibility = View.GONE
                    }
                } else {
                    val channel = allChannels.getOrNull(currentChannelIndex)
                    if (channel != null && channel.currentEpg.isNotEmpty()) {
                        globalProgressBar?.progress = (channel.getDynamicEpgPercent() * 10).toInt()
                        globalProgressBar?.visibility = View.VISIBLE
                    } else {
                        globalProgressBar?.visibility = View.GONE
                    }
                }
                
                globalProgressHandler.postDelayed(this, 1000)
            }
        }
        globalProgressRunnable = runnable
        globalProgressHandler.postDelayed(runnable, 1000)
    }

    private fun stopGlobalProgressTicker() {
        globalProgressRunnable?.let { globalProgressHandler.removeCallbacks(it) }
        globalProgressRunnable = null
    }

    private fun startEpgTicker() {
        epgTickerRunnable?.let { epgTickerHandler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                // 智能检测：如果当前节目进度已达 100%，突破 10 分钟防刷锁，静默拉取下一集 EPG
                val channelForEpgCheck = allChannels.getOrNull(currentChannelIndex)
                if (channelForEpgCheck != null) {
                    if (channelForEpgCheck.currentEpg.isNotEmpty() && channelForEpgCheck.getDynamicEpgPercent() >= 100) {
                        checkAndRefreshEpgBg(force = true)
                    }
                }

                // Update OSD if visible
                if (osdOverlayView?.isOsdVisible() == true) {
                    if (channelForEpgCheck != null) {
                        loadEpgForChannel(channelForEpgCheck)
                    }
                }
                
                // Update left sidebar EPG list
                if (layoutEpgMenu?.visibility == View.VISIBLE) {
                    epgAdapter.notifyDataSetChanged()
                }
                
                // Update channel list EPG
                if (tvChannelsRv?.visibility == View.VISIBLE) {
                    val lm = tvChannelsRv?.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                    if (lm != null) {
                        val first = lm.findFirstVisibleItemPosition()
                        val last = lm.findLastVisibleItemPosition()
                        if (first != androidx.recyclerview.widget.RecyclerView.NO_POSITION && last != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                            channelAdapter.notifyItemRangeChanged(first, last - first + 1, "epg_update")
                        }
                    }
                }

                epgTickerHandler.postDelayed(this, 60000) // 1 min
            }
        }
        epgTickerRunnable = runnable
        epgTickerHandler.postDelayed(runnable, 60000)
    }

    private fun stopEpgTicker() {
        epgTickerRunnable?.let { epgTickerHandler.removeCallbacks(it) }
        epgTickerRunnable = null
    }

    private var heartbeatFailCount = 0

    private fun startHeartbeat() {
        heartbeatRunnable?.let { heartbeatHandler.removeCallbacks(it) }
        heartbeatFailCount = 0
        val runnable = object : Runnable {
            override fun run() {
                lifecycleScope.launch {
                    try {
                        authManager.verify()
                            .onSuccess { resp ->
                                heartbeatFailCount = 0 // 连接成功，重置失败计数
                                if (resp != null && resp.globalMaintenance && !resp.isTester) {
                                    // 进入维护模式，切断播放并转入全量探测主备服务器
                                    showAuthWaiting("系统维护中，正在寻找可用服务器...", showQr = true)
                                    playerHelper?.release()
                                    playerHelper = null
                                    heartbeatRunnable?.let { heartbeatHandler.removeCallbacks(it) }
                                    heartbeatRunnable = null
                                    authFlowManager.startAuthFlow()
                                }
                            }
                            .onFailure {
                                // 服务器网络故障/崩溃/超时（非维护模式，而是直接断联）
                                heartbeatFailCount++
                                if (heartbeatFailCount >= 2) {
                                    // 连续 2 次心跳失败（约 6 分钟），确认服务器不可达
                                    // 触发全量主备探测，自动切换到下一个可用服务器
                                    heartbeatFailCount = 0
                                    showAuthWaiting("当前服务器连接中断，正在寻找可用服务器...", showQr = true)
                                    playerHelper?.release()
                                    playerHelper = null
                                    heartbeatRunnable?.let { heartbeatHandler.removeCallbacks(it) }
                                    heartbeatRunnable = null
                                    authFlowManager.startAuthFlow()
                                }
                                // 第 1 次失败时不立即切换，等待下次心跳重试（避免网络抖动误触发）
                            }
                    } catch (_: Exception) {}
                }
                heartbeatHandler.postDelayed(this, 3 * 60 * 1000) // 每3分钟心跳
            }
        }
        heartbeatRunnable = runnable
        heartbeatHandler.postDelayed(runnable, 3 * 60 * 1000)
    }


    private fun loadData() {
        if (isLoadingData) return
        isLoadingData = true
        progressBuffering?.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                // 1. 先拉分组列表
                val realGroups = repo.getGroups().getOrElse { emptyList() }
                groups = listOf(ChannelGroup(id = 0, name = "全部")) + realGroups
                groupAdapter.submitList(groups)
                groupAdapter.setSelected(0)

                val groupOrderMap = realGroups.mapIndexed { index, group -> group.id to index }.toMap()

                // 获取上次观看的 group_id
                val prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
                val lastGroupId = prefs.getLong("last_group_id", -1L)

                // 为后台并发拉取构建优先队列（不影响 UI 左侧边栏排序）
                val fetchPriorityGroups = if (lastGroupId != -1L) {
                    val targetGroup = realGroups.find { it.id == lastGroupId }
                    if (targetGroup != null) {
                        listOf(targetGroup) + realGroups.filter { it.id != lastGroupId }
                    } else {
                        realGroups
                    }
                } else {
                    realGroups
                }

                // 2. 懒加载频道：首个分组（即上次观看的组）将优先被拉取并瞬间显示
                var isFirstEmission = true
                repo.loadChannelsLazy(fetchPriorityGroups).collect { newChannels ->
                    if (isFirstEmission) {
                        // 首页数据：立即显示，让用户看到内容
                        isFirstEmission = false
                        allChannels = newChannels.toMutableList()
                        channelsByGroup = newChannels.groupBy { it.groupId }
                        
                        // 构建 ID 索引
                        channelIndexById.clear()
                        newChannels.forEach { channelIndexById[it.id] = it }
                        
                        // 设置全局索引
                        newChannels.forEachIndexed { index, channel ->
                            channel.globalIndex = index
                        }
                        
                        if (newChannels.isNotEmpty()) {
                            // 尝试恢复上次播放的频道
                            val prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
                            val lastChannelId = prefs.getLong("last_channel_id", -1L)
                            var targetIndex = 0
                            if (lastChannelId != -1L) {
                                val foundIndex = newChannels.indexOfFirst { it.id == lastChannelId }
                                if (foundIndex != -1) {
                                    targetIndex = foundIndex
                                }
                            }
                            currentGroupId = newChannels[targetIndex].groupId
                            groupAdapter.setSelected(currentGroupId)
                            filterChannels(scrollToTop = false)
                            playTvChannel(targetIndex)
                            videoLayout?.requestFocus()
                        }
                        
                        progressBuffering?.visibility = View.GONE
                    } else {
                        // 增量数据：合并到现有列表
                        val uniqueNewChannels = newChannels.filter { channelIndexById[it.id] == null }
                        
                        if (uniqueNewChannels.isNotEmpty()) {
                            // 记录当前播放的频道 ID 以便排序后恢复索引
                            val currentPlayingId = if (currentChannelIndex in allChannels.indices) {
                                allChannels[currentChannelIndex].id
                            } else -1L

                            allChannels.addAll(uniqueNewChannels)
                            
                            // 按照套餐的真实顺序排序，解决并发拉取导致的乱序和频道号跳跃问题
                            allChannels.sortBy { groupOrderMap[it.groupId] ?: Int.MAX_VALUE }
                            
                            // 重新构建 ID 索引和全局频道号索引
                            channelIndexById.clear()
                            allChannels.forEachIndexed { index, channel ->
                                channelIndexById[channel.id] = channel
                                channel.globalIndex = index
                            }
                            
                            // 恢复当前播放频道的索引
                            if (currentPlayingId != -1L) {
                                currentChannelIndex = allChannels.indexOfFirst { it.id == currentPlayingId }
                            }
                            
                            // 更新分组映射
                            channelsByGroup = allChannels.groupBy { it.groupId }
                            
                            // 刷新当前显示的列表
                            filterChannels(scrollToTop = false)
                        }
                    }
                }
            } catch (e: Exception) {
                progressBuffering?.visibility = View.GONE
            } finally {
                isLoadingData = false
            }
        }
    }

    private fun showLoading(show: Boolean) {
        progressLoading?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showEmpty(show: Boolean, message: String = getString(R.string.no_channels)) {
        // layoutEmpty is not present in TV layout
    }

    private fun filterChannels(scrollToTop: Boolean = true) {
        filteredChannels = if (currentGroupId == 0L) {
            allChannels
        } else {
            channelsByGroup[currentGroupId] ?: emptyList()
        }
        channelAdapter.setData(filteredChannels)
        
        if (scrollToTop) {
            tvChannelsRv?.post {
                tvChannelsRv?.scrollToPosition(0) // 分组切换时，频道列表重置到顶部
            }
        }
    }


    private fun showEpgMenu() {
        if (currentChannelIndex < 0 || currentChannelIndex >= allChannels.size) return
        val channel = allChannels[currentChannelIndex]
        
        // 在显示 EPG 面板前主动更新焦点区域，防止 OnGlobalFocusChangeListener 拒绝合法焦点转移
        activeListArea = "epg"
        
        layoutZappingMenu?.visibility = View.GONE
        layoutSettingsMenu?.visibility = View.GONE
        layoutEpgMenu?.visibility = View.VISIBLE
        com.mediaplayer.app.util.RemoteLogger.i("PanelTrace", "EPG VISIBLE")
        
        tvEpgMenuTitle?.text = "节目单"
        
        val cached = com.mediaplayer.app.util.EpgCacheManager.get(channel.name)
        if (cached != null) {
            progressEpgLoading?.visibility = View.GONE
            if (cached.isEmpty()) {
                tvEpgEmptyText?.visibility = View.VISIBLE
                rvEpgList?.visibility = View.GONE
            } else {
                tvEpgEmptyText?.visibility = View.GONE
                rvEpgList?.visibility = View.VISIBLE
                epgAdapter.setSupportCatchup(channel.supportCatchup)
                if (currentChannelIndex == currentCatchupChannelIndex) {
                    epgAdapter.setActiveProgramStartTime(currentCatchupStartTime)
                } else {
                    epgAdapter.setActiveProgramStartTime(null)
                }
                epgAdapter.setData(cached)
                val pIndex = epgAdapter.getPlayingIndex()
                if (pIndex >= 0) {
                    rvEpgList?.scrollToPosition(pIndex)
                    rvEpgList?.post {
                        rvEpgList?.layoutManager?.findViewByPosition(pIndex)?.requestFocus()
                    }
                } else {
                    rvEpgList?.requestFocus()
                }
            }
            return
        }

        rvEpgList?.visibility = View.GONE
        tvEpgEmptyText?.visibility = View.GONE
        progressEpgLoading?.visibility = View.VISIBLE
        rvEpgList?.requestFocus()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Backend uses channel_id as string, pass channel.name for fuzzy match
                val epgId = channel.name
                val response = ApiClient.getService().getEPG(epgId)
                withContext(Dispatchers.Main) {
                    progressEpgLoading?.visibility = View.GONE
                    if (response.isSuccessful && response.body()?.code == 0) {
                        val programs = response.body()?.data ?: emptyList()
                        com.mediaplayer.app.util.EpgCacheManager.put(channel.name, programs)
                        if (programs.isEmpty()) {
                            tvEpgEmptyText?.visibility = View.VISIBLE
                        } else {
                            rvEpgList?.visibility = View.VISIBLE
                            epgAdapter.setSupportCatchup(channel.supportCatchup)
                            if (currentChannelIndex == currentCatchupChannelIndex) {
                                epgAdapter.setActiveProgramStartTime(currentCatchupStartTime)
                            } else {
                                epgAdapter.setActiveProgramStartTime(null)
                            }
                            epgAdapter.setData(programs)
                            
                            // Scroll to playing index
                            val pIndex = epgAdapter.getPlayingIndex()
                            if (pIndex >= 0) {
                                rvEpgList?.scrollToPosition(pIndex)
                                rvEpgList?.post {
                                    rvEpgList?.layoutManager?.findViewByPosition(pIndex)?.requestFocus()
                                }
                            }
                        }
                    } else {
                        tvEpgEmptyText?.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressEpgLoading?.visibility = View.GONE
                    tvEpgEmptyText?.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun hideEpgMenu() {
        layoutEpgMenu?.visibility = View.GONE
        activeListArea = "channels"
    }

    // ── Number Input State ──
    private var channelInputBuffer = java.lang.StringBuilder()
    private var isInputtingChannel = false
    private val channelInputRunnable = Runnable {
        isInputtingChannel = false
        val inputNum = channelInputBuffer.toString().toIntOrNull()
        channelInputBuffer.clear()
        
        if (inputNum != null && inputNum > 0 && allChannels.isNotEmpty()) {
            val targetIndex = allChannels.indexOfFirst { it.globalIndex + 1 == inputNum }
            if (targetIndex != -1) {
                playTvChannel(targetIndex)
            } else {
                Toast.makeText(this@MainActivity, "未找到频道: $inputNum", Toast.LENGTH_SHORT).show()
                // Restore OSD to current playing channel info
                if (currentChannelIndex >= 0 && currentChannelIndex < allChannels.size) {
                    val currentChannel = allChannels[currentChannelIndex]
                    osdOverlayView?.setChannelNum(String.format("%03d", currentChannel.globalIndex + 1).toString())
                    osdOverlayView?.setChannelName(currentChannel.name.toString())
                    val lines = currentChannel.getLinesSafely()
                    if (lines.isNotEmpty()) {
                        val safeIndex = if (currentLineIndex < lines.size) currentLineIndex else 0
                        osdOverlayView?.setLineInfo("${safeIndex + 1}/${lines.size}".toString())
                    } else {
                        osdOverlayView?.setLineInfo("".toString())
                    }
                }
            }
        } else {
             // Restore OSD
             if (currentChannelIndex >= 0 && currentChannelIndex < allChannels.size) {
                 val currentChannel = allChannels[currentChannelIndex]
                 osdOverlayView?.setChannelNum(String.format("%03d", currentChannel.globalIndex + 1).toString())
                 osdOverlayView?.setChannelName(currentChannel.name.toString())
                 val lines = currentChannel.getLinesSafely()
                 if (lines.isNotEmpty()) {
                     val safeIndex = if (currentLineIndex < lines.size) currentLineIndex else 0
                     osdOverlayView?.setLineInfo("${safeIndex + 1}/${lines.size}".toString())
                 } else {
                     osdOverlayView?.setLineInfo("".toString())
                 }
             }
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_DOWN || ev.action == android.view.MotionEvent.ACTION_MOVE) {
            if (layoutZappingMenu?.visibility == View.VISIBLE) {
                uiHandler.removeCallbacks(hideZappingRunnable)
                uiHandler.postDelayed(hideZappingRunnable, 15000)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun showZappingMenu(focusOnGroups: Boolean, resetToPlaying: Boolean = false) {
        checkAndRefreshEpgBg()
        if (layoutZappingMenu?.visibility == View.VISIBLE) return

        val playingChannel = if (currentChannelIndex >= 0 && currentChannelIndex < allChannels.size) allChannels[currentChannelIndex] else null
        
        var groupChanged = false
        if (resetToPlaying) {
            val newGroupId = playingChannel?.groupId ?: 0L
            if (currentGroupId != newGroupId) {
                currentGroupId = newGroupId
                groupChanged = true
            }
        }
        
        if (groupChanged) {
            groupAdapter.setSelected(currentGroupId)
            filterChannels(scrollToTop = false)
        } else {
            groupAdapter.setSelected(currentGroupId)
        }
        
        // 先清除 videoLayout 上的焦点，防止其子 View 拦截后续按键事件
        videoLayout?.clearFocus()
        
        // 在设置可见性和请求焦点前主动更新焦点区域，防止 OnGlobalFocusChangeListener 拒绝合法焦点转移
        activeListArea = if (focusOnGroups) "groups" else "channels"
        
        // 必须在设置为 VISIBLE 之前封锁左侧焦点，否则 setVisibility 内部会瞬间触发原生焦点分配并篡改当前选中状态
        tvGroupsRv?.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS

        layoutZappingMenu?.visibility = View.VISIBLE
        com.mediaplayer.app.util.RemoteLogger.i("PanelTrace", "ZappingMenu VISIBLE")
        uiHandler.removeCallbacks(hideZappingRunnable)
        uiHandler.postDelayed(hideZappingRunnable, 10000)

        if (focusOnGroups) {
            tvGroupsRv?.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            val groupIndex = groupAdapter.currentList.indexOfFirst { it.id == currentGroupId }
            if (groupIndex >= 0) {
                tvGroupsRv?.scrollToPosition(groupIndex)
                tvGroupsRv?.post {
                    val lm = tvGroupsRv?.layoutManager as? LinearLayoutManager
                    lm?.findViewByPosition(groupIndex)?.requestFocus() ?: tvGroupsRv?.requestFocus()
                }
            } else {
                tvGroupsRv?.requestFocus()
            }
        } else {
            val playingId = playingChannel?.id ?: -1L
            val indexInFiltered = filteredChannels.indexOfFirst { it.id == playingId }
            
            if (indexInFiltered >= 0) {
                tvChannelsRv?.scrollToPosition(indexInFiltered)
            }
            
            // 使用 postDelayed 确保布局完成后再请求焦点
            val requestFocusAction = {
                tvGroupsRv?.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
                if (indexInFiltered >= 0) {
                    val lm = tvChannelsRv?.layoutManager as? LinearLayoutManager
                    val view = lm?.findViewByPosition(indexInFiltered)
                    if (view != null) {
                        view.requestFocus()
                    } else {
                        // 如果视图还未布局完成，通过滚动触发再试一次
                        tvChannelsRv?.post {
                            val lm2 = tvChannelsRv?.layoutManager as? LinearLayoutManager
                            lm2?.findViewByPosition(indexInFiltered)?.requestFocus() ?: tvChannelsRv?.requestFocus()
                        }
                    }
                } else {
                    val lm = tvChannelsRv?.layoutManager as? LinearLayoutManager
                    val firstVisible = lm?.findFirstVisibleItemPosition() ?: 0
                    lm?.findViewByPosition(firstVisible)?.requestFocus() ?: tvChannelsRv?.requestFocus()
                }
            }
            
            tvChannelsRv?.postDelayed({ requestFocusAction() }, 100)
        }
    }

    private fun isViewDescendantOf(view: View, parent: View?): Boolean {
        if (parent == null) return false
        var p = view.parent
        while (p != null) {
            if (p === parent) return true
            p = p.parent
        }
        return false
    }

    // 按键防抖
    private var lastDispatchedKeyCode: Int = -1
    private var lastDispatchedKeyTime: Long = 0L

    private var isBouncingFocus = false

    private fun bounceFocusBack() {
        if (isBouncingFocus) return
        isBouncingFocus = true
        
        when (activeListArea) {
            "channels" -> {
                val rv = tvChannelsRv
                val lm = rv?.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                val pos = lm?.findFirstCompletelyVisibleItemPosition()?.takeIf { it != -1 } ?: lm?.findFirstVisibleItemPosition() ?: 0
                if (pos != -1) {
                    val view = lm?.findViewByPosition(pos)
                    if (view != null) view.requestFocus() else rv?.requestFocus()
                }
            }
            "groups" -> {
                val rv = tvGroupsRv
                val lm = rv?.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                val pos = lm?.findFirstCompletelyVisibleItemPosition()?.takeIf { it != -1 } ?: lm?.findFirstVisibleItemPosition() ?: 0
                if (pos != -1) {
                    val view = lm?.findViewByPosition(pos)
                    if (view != null) view.requestFocus() else rv?.requestFocus()
                }
            }
            "epg" -> {
                val rv = rvEpgList
                val lm = rv?.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                val pos = lm?.findFirstCompletelyVisibleItemPosition()?.takeIf { it != -1 } ?: lm?.findFirstVisibleItemPosition() ?: 0
                if (pos != -1) {
                    val view = lm?.findViewByPosition(pos)
                    if (view != null) view.requestFocus() else rv?.requestFocus()
                }
            }
        }
        
        tvChannelsRv?.postDelayed({ isBouncingFocus = false }, 100)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val keyCode = event.keyCode
            
            // 按键防抖：同一按键 60ms 内重复触发则忽略
            // 注意：TV 遥控器的自动重复间隔通常为 100-110ms，阈值必须高于此值
            val now = android.os.SystemClock.uptimeMillis()
            if (keyCode == lastDispatchedKeyCode && now - lastDispatchedKeyTime < 60) {
                return true
            }
            lastDispatchedKeyCode = keyCode
            lastDispatchedKeyTime = now

            // 快速通行：非本应用关注的按键（如音量、电源、未知遥控键等）
            // 直接交给系统，不执行任何日志或 Handler 操作，防止主线程被淹没
            val isKnownKey = keyCode in setOf(
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BACK
            ) || keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 || keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9
            if (!isKnownKey) {
                return super.dispatchKeyEvent(event)
            }

            com.mediaplayer.app.util.RemoteLogger.i("KeyEvent", "User pressed key $keyCode")

            // VOD 模式：专业快进/快退（seekTo 跳跃式，线性加速）
            // 短按：±60s（1分钟）步进 seek
            // 长按左键/右键：seekTo 跳跃式快退/快进，速度线性递增（60s/s → 300s/s）
            // Gap A: 焦点在 OSD 音轨/字幕按钮上时，LEFT/RIGHT 不拦截，交给按钮焦点导航
            val focusOnTrackButton = currentFocus?.let { view ->
                view.id == R.id.tvBtnAudio || view.id == R.id.tvBtnSubtitle
            } ?: false
            val isVodSeek = isCurrentChannelVod()
                && layoutZappingMenu?.visibility != View.VISIBLE
                && layoutLineMenu?.visibility != View.VISIBLE
                && !isTrackPanelOpen
                && !focusOnTrackButton
            if (isVodSeek && (osdOverlayView?.isOsdVisible() == true || vodSeekActive)) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            if (event.repeatCount == 0) {
                                // 短按：-60s（1分钟）步进
                                stopVodSeek()
                                val newPos = (playerHelper?.getTime() ?: 0) - vodSeekStepMs
                                playerHelper?.setTime(newPos.coerceAtLeast(0))
                                showOsd()
                            } else {
                                // 长按：seekTo 快退
                                if (!vodSeekActive || vodSeekDirection != -1) {
                                    stopVodSeek()
                                    vodSeekActive = true
                                    vodSeekDirection = -1
                                    osdOverlayView?.isVodSeeking = true
                                    vodSeekTarget = playerHelper?.getTime() ?: 0
                                    vodSeekLastTick = SystemClock.uptimeMillis()
                                    vodSeekStartTime = vodSeekLastTick
                                    vodSeekHandler.post(vodSeekRunnable)
                                }
                                // 保持 OSD 可见
                                osdOverlayView?.removeCallbacks()
                                osdOverlayView?.showOsd()
                            }
                        } else if (event.action == KeyEvent.ACTION_UP) {
                            stopVodSeek()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            if (event.repeatCount == 0) {
                                // 短按：+60s（1分钟）步进
                                stopVodSeek()
                                val current = playerHelper?.getTime() ?: 0
                                val duration = playerHelper?.getDuration() ?: 0
                                val newPos = current + vodSeekStepMs
                                playerHelper?.setTime(if (duration > 0) newPos.coerceAtMost(duration) else newPos)
                                showOsd()
                            } else {
                                // 长按：seekTo 快进
                                if (!vodSeekActive || vodSeekDirection != 1) {
                                    stopVodSeek()
                                    vodSeekActive = true
                                    vodSeekDirection = 1
                                    osdOverlayView?.isVodSeeking = true
                                    vodSeekTarget = playerHelper?.getTime() ?: 0
                                    vodSeekLastTick = SystemClock.uptimeMillis()
                                    vodSeekStartTime = vodSeekLastTick
                                    vodSeekHandler.post(vodSeekRunnable)
                                }
                                // 保持 OSD 可见
                                osdOverlayView?.removeCallbacks()
                                osdOverlayView?.showOsd()
                            }
                        } else if (event.action == KeyEvent.ACTION_UP) {
                            stopVodSeek()
                        }
                        return true
                    }
                }
            }

            // 只要面板处于显示状态，用户的任何按键都应当重置自动隐藏的时间
            if (layoutZappingMenu?.visibility == View.VISIBLE) {
                uiHandler.removeCallbacks(hideZappingRunnable)
                uiHandler.postDelayed(hideZappingRunnable, 15000)
            }
            if (osdOverlayView?.isOsdVisible() == true) {
                osdOverlayView?.removeCallbacks()
                osdOverlayView?.showOsd()

                // ── VOD 模式 OSD 焦点导航 ──
                if (isCurrentChannelVod() && !isTrackPanelOpen) {
                    val isFocusOnAudio = currentFocus?.id == R.id.tvBtnAudio
                    val isFocusOnSubtitle = currentFocus?.id == R.id.tvBtnSubtitle
                    val focusOnTrackBtn = isFocusOnAudio || isFocusOnSubtitle

                    // DPAD_UP: 向上导航 (字幕 -> 音轨 -> 播放进度)
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            if (isFocusOnSubtitle) {
                                val btnAudio = findViewById<View>(R.id.tvBtnAudio)
                                if (btnAudio?.visibility == View.VISIBLE) {
                                    btnAudio.requestFocus()
                                } else {
                                    findViewById<View>(R.id.tvVodIcon)?.requestFocus()
                                }
                            } else if (isFocusOnAudio) {
                                findViewById<View>(R.id.tvVodIcon)?.requestFocus()
                            }
                        }
                        return true // 拦截 UP 防止切台
                    }

                    // DPAD_DOWN: 向下导航 (播放进度 -> 音轨 -> 字幕)
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            val btnAudio = findViewById<View>(R.id.tvBtnAudio)
                            val btnSubtitle = findViewById<View>(R.id.tvBtnSubtitle)
                            
                            if (!focusOnTrackBtn) {
                                if (btnAudio?.visibility == View.VISIBLE) {
                                    btnAudio.requestFocus()
                                } else if (btnSubtitle?.visibility == View.VISIBLE) {
                                    btnSubtitle.requestFocus()
                                }
                            } else if (isFocusOnAudio) {
                                if (btnSubtitle?.visibility == View.VISIBLE) {
                                    btnSubtitle.requestFocus()
                                }
                            }
                        }
                        return true // 拦截 DOWN 防止切台
                    }
                }
            }
            
            val focusedView = currentFocus
            if (focusedView != null) {
                // 跟踪用户的合法横向意图
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (isViewDescendantOf(focusedView, tvChannelsRv)) activeListArea = "groups"
                    else if (isViewDescendantOf(focusedView, rvEpgList)) activeListArea = "channels"
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (isViewDescendantOf(focusedView, tvGroupsRv)) activeListArea = "channels"
                    else if (isViewDescendantOf(focusedView, tvChannelsRv)) activeListArea = "epg"
                }
            }

            // 【终极 TV 焦点防御系统】
            // 拦截 Activity 级别的所有按键分发。防止极速滚动时脱离列表边界。
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (focusedView != null) {
                    val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) View.FOCUS_DOWN else View.FOCUS_UP
                    val dirStr = if (direction == View.FOCUS_DOWN) "DOWN" else "UP"
                    
                    fun handleListFocus(rv: androidx.recyclerview.widget.RecyclerView, listName: String): Boolean {
                        val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                        val adapter = rv.adapter
                        val focusedPos = rv.getChildAdapterPosition(focusedView)
                        val nextFocus = android.view.FocusFinder.getInstance().findNextFocus(rv as android.view.ViewGroup, focusedView, direction)
                        
                        com.mediaplayer.app.util.RemoteLogger.i("FocusTrace", "$listName $dirStr | currPos:$focusedPos, nextFocusPos:${nextFocus?.let { rv.getChildAdapterPosition(it) }}, lastVisible:${lm?.findLastVisibleItemPosition()}")

                        if (nextFocus != null) {
                            nextFocus.requestFocus()
                            return true
                        } else {
                            if (lm != null && adapter != null && focusedPos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                                if (direction == View.FOCUS_DOWN) {
                                    val nextPos = focusedPos + 1
                                    if (nextPos < adapter.itemCount) {
                                        rv.scrollToPosition(nextPos)
                                        rv.post { lm.findViewByPosition(nextPos)?.requestFocus() }
                                        com.mediaplayer.app.util.RemoteLogger.i("FocusTrace", "$listName Blocked escape DOWN. Snap to $nextPos")
                                    } else {
                                        com.mediaplayer.app.util.RemoteLogger.i("FocusTrace", "$listName reached BOTTOM.")
                                    }
                                } else {
                                    val nextPos = focusedPos - 1
                                    if (nextPos >= 0) {
                                        rv.scrollToPosition(nextPos)
                                        rv.post { lm.findViewByPosition(nextPos)?.requestFocus() }
                                        com.mediaplayer.app.util.RemoteLogger.i("FocusTrace", "$listName Blocked escape UP. Snap to $nextPos")
                                    } else {
                                        com.mediaplayer.app.util.RemoteLogger.i("FocusTrace", "$listName reached TOP.")
                                    }
                                }
                            }
                            return true // 始终吞噬，绝对不让 Android 全局接管焦点！
                        }
                    }

                    if (isViewDescendantOf(focusedView, tvChannelsRv)) {
                        return handleListFocus(tvChannelsRv as androidx.recyclerview.widget.RecyclerView, "ChannelList")
                    } else if (isViewDescendantOf(focusedView, tvGroupsRv)) {
                        return handleListFocus(tvGroupsRv as androidx.recyclerview.widget.RecyclerView, "GroupList")
                    } else if (isViewDescendantOf(focusedView, rvEpgList)) {
                        return handleListFocus(rvEpgList as androidx.recyclerview.widget.RecyclerView, "EpgList")
                    } else {
                        com.mediaplayer.app.util.RemoteLogger.i("FocusTrace", "OtherArea $dirStr | focusedId:${focusedView.id}")
                    }
                }
            }
            
            // 遥控器数字键换台
            if (isTvMode && tvAuthWaiting?.visibility == View.GONE) {
                val keyCode = event.keyCode
                if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 || keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9) {
                    val digit = if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0) {
                        keyCode - KeyEvent.KEYCODE_NUMPAD_0
                    } else {
                        keyCode - KeyEvent.KEYCODE_0
                    }
                    
                    isInputtingChannel = true
                    if (channelInputBuffer.length < 5) {
                        channelInputBuffer.append(digit)
                    }
                    
                    showOsd()
                    osdOverlayView?.setChannelNum(channelInputBuffer.toString().toString())
                    osdOverlayView?.setChannelName("输入频道号...".toString())
                    osdOverlayView?.setLineInfo("".toString())
                    
                    uiHandler.removeCallbacks(channelInputRunnable)
                    uiHandler.postDelayed(channelInputRunnable, 1500)
                    return true
                }
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            val keyCode = event.keyCode
            if (isTvMode && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                val isMenuVisible = layoutZappingMenu?.visibility == View.VISIBLE
                val isSettingsVisible = layoutSettingsMenu?.visibility == View.VISIBLE
                val isEpgVisible = layoutEpgMenu?.visibility == View.VISIBLE
                val isLineVisible = layoutLineMenu?.visibility == View.VISIBLE
                val anyPanelOpen = isMenuVisible || isSettingsVisible || isEpgVisible || isLineVisible || isTrackPanelOpen

                if (!anyPanelOpen) {
                    // 【焦点修复】拦截焦点遗留在频道列表项上的 OK 事件，防止触发换台
                    // 改为显示 OSD（用户可通过 LEFT 键呼出频道列表）
                    val focusedView = currentFocus
                    if (focusedView != null && isViewDescendantOf(focusedView, tvChannelsRv)) {
                        // VOD 中 OSD 已显示时，不拦截 OK 事件，让 onKeyUp 处理暂停/恢复
                        if (isCurrentChannelVod() && osdOverlayView?.isOsdVisible() == true) {
                            // 不拦截，传递到 onKeyUp 处理 toggle
                        } else {
                            com.mediaplayer.app.util.RemoteLogger.i("KeyEvent", "OK on channel item - intercepted for OSD")
                            showOsd()
                            return true
                        }
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ── TV key events ──────────────────────────────────

    /** 检查当前焦点是否在 OSD 音轨/字幕按钮上 */
    private fun isFocusOnTrackButton(): Boolean {
        return currentFocus?.let { view ->
            view.id == R.id.tvBtnAudio || view.id == R.id.tvBtnSubtitle
        } ?: false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_INFO) {
            if (isTrackPanelOpen) hideTrackSelectionPanel() else showTrackPanel("audio")
            return true
        }

        // 焦点在 OSD 音轨/字幕按钮上时，不拦截任何按键，让系统处理焦点导航和点击事件
        if (isFocusOnTrackButton()) {
            return super.onKeyDown(keyCode, event)
        }

        // 任何时候按下菜单键，直接显示右侧设置
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val isSettingsVisible = layoutSettingsMenu?.visibility == View.VISIBLE
            if (isSettingsVisible) hideSettingsMenu() else showSettingsMenu()
            return true
        }

        if (isTvMode && tvAuthWaiting?.visibility == View.GONE) {
            val isMenuVisible = layoutZappingMenu?.visibility == View.VISIBLE
            val isSettingsVisible = layoutSettingsMenu?.visibility == View.VISIBLE
            val isEpgVisible = layoutEpgMenu?.visibility == View.VISIBLE
            val isLineVisible = layoutLineMenu?.visibility == View.VISIBLE

            val anyPanelOpen = isMenuVisible || isSettingsVisible || isEpgVisible || isLineVisible || isTrackPanelOpen

            // 当任何面板未显示时，开始追踪 OK 键的长按事件
            if (!anyPanelOpen && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                event?.startTracking()
                return true
            }

            val reverseChannels = getSharedPreferences(Prefs.FILE, MODE_PRIVATE).getBoolean(Prefs.KEY_REVERSE_CHANNEL_KEYS, false)
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (!anyPanelOpen) {
                        val targetIdx = if (reverseChannels) {
                            if (currentChannelIndex < allChannels.size - 1) currentChannelIndex + 1 else 0
                        } else {
                            if (currentChannelIndex > 0) currentChannelIndex - 1 else allChannels.size - 1
                        }
                        playTvChannel(targetIdx)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!anyPanelOpen) {
                        val targetIdx = if (reverseChannels) {
                            if (currentChannelIndex > 0) currentChannelIndex - 1 else allChannels.size - 1
                        } else {
                            if (currentChannelIndex < allChannels.size - 1) currentChannelIndex + 1 else 0
                        }
                        playTvChannel(targetIdx)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (isSettingsVisible) {
                        val focus = currentFocus
                        if (focus is android.widget.SeekBar || focus is android.widget.EditText) {
                            return false
                        }

                        hideSettingsMenu()
                        return true
                    }
                    if (isEpgVisible) {
                        hideEpgMenu()
                        return true
                    }
                    if (isLineVisible) {
                        hideLineSelectionMenu()
                        return true
                    }
                    if (isMenuVisible) {
                        // 在菜单显示状态下，如果频道列表有焦点，向左按键转移到对应的分组上
                        val groupsRv = tvGroupsRv
                        val channelsRv = tvChannelsRv
                        if (groupsRv != null && channelsRv != null && channelsRv.hasFocus()) {
                            val groupIndex = groupAdapter.currentList.indexOfFirst { it.id == currentGroupId }
                            if (groupIndex >= 0) {
                                val lm = groupsRv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                                lm?.findViewByPosition(groupIndex)?.requestFocus() ?: groupsRv.requestFocus()
                            } else {
                                groupsRv.requestFocus()
                            }
                            return true
                        }
                    } else {
                        showZappingMenu(focusOnGroups = false, resetToPlaying = true)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (isSettingsVisible) {
                        val focus = currentFocus
                        if (focus is android.widget.SeekBar || focus is android.widget.EditText) {
                            return false
                        }

                        return true
                    }
                    if (isEpgVisible) {
                        return true
                    }
                    if (isLineVisible) {
                        hideLineSelectionMenu()
                        return true
                    }
                    if (isMenuVisible) {
                        val groupsRv = tvGroupsRv
                        val channelsRv = tvChannelsRv
                        if (channelsRv != null && channelsRv.hasFocus()) {
                            // 如果已经在频道列表（最右侧），再按右键则关闭菜单
                            uiHandler.removeCallbacks(hideZappingRunnable)
                            hideZappingRunnable.run()
                            return true
                        }
                        if (groupsRv != null && channelsRv != null && groupsRv.hasFocus()) {
                            // 获取当前焦点所在的分组
                            val focusedView = groupsRv.findFocus()
                            if (focusedView != null) {
                                val focusedPos = groupsRv.getChildAdapterPosition(focusedView)
                                if (focusedPos >= 0) {
                                    val focusedGroup = groupAdapter.currentList.getOrNull(focusedPos)
                                    if (focusedGroup != null) {
                                        // 立即取消挂起的分组切台防抖任务，防止异步刷新导致焦点错乱
                                        focusDebounceRunnable?.let {
                                            focusDebounceHandler.removeCallbacks(it)
                                            focusDebounceRunnable = null
                                        }
                                        if (currentGroupId != focusedGroup.id) {
                                            currentGroupId = focusedGroup.id
                                            groupAdapter.setSelected(focusedGroup.id)
                                        }
                                        // 强制同步刷新频道列表，确保 filteredChannels 为当前选中分组的数据
                                        filterChannels(scrollToTop = false)

                                        val playingChannel = allChannels.getOrNull(currentChannelIndex)
                                        val playingGroupId = playingChannel?.groupId ?: 0L

                                        // 判断当前焦点分组是否是正在播放的频道所在分组（若为全部频道 0L 也视为匹配）
                                        val isPlayingGroup = (focusedGroup.id == playingGroupId || focusedGroup.id == 0L)

                                        val targetPos = if (isPlayingGroup && playingChannel != null) {
                                            // 回到当前分组选中的频道上
                                            val pos = filteredChannels.indexOfFirst { it.id == playingChannel.id }
                                            if (pos < 0) 0 else pos
                                        } else {
                                            // 焦点转移到当前分组频道的第一个频道列表项
                                            0
                                        }

                                        channelsRv.scrollToPosition(targetPos)
                                        channelsRv.post {
                                            val lm = channelsRv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                                            lm?.findViewByPosition(targetPos)?.requestFocus() ?: channelsRv.requestFocus()
                                        }
                                        return true
                                    }
                                }
                            }
                        }
                    } else {
                        // 如果菜单未显示，按右键呼出完整 EPG 节目单
                        showEpgMenu()
                        return true
                    }
                }
            }
            if (isMenuVisible && keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER) {
                uiHandler.removeCallbacks(hideZappingRunnable)
                uiHandler.postDelayed(hideZappingRunnable, 10000)
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (isFocusOnTrackButton()) {
            return super.onKeyLongPress(keyCode, event)
        }
        if (isTvMode && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            // 长按 OK 键呼出手动切源菜单
            showLineSelectionMenu()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // 焦点在 OSD 音轨/字幕按钮上时，不拦截 OK 事件，让按钮的 onClickListener 触发
        if (isFocusOnTrackButton()) {
            return super.onKeyUp(keyCode, event)
        }

        if (isTvMode && tvAuthWaiting?.visibility == View.GONE) {
            val isMenuVisible = layoutZappingMenu?.visibility == View.VISIBLE
            val isSettingsVisible = layoutSettingsMenu?.visibility == View.VISIBLE
            val isEpgVisible = layoutEpgMenu?.visibility == View.VISIBLE
            val isLineVisible = layoutLineMenu?.visibility == View.VISIBLE

            if (!isMenuVisible && !isSettingsVisible && !isEpgVisible && !isLineVisible && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                if (event?.isTracking == true && !event.isCanceled) {
                    if (isCurrentChannelVod()) {
                        // VOD：第一次 OK 显示 OSD，OSD 已显示时再按才暂停/恢复
                        if (osdOverlayView?.isOsdVisible() == true) {
                            toggleVodPauseResume()
                        }
                        showOsd()
                    } else {
                        showOsd()
                    }
                }
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onBackPressed() {
        handleBackPress()
    }

    private fun handleBackPress() {
        if (tvAuthWaiting?.visibility == View.VISIBLE) {
            finish()
            return
        }
        
        val layoutAboutDevice = findViewById<View>(R.id.layoutAboutDevice)
        if (layoutAboutDevice?.visibility == View.VISIBLE) {
            hideAboutDevice()
            return
        }
        
        if (layoutSettingsMenu?.visibility == View.VISIBLE) {
            hideSettingsMenu()
            return
        }
        if (layoutEpgMenu?.visibility == View.VISIBLE) {
            hideEpgMenu()
            return
        }
        if (layoutLineMenu?.visibility == View.VISIBLE) {
            hideLineSelectionMenu()
            return
        }
        if (layoutZappingMenu?.visibility == View.VISIBLE) {
            uiHandler.removeCallbacks(hideZappingRunnable)
            hideZappingRunnable.run()
            return
        }
        if (isTrackPanelOpen) {
            hideTrackSelectionPanel()
            return
        }
        if (osdOverlayView?.isOsdVisible() == true) {
            osdOverlayView?.removeCallbacks()
            osdOverlayView?.hideOsd()
            return
        }
        
        showExitDialog()
    }
    private var exitDialog: android.app.Dialog? = null

    private fun showExitDialog() {
        if (exitDialog?.isShowing == true) return
        
        exitDialog = android.app.Dialog(this)
        exitDialog?.setContentView(R.layout.dialog_exit)
        exitDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        val btnCancel = exitDialog?.findViewById<android.widget.Button>(R.id.btn_cancel)
        val btnExit = exitDialog?.findViewById<android.widget.Button>(R.id.btn_exit)
        
        btnCancel?.setOnClickListener {
            exitDialog?.dismiss()
        }
        
        btnExit?.setOnClickListener {
            exitDialog?.dismiss()
            finish()
        }
        
        exitDialog?.setOnDismissListener {
            exitDialog = null
        }
        
        exitDialog?.show()
        btnExit?.requestFocus()
    }

    companion object {
        @Volatile
        @JvmStatic
        var settingsChanged = false
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        
        // 交由专门的控制器去动态注入画中画参数
        pipController.updatePipParams(playerHelper?.isPlaying() == true)
        
        // 首次 onResume 由 onCreate 的认证链路负责数据加载，跳过以避免并发
        if (isFirstResume) {
            isFirstResume = false
            return
        }
        
        if (settingsChanged || allChannels.isEmpty()) {
            loadData()
            settingsChanged = false
        } else if (isTvMode && allChannels.isNotEmpty() && currentChannelIndex >= 0 && currentChannelIndex < allChannels.size) {
            // 直播流在切后台后会断开或缓冲失效，必须重新连接加载 (仅当不在画中画模式时)
            if (!(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && isInPictureInPictureMode)) {
                videoLayout?.post {
                    playTvChannel(currentChannelIndex)
                }
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(watchdogRunnable)
        if (pipController.shouldKeepPlayerAliveOnPause()) {
            // 画中画模式下被拦截，不停止播放
        } else if (isTvMode) {
            // 直播流切后台直接彻底停止，释放硬件解码器和网络连接
            playerHelper?.release()
            playerHelper = null
        }
    }
    
    override fun onStop() {
        super.onStop()
        if (isPipClosedBySystem || (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && isInPictureInPictureMode)) {
            playerHelper?.release()
            pipController.release()
            stopService(Intent(this, com.mediaplayer.app.service.PlaybackService::class.java))
            finishAffinity()
            System.exit(0)
        } else if (!isTvMode) {
            playerHelper?.pause()
        }
    }
    
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        pipController.handleUserLeaveHint(playerHelper?.isPlaying() == true)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        
        val viewsToHide = listOf(
            layoutZappingMenu,
            layoutEpgMenu,
            layoutSettingsMenu,
            layoutLineMenu,
            osdOverlayView
        )
        pipController.handlePictureInPictureModeChanged(isInPictureInPictureMode, viewsToHide)
        
        val timeOverlayView = findViewById<com.mediaplayer.app.ui.widget.TimeOverlayView>(R.id.timeOverlayView)
        timeOverlayView?.onPipModeChanged(isInPictureInPictureMode, newConfig)
        
        if (!isInPictureInPictureMode) {
            // 退出画中画恢复 OSD
            osdOverlayView?.visibility = View.VISIBLE
            
            if (lifecycle.currentState != androidx.lifecycle.Lifecycle.State.RESUMED) {
                isPipClosedBySystem = true
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        
        // Pad 或设备旋转时，系统触发横竖屏切换或屏幕尺寸变化
        // 由于我们在 manifest 中声明了 configChanges="orientation|screenSize"，Activity 不会重建
        // 在这里可以安全地调整 UI 或通知播放器重新计算尺寸，防止 Surface 尺寸异常导致闪退
        videoLayout?.requestLayout()
    }

    override fun onDestroy() {
        super.onDestroy()
        configWebServer?.stop()
        authFlowManager.cancelRetry()
        authPollRunnable?.let { authPollHandler.removeCallbacks(it) }
        heartbeatRunnable?.let { heartbeatHandler.removeCallbacks(it) }
        stopEpgTicker()
        stopGlobalProgressTicker()
        osdOverlayView?.removeCallbacks()
        uiHandler.removeCallbacks(hideZappingRunnable)
        uiHandler.removeCallbacks(channelInputRunnable)
        uiHandler.removeCallbacks(watchdogRunnable)
        pipController.release()
        playerHelper?.release()
        playerHelper = null
    }

    override fun onPipPlay() {
        playerHelper?.resume()
        pipController.updatePipParams(true)
    }

    override fun onPipPause() {
        playerHelper?.pause()
        pipController.updatePipParams(false)
    }

    override fun onPipNext() {
        if (allChannels.isNotEmpty()) {
            var nextIndex = currentChannelIndex + 1
            if (nextIndex >= allChannels.size) nextIndex = 0
            playTvChannel(nextIndex)
            pipController.updatePipParams(true)
        }
    }

    override fun onPipPrev() {
        if (allChannels.isNotEmpty()) {
            var prevIndex = currentChannelIndex - 1
            if (prevIndex < 0) prevIndex = allChannels.size - 1
            playTvChannel(prevIndex)
            pipController.updatePipParams(true)
        }
    }
}
