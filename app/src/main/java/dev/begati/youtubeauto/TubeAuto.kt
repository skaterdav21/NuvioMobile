package dev.begati.youtubeauto

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.support.car.input.CarRestrictedEditText
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.media.CarAudioRecord
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.apps.auto.sdk.CarActivity
import com.google.android.apps.auto.sdk.CarActivityService
import com.google.android.gms.car.input.CarEditable
import com.google.android.gms.car.input.CarEditableListener
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// =====================================================================================================================
// TubeAuto settings (on top of YT Auto's Choice / Flag model; the phone's Settings screen lists them automatically)
// =====================================================================================================================

/** Data saver: caps the video quality at 720p, always or only on mobile data. */
internal val DATA_SAVER = Choice(
    "dataSaver", R.string.data_saver_title,
    linkedMapOf("off" to R.string.data_saver_off, "mobile" to R.string.data_saver_mobile, "always" to R.string.data_saver_always),
    "mobile",
)

/** How long the floating button bar stays up in Full-screen app mode (ms; 0 = always shown). */
internal val BAR_TIMEOUT = Choice(
    "barTimeout", R.string.bar_timeout_title,
    linkedMapOf("3000" to R.string.bar_3s, "5000" to R.string.bar_5s, "10000" to R.string.bar_10s, "0" to R.string.bar_always),
    "5000",
)

internal val TUBE_CHOICES = listOf(DATA_SAVER, BAR_TIMEOUT)

internal val TUBE_FLAGS = listOf(
    Flag("resumeLast", R.string.flag_resume_last),
    Flag("remoteNotification", R.string.flag_remote_notification),
    Flag("hideStatusOverlay", R.string.flag_hide_status),
    Flag("barAtBottom", R.string.flag_bar_bottom),
    Flag("basicMode", R.string.flag_basic_mode, default = false),
    Flag("installWarning", R.string.flag_install_warning, default = false),
)

/** What Basic mode turns off: the parts that lean on m.youtube.com's markup and break first when YouTube changes it. */
internal val BASIC_OFF = setOf("skipAds", "sponsorBlock", "navOverlay", "fullscreen")

internal val TUBE_GROUPS = listOf(
    R.string.group_data to listOf("dataSaver"),
    R.string.group_fullscreen_mode to listOf("hideStatusOverlay", "barAtBottom", "barTimeout"),
    R.string.group_phone to listOf("remoteNotification", "resumeLast"),
    R.string.group_troubleshooting to listOf("basicMode", "installWarning"),
)

internal val TUBE_ICONS = mapOf(
    "dataSaver" to R.drawable.ic_wifi_off, "barTimeout" to R.drawable.ic_history, "resumeLast" to R.drawable.ic_history,
    "remoteNotification" to R.drawable.ic_smartphone, "hideStatusOverlay" to R.drawable.ic_fullscreen,
    "barAtBottom" to R.drawable.ic_tune, "basicMode" to R.drawable.ic_block, "installWarning" to R.drawable.ic_flag,
)

internal val TUBE_SUMMARIES = mapOf(
    "resumeLast" to R.string.summary_resume_last, "remoteNotification" to R.string.summary_remote_notification,
    "hideStatusOverlay" to R.string.summary_hide_status, "barAtBottom" to R.string.summary_bar_bottom,
    "basicMode" to R.string.summary_basic_mode, "installWarning" to R.string.summary_install_warning,
)

internal const val VOICE_REQUEST = 42

internal fun Context.onMobileData(): Boolean = runCatching {
    val cm = getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}.getOrDefault(false)

/** Whether the data saver applies right now. */
internal fun dataSaverOn(ctx: Context) = when (choice(ctx, DATA_SAVER)) {
    "always" -> true
    "mobile" -> ctx.onMobileData()
    else -> false
}

/** The saved quality, capped at 720p while the data saver applies ("auto" becomes 720p too). */
internal fun effectiveQuality(ctx: Context): String {
    val q = choice(ctx, QUALITY)
    if (!dataSaverOn(ctx)) return q
    val order = QUALITIES.keys.toList()
    return if (q == "auto" || order.indexOf(q) > order.indexOf("hd720")) "hd720" else q
}

// Last page and watch position, kept across app restarts for "Continue where I left off".
internal fun saveLastWatch(prefs: SharedPreferences, url: String, at: Pair<String, Int>?) {
    if (url.isEmpty() || isCarSignIn(url)) return
    prefs.edit().putString("lastUrl", url)
        .putString("lastAtId", at?.first.orEmpty()).putInt("lastAtSec", at?.second ?: 0).apply()
}

internal fun restoreLastWatch(prefs: SharedPreferences): Pair<String, Pair<String, Int>?>? {
    val url = prefs.getString("lastUrl", null)?.takeIf { it.startsWith("https://") } ?: return null
    val id = prefs.getString("lastAtId", "").orEmpty()
    val at = if (id.isNotEmpty()) id to prefs.getInt("lastAtSec", 0) else null
    return url to at
}

// =====================================================================================================================
// Car log: readable and shareable from the phone, so a failure in the car can be looked at without adb.
// =====================================================================================================================

internal object TubeLog {
    private const val TAG = "TubeAuto"
    private const val MAX_LINES = 400
    private var file: File? = null
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.applicationContext.filesDir, "tubeauto_log.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous !is CrashRecorder) Thread.setDefaultUncaughtExceptionHandler(CrashRecorder(previous))
    }

    @Synchronized
    fun d(message: String, error: Throwable? = null) {
        if (error == null) Log.i(TAG, message) else Log.w(TAG, message, error)
        val f = file ?: return
        runCatching {
            val line = buildString {
                append(time.format(Date())).append("  ").append(message)
                if (error != null) append("\n    ").append(error.javaClass.simpleName).append(": ").append(error.message)
            }
            val lines = (if (f.exists()) f.readLines() else emptyList()) + line.lines()
            f.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
        }
    }

    fun read(ctx: Context): String {
        init(ctx)
        return runCatching { file?.takeIf { it.exists() }?.readText().orEmpty() }.getOrDefault("")
    }

    fun clear(ctx: Context) {
        init(ctx)
        runCatching { file?.delete() }
    }

    fun deviceLine() =
        "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

    private class CrashRecorder(private val next: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching { d("CRASH on ${t.name}: ${e.stackTraceToString().lines().take(14).joinToString("\n    ")}") }
            next?.uncaughtException(t, e)
        }
    }
}

// =====================================================================================================================
// Car mode: which Android Auto entry point is on
// =====================================================================================================================

/**
 * - [NAVIGATION]: YT Auto's way, a Car App Library navigation app (Android Auto's buttons, gestures, car mic). Android Auto
 *   then treats TubeAuto as the navigation app, so a car's cockpit loses Google Maps' directions while it's open.
 * - [FULL_SCREEN_APP]: projection app through the unofficial SDK (like Fermata). Android Auto treats TubeAuto as an
 *   ordinary app, so Maps stays the navigation app and the cockpit keeps its directions. Real touches, floating buttons,
 *   car keyboard.
 */
internal enum class CarMode(val label: Int, val summary: Int) {
    NAVIGATION(R.string.car_mode_navigation, R.string.car_mode_navigation_summary),
    FULL_SCREEN_APP(R.string.car_mode_fullscreen, R.string.car_mode_fullscreen_summary);

    companion object {
        fun current(ctx: Context): CarMode {
            val state = ctx.packageManager.getComponentEnabledSetting(component(ctx, YoutubeCarAppService::class.java))
            // The manifest ships the navigation entry off: "default" means Full-screen app.
            return if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) NAVIGATION else FULL_SCREEN_APP
        }

        fun set(ctx: Context, mode: CarMode) {
            val pm = ctx.packageManager
            fun toggle(cls: Class<*>, on: Boolean) = pm.setComponentEnabledSetting(
                component(ctx, cls),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            toggle(YoutubeCarAppService::class.java, mode == NAVIGATION)
            toggle(LegacyCarService::class.java, mode == FULL_SCREEN_APP)
            TubeLog.d("Car mode set to $mode")
        }

        private fun component(ctx: Context, cls: Class<*>) = ComponentName(ctx.packageName, cls.name)
    }
}

// =====================================================================================================================
// Template front end (Android Auto's navigation templates): YT Auto's original car screen
// =====================================================================================================================

class YoutubeScreen(ctx: CarContext) : Screen(ctx), SurfaceCallback {
    private val engine: YoutubeEngine = object : YoutubeEngine(ctx) {
        override fun invalidate() = this@YoutubeScreen.invalidate()
        override fun isTop() = screenManager.top === this@YoutubeScreen
        override fun toast(text: String, long: Boolean) =
            CarToast.makeText(this@YoutubeScreen.carContext, text, if (long) CarToast.LENGTH_LONG else CarToast.LENGTH_SHORT).show()
        override fun showSignIn() {
            if (screenManager.top !is SignInScreen) screenManager.push(SignInScreen(this@YoutubeScreen.carContext))
        }
    }

    init {
        ctx.getCarService(AppManager::class.java).setSurfaceCallback(this)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = engine.destroy()
        })
    }

    private fun icon(id: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, id)).build()

    private fun mapAction(key: String): Action = when (key) {
        "previous" -> Action.Builder().setIcon(icon(R.drawable.ic_skip_previous)).setOnClickListener { engine.previous() }.build()
        "next" -> Action.Builder().setIcon(icon(R.drawable.ic_skip_next)).setOnClickListener { engine.next() }.build()
        "aspect" -> Action.Builder()
            .setIcon(icon(aspectIcon(choice(carContext, ASPECT))))
            .setOnClickListener { engine.toast(engine.cycleAspect()) }
            .build()
        "voice" -> Action.Builder().setIcon(icon(R.drawable.ic_mic)).setOnClickListener {
            engine.stopEnd()
            screenManager.push(CarVoiceScreen(carContext) { q -> engine.search(q) })
        }.build()
        else -> Action.Builder()
            .setIcon(icon(R.drawable.ic_settings))
            .setOnClickListener {
                engine.stopEnd() // a list over the video: the countdown would run unseen
                screenManager.push(SettingsScreen(carContext, onGo = { url ->
                    engine.goTo(url)
                    screenManager.popToRoot()
                }) { engine.settingsChanged() })
            }
            .build()
    }

    override fun onGetTemplate() = NavigationTemplate.Builder()
        .setActionStrip(
            ActionStrip.Builder()
                .addAction(Action.Builder().setIcon(icon(R.drawable.ic_arrow_back)).setOnClickListener { engine.back() }.build())
                .addAction(Action.Builder().setIcon(icon(R.drawable.ic_home)).setOnClickListener { engine.goHome() }.build())
                .apply {
                    engine.toggleShown = playToggleShown(pageUrl, engine.playing)
                    if (engine.toggleShown) addAction(
                        Action.Builder().setIcon(icon(if (engine.playing) R.drawable.ic_pause else R.drawable.ic_play_arrow))
                            .setOnClickListener { engine.togglePlay() }.build()
                    )
                }
                .addAction(Action.Builder().setIcon(icon(R.drawable.ic_search)).setOnClickListener {
                    engine.stopEnd()
                    screenManager.push(SearchScreen(carContext) { query -> engine.goTo(searchUrl(query)) })
                }.build())
                .build()
        )
        .setMapActionStrip(
            ActionStrip.Builder().apply {
                engine.stripWatch = engine.onWatch
                // Off a video the strip has room: the car mic goes there, beside settings.
                val keys = mapStrip(engine.onWatch, engine.hasNext).let { if (engine.onWatch) it else listOf("voice") + it }
                for (key in keys) addAction(mapAction(key))
            }.build()
        )
        .build()

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        val surface = container.surface ?: return
        engine.surfaceAvailable(surface, container.width, container.height, container.dpi)
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) = engine.surfaceDestroyed()
    override fun onVisibleAreaChanged(visibleArea: android.graphics.Rect) = engine.visibleAreaChanged(visibleArea)
    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) = engine.onScale(focusX, focusY, scaleFactor)
    override fun onScroll(distanceX: Float, distanceY: Float) = engine.onScroll(distanceX, distanceY)
    override fun onClick(x: Float, y: Float) = engine.onClick(x, y)
}

// =====================================================================================================================
// Full-screen app front end (projection, unofficial SDK): Maps keeps the cockpit
// =====================================================================================================================

class LegacyCarActivity : CarActivity() {
    private var engine: YoutubeEngine? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var bar: LinearLayout
    private lateinit var panel: ScrollView
    private lateinit var panelList: LinearLayout
    private var barMode = ""
    private var searchField: CarSearchField? = null

    private val hideBar = Runnable { bar.animate().alpha(0f).setDuration(250).withEndAction { bar.visibility = View.GONE } }
    private val poll = object : Runnable {
        override fun run() {
            rebuildBar(force = false)
            main.postDelayed(this, 700)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TubeLog.init(applicationContext)
        TubeLog.d("Full-screen app mode opened")
        setIgnoreConfigChanges(0xFFFFFFFF.toInt())
        runCatching {
            val ui = carUiController
            ui.menuController.hideMenuButton()
            // Android Auto's status overlay (signal, battery, clock, mic, title) off the video.
            if (flag(this, "hideStatusOverlay")) ui.statusBarController.run {
                hideAppHeader()
                hideTitle()
                hideClock()
                hideBatteryLevel()
                hideConnectivityLevel()
                hideMicButton()
                setAppBarAlpha(0f)
            }
        }.onFailure { TubeLog.d("Status overlay controls unavailable", it) }

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(30).toFloat()
                setColor(Color.argb(0xCC, 16, 16, 16))
            }
        }
        panelList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        panel = ScrollView(this).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.argb(0xF0, 18, 18, 18))
            }
            addView(panelList)
        }

        val e = object : YoutubeEngine(applicationContext) {
            override fun invalidate() { main.post { rebuildBar(force = false) } }
            override fun isTop() = panel.visibility != View.VISIBLE
            override fun toast(text: String, long: Boolean) =
                Toast.makeText(this@LegacyCarActivity, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            override fun showSignIn() = toast(R.string.signin_car_text, long = true)
        }
        engine = e
        val dpi = resources.displayMetrics.densityDpi
        val surfaceView = object : SurfaceView(this) {
            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    showBar()
                    if (panel.visibility == View.VISIBLE) {
                        panel.visibility = View.GONE
                        return true
                    }
                }
                e.touch(event)
                return true
            }
        }
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                e.surfaceAvailable(holder.surface, width, height, dpi)
            override fun surfaceDestroyed(holder: SurfaceHolder) = e.surfaceDestroyed()
        })

        val atBottom = flag(this, "barAtBottom")
        setContentView(FrameLayout(this).apply {
            addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
            addView(bar, FrameLayout.LayoutParams(-2, -2, (if (atBottom) Gravity.BOTTOM else Gravity.TOP) or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(10)
                bottomMargin = dp(10)
            })
            addView(panel, FrameLayout.LayoutParams(dp(420), -1, Gravity.END).apply {
                setMargins(dp(12), dp(12), dp(12), dp(12))
            })
        })
        rebuildBar(force = true)
        showBar()
        main.post(poll)
    }

    private fun dp(n: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, n.toFloat(), resources.displayMetrics).toInt()

    private fun showBar() {
        main.removeCallbacks(hideBar)
        bar.animate().cancel()
        bar.visibility = View.VISIBLE
        bar.alpha = 1f
        val timeout = choice(this, BAR_TIMEOUT).toLong()
        if (timeout > 0) main.postDelayed(hideBar, timeout)
    }

    /** Browsing: Back, Home, Subscriptions, Search, Size, Settings. Watching: Back, previous, -10, play, +10, next, aspect, audio only. */
    private fun rebuildBar(force: Boolean) {
        val e = engine ?: return
        val watching = e.onWatch
        val mode = if (watching) "w:${e.playing}:${e.hasNext}:${e.audioOnly}:${choice(this, ASPECT)}" else "b"
        if (!force && mode == barMode) return
        barMode = mode
        bar.removeAllViews()
        button(R.drawable.ic_arrow_back) { e.back() }
        if (watching) {
            button(R.drawable.ic_skip_previous) { e.previous() }
            button(R.drawable.ic_replay_10) { e.seekBy(-10) }
            button(if (e.playing) R.drawable.ic_pause else R.drawable.ic_play_arrow) { e.togglePlay() }
            button(R.drawable.ic_forward_10) { e.seekBy(10) }
            if (e.hasNext) button(R.drawable.ic_skip_next) { e.next() }
            button(aspectIcon(choice(this, ASPECT))) { e.toast(e.cycleAspect()) }
            button(R.drawable.ic_headphones, active = e.audioOnly) {
                e.toast(if (e.toggleAudioOnly()) R.string.audio_only_on else R.string.audio_only_off)
            }
            button(R.drawable.ic_settings) { togglePanel() }
        } else {
            button(R.drawable.ic_home) { e.goHome() }
            button(R.drawable.ic_subscriptions) { e.goTo("https://m.youtube.com/feed/subscriptions") }
            button(R.drawable.ic_search) { startCarKeyboard() }
            button(R.drawable.ic_format_size) { e.toast(e.cycleTextZoom()) }
            button(R.drawable.ic_settings) { togglePanel() }
        }
    }

    private fun button(icon: Int, active: Boolean = false, onClick: () -> Unit) {
        bar.addView(
            ImageButton(this).apply {
                setImageResource(icon)
                setColorFilter(Color.WHITE)
                scaleType = android.widget.ImageView.ScaleType.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (active) Color.rgb(204, 0, 0) else Color.argb(0x33, 255, 255, 255))
                }
                setOnClickListener {
                    showBar()
                    onClick()
                }
            },
            LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginStart = dp(4); marginEnd = dp(4) },
        )
    }

    // ---- Settings panel (the car-side settings list of this mode) ----

    private fun togglePanel() {
        if (panel.visibility == View.VISIBLE) {
            panel.visibility = View.GONE
            return
        }
        fillPanel()
        panel.visibility = View.VISIBLE
        engine?.stopEnd()
    }

    private fun fillPanel() {
        val e = engine ?: return
        panelList.removeAllViews()
        fun header(text: String) = panelList.addView(TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.argb(0xB3, 255, 255, 255))
            setPadding(dp(4), dp(10), dp(4), dp(4))
        })
        fun row(text: String, onClick: () -> Unit) = panelList.addView(TextView(this).apply {
            this.text = text
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(0x26, 255, 255, 255))
            }
            setOnClickListener {
                showBar()
                onClick()
                fillPanel()
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        fun cycle(c: Choice) {
            val keys = c.options.keys.toList()
            setChoice(this, c, keys[(keys.indexOf(choice(this, c)) + 1) % keys.size])
            e.settingsChanged()
        }
        fun choiceRow(c: Choice) = row(getString(c.title) + ":  " + label(c, choice(this, c))) { cycle(c) }
        fun flagRow(key: String) {
            val f = FLAGS.first { it.key == key }
            val on = flag(this, key)
            row(getString(f.title) + ":  " + getString(if (on) R.string.on else R.string.off)) {
                setFlag(this, key, !on)
                e.settingsChanged()
            }
        }
        header(getString(R.string.go_to))
        HOME_MENU.forEach { (url, title, _) ->
            row(getString(title)) {
                e.goTo(url)
                panel.visibility = View.GONE
            }
        }
        header(getString(R.string.group_playback))
        choiceRow(QUALITY)
        choiceRow(DATA_SAVER)
        choiceRow(SPEED)
        choiceRow(ASPECT)
        flagRow("captions")
        header(getString(R.string.group_car))
        flagRow("audioWhileMoving")
        flagRow("navOverlay")
        choiceRow(TEXT_ZOOM)
        choiceRow(HOME_PAGE)
        header(getString(R.string.group_ads))
        flagRow("skipAds")
        flagRow("sponsorBlock")
        row(getString(android.R.string.cancel)) { panel.visibility = View.GONE }
    }

    // ---- Car keyboard ----

    private fun startCarKeyboard() {
        runCatching {
            val field = searchField ?: CarSearchField(this) { q ->
                stopCarKeyboard()
                if (q.isNotBlank()) engine?.search(q)
            }.also { searchField = it }
            field.setText("")
            a().startInput(field)
        }.onFailure {
            TubeLog.d("Car keyboard unavailable", it)
            engine?.toast(R.string.keyboard_unavailable, long = true)
        }
    }

    private fun stopCarKeyboard() {
        runCatching { if (a().isInputActive) a().stopInput() }
    }

    override fun onBackPressed() {
        if (runCatching { a().isInputActive }.getOrDefault(false)) return stopCarKeyboard()
        if (panel.visibility == View.VISIBLE) {
            panel.visibility = View.GONE
            return
        }
        engine?.back()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        stopCarKeyboard()
        engine?.destroy()
        engine = null
        super.onDestroy()
    }
}

/** Off-screen text field the Android Auto keyboard types into; Search on the keyboard submits it. */
private class CarSearchField(ctx: Context, private val onSubmit: (String) -> Unit) : CarRestrictedEditText(ctx, null), CarEditable {
    init {
        setSingleLine()
        imeOptions = EditorInfo.IME_ACTION_SEARCH
    }

    override fun setCarEditableListener(listener: CarEditableListener) {
        super.setCarEditableListener(
            android.support.car.input.CarEditableListener { selStart, selEnd, candStart, candEnd ->
                listener.onUpdateSelection(selStart, selEnd, candStart, candEnd)
            },
        )
    }

    override fun onEditorAction(actionCode: Int) {
        when (actionCode) {
            EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_NEXT -> onSubmit(text?.toString().orEmpty())
            else -> super.onEditorAction(actionCode)
        }
    }
}

/** Bound by Android Auto (CATEGORY_PROJECTION) to show [LegacyCarActivity] on the head unit. */
class LegacyCarService : CarActivityService() {
    override fun getCarActivity(): Class<out CarActivity> = LegacyCarActivity::class.java
}

// =====================================================================================================================
// Car voice search (template mode): the car's microphone through Android Auto, fed to the phone's recognizer
// =====================================================================================================================

internal class CarVoiceScreen(ctx: CarContext, private val onResult: (String) -> Unit) : Screen(ctx) {
    private enum class Phase { LISTENING, WORKING, FAILED }

    private val main = Handler(Looper.getMainLooper())
    private var phase = Phase.LISTENING
    private var message = ctx.getString(R.string.voice_say)
    private var recognizer: SpeechRecognizer? = null
    private var audio: CarAudioRecord? = null
    private var pipe: Array<ParcelFileDescriptor>? = null
    private var triedOnDevice = false

    @Volatile
    private var streaming = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (phase == Phase.LISTENING && recognizer == null) start(onDevice = false)
            }

            override fun onDestroy(owner: LifecycleOwner) = stopAll()
        })
    }

    override fun onGetTemplate(): Template {
        val mic = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_mic)).build()
        val b = MessageTemplate.Builder(message)
            .setTitle(carContext.getString(when (phase) {
                Phase.LISTENING -> R.string.voice_listening
                Phase.WORKING -> R.string.voice_working
                Phase.FAILED -> R.string.voice_title
            }))
            .setIcon(mic)
            .setHeaderAction(Action.BACK)
        if (phase == Phase.FAILED) b.addAction(
            Action.Builder().setTitle(carContext.getString(R.string.voice_retry)).setFlags(Action.FLAG_PRIMARY).setOnClickListener {
                phase = Phase.LISTENING
                message = carContext.getString(R.string.voice_say)
                triedOnDevice = false
                invalidate()
                start(onDevice = false)
            }.build()
        )
        b.addAction(Action.Builder().setTitle(carContext.getString(android.R.string.cancel)).setOnClickListener { screenManager.pop() }.build())
        return b.build()
    }

    @SuppressLint("MissingPermission")
    private fun start(onDevice: Boolean) {
        stopAll()
        if (carContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return fail(carContext.getString(R.string.voice_need_mic))
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return fail(carContext.getString(R.string.voice_old_android))
        val available = if (onDevice) SpeechRecognizer.isOnDeviceRecognitionAvailable(carContext)
        else SpeechRecognizer.isRecognitionAvailable(carContext)
        if (!available) {
            if (!onDevice) start(onDevice = true) else fail(carContext.getString(R.string.voice_no_recognizer))
            return
        }
        try {
            val p = ParcelFileDescriptor.createPipe()
            pipe = p
            val rec = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(carContext)
            else SpeechRecognizer.createSpeechRecognizer(carContext)
            recognizer = rec
            rec.setRecognitionListener(listener)
            rec.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, p[0])
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, CarAudioRecord.AUDIO_CONTENT_SAMPLING_RATE)
            )
            val record = CarAudioRecord.create(carContext)
            audio = record
            record.startRecording()
            streaming = true
            Thread({ pump(record, p[1]) }, "TubeCarVoice").start()
            TubeLog.d("Car voice search started (on-device: $onDevice)")
        } catch (e: Exception) {
            TubeLog.d("Car voice search couldn't start", e)
            if (!onDevice && !triedOnDevice) {
                triedOnDevice = true
                start(onDevice = true)
            } else fail(carContext.getString(R.string.voice_unavailable))
        }
    }

    private fun pump(record: CarAudioRecord, sink: ParcelFileDescriptor) {
        val buffer = ByteArray(CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)
        val end = SystemClock.elapsedRealtime() + 8_000L
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(sink).use { out ->
                while (streaming && SystemClock.elapsedRealtime() < end) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n <= 0) break
                    out.write(buffer, 0, n)
                }
            }
        } catch (e: Exception) {
            TubeLog.d("Car microphone stream ended", e)
        } finally {
            main.post { stopAudio() }
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onEndOfSpeech() {
            stopAudio()
            phase = Phase.WORKING
            message = carContext.getString(R.string.voice_one_moment)
            invalidate()
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            stopAll()
            if (text.isEmpty()) return fail(carContext.getString(R.string.voice_no_match))
            TubeLog.d("Car voice search heard \"$text\"")
            screenManager.pop()
            onResult(text)
        }

        override fun onError(error: Int) {
            TubeLog.d("Car voice search error $error")
            stopAll()
            if (!triedOnDevice && error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                triedOnDevice = true
                start(onDevice = true)
                return
            }
            fail(carContext.getString(when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.voice_no_match
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> R.string.voice_no_network
                else -> R.string.voice_unavailable
            }))
        }
    }

    private fun fail(text: String) {
        phase = Phase.FAILED
        message = text
        invalidate()
    }

    private fun stopAudio() {
        streaming = false
        audio?.let { runCatching { it.stopRecording() } }
        audio = null
    }

    private fun stopAll() {
        stopAudio()
        recognizer?.let { runCatching { it.cancel(); it.destroy() } }
        recognizer = null
        pipe?.forEach { runCatching { it.close() } }
        pipe = null
    }
}

// =====================================================================================================================
// Phone remote additions
// =====================================================================================================================

/** Sends [q] to the car as a search (results page); kept for later if the car screen isn't open. */
internal fun sendCarSearch(ctx: Context, car: MediaController, q: String) {
    car.transportControls.sendCustomAction(REMOTE_SEARCH, Bundle().apply { putString("q", q) })
    Toast.makeText(ctx, ctx.getString(if (carPlaying()) R.string.search_sent else R.string.search_saved, q), Toast.LENGTH_SHORT).show()
}

/** Row of car-control buttons above the now-playing bar: back, home, voice, type a search, audio only, aspect. */
internal class PhoneRemoteStrip(ctx: Activity, private val car: MediaController, private val onVoice: () -> Unit) : LinearLayout(ctx) {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            alpha = if (carPlaying()) 1f else 0.55f
            handler.postDelayed(this, 1000)
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setBackgroundColor(0xFF161616.toInt())
        setPadding(dp(4), dp(4), dp(4), dp(4))
        add(R.drawable.ic_arrow_back, R.string.remote_back) { send(REMOTE_BACK) }
        add(R.drawable.ic_home, R.string.remote_home) { send(REMOTE_HOME) }
        add(R.drawable.ic_mic, R.string.remote_voice) { onVoice() }
        add(R.drawable.ic_search, R.string.remote_search) { typeSearch(ctx) }
        add(R.drawable.ic_headphones, R.string.remote_audio) { send(REMOTE_AUDIO) }
        add(R.drawable.ic_aspect_ratio, R.string.aspect_title) { send(REMOTE_ASPECT) }
        handler.post(tick)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private fun send(action: String) {
        if (!carPlaying()) {
            Toast.makeText(context, R.string.open_car_to_control, Toast.LENGTH_SHORT).show()
            return
        }
        car.transportControls.sendCustomAction(action, null)
    }

    private fun typeSearch(ctx: Activity) {
        val input = android.widget.EditText(ctx).apply {
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            hint = ctx.getString(R.string.remote_search_hint)
        }
        val dialog = android.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.remote_search)
            .setView(FrameLayout(ctx).apply {
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(input)
            })
            .setPositiveButton(R.string.remote_search) { _, _ ->
                input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { sendCarSearch(ctx, car, it) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        input.setOnEditorActionListener { _, _, _ ->
            input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { sendCarSearch(ctx, car, it) }
            dialog.dismiss()
            true
        }
        dialog.show()
        input.requestFocus()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    private fun add(icon: Int, label: Int, onClick: () -> Unit) = addView(ImageButton(context).apply {
        setImageResource(icon)
        contentDescription = context.getString(label)
        imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        setBackgroundResource(context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless)).run {
            getResourceId(0, 0).also { recycle() }
        })
        setOnClickListener { onClick() }
    }, LayoutParams(0, dp(48), 1f))
}

/** Phone notification while the car screen is up: tap for the remote. */
internal object RemoteNotice {
    private const val ID = 7

    fun show(ctx: Context) {
        if (!flag(ctx, "remoteNotification")) return
        runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("remote", ctx.getString(R.string.remote_channel), NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(ctx, 5, Intent(ctx, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(ID, Notification.Builder(ctx, "remote")
                .setSmallIcon(R.drawable.ic_smartphone)
                .setContentTitle(ctx.getString(R.string.remote_notice_title))
                .setContentText(ctx.getString(R.string.remote_notice_text))
                .setContentIntent(open)
                .setOngoing(true)
                .build())
        }
    }

    fun hide(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(ID) }
    }
}

/** Quick Settings tile: opens the phone remote from the pull-down shade. */
class RemoteTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = if (carPlaying()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = getString(if (carPlaying()) R.string.tile_in_car else R.string.tile_idle)
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val intent = Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

// =====================================================================================================================
// Phone setup rows (SettingsActivity): car mode at the top, permissions and troubleshooting at the bottom
// =====================================================================================================================

@Suppress("DEPRECATION")
internal fun addTubeSetupTop(ctx: Activity, screen: android.preference.PreferenceScreen) {
    val cat = android.preference.PreferenceCategory(ctx).apply { setTitle(R.string.group_car_mode) }
    screen.addPreference(cat)
    val modes = CarMode.entries
    cat.addPreference(android.preference.ListPreference(ctx).apply {
        isPersistent = false
        key = "carMode"
        setTitle(R.string.car_mode_title)
        setDialogTitle(R.string.car_mode_title)
        entries = modes.map { ctx.getString(it.label) }.toTypedArray()
        entryValues = modes.map { it.name }.toTypedArray()
        val now = CarMode.current(ctx)
        value = now.name
        summary = ctx.getString(now.label) + "\n" + ctx.getString(now.summary)
        icon = ctx.getDrawable(R.drawable.ic_navigation)
        setOnPreferenceChangeListener { p, v ->
            val m = CarMode.valueOf(v as String)
            CarMode.set(ctx, m)
            p.summary = ctx.getString(m.label) + "\n" + ctx.getString(m.summary)
            Toast.makeText(ctx, R.string.car_mode_reconnect, Toast.LENGTH_LONG).show()
            true
        }
    })
}

@Suppress("DEPRECATION")
internal fun addTubeSetupBottom(ctx: Activity, screen: android.preference.PreferenceScreen) {
    fun action(cat: android.preference.PreferenceCategory, title: Int, summary: String?, onClick: (android.preference.Preference) -> Unit) =
        cat.addPreference(android.preference.Preference(ctx).apply {
            setTitle(title)
            this.summary = summary
            setOnPreferenceClickListener {
                onClick(it)
                true
            }
        })

    val perms = android.preference.PreferenceCategory(ctx).apply { setTitle(R.string.group_permissions) }
    screen.addPreference(perms)
    fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    action(perms, R.string.perm_mic, ctx.getString(if (granted(Manifest.permission.RECORD_AUDIO)) R.string.perm_granted else R.string.perm_mic_summary)) {
        ctx.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 11)
    }
    action(perms, R.string.perm_location, ctx.getString(if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) R.string.perm_granted else R.string.perm_location_summary)) {
        ctx.requestLocation()
    }
    if (Build.VERSION.SDK_INT >= 33) action(perms, R.string.perm_notifications,
        ctx.getString(if (granted(Manifest.permission.POST_NOTIFICATIONS)) R.string.perm_granted else R.string.perm_notifications_summary)) {
        ctx.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 12)
    }
    if (Build.VERSION.SDK_INT >= 33) action(perms, R.string.perm_tile, ctx.getString(R.string.perm_tile_summary)) {
        runCatching {
            ctx.getSystemService(android.app.StatusBarManager::class.java).requestAddTileService(
                ComponentName(ctx, RemoteTileService::class.java), ctx.getString(R.string.tile_label),
                android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_smartphone), ctx.mainExecutor,
            ) { }
        }
    }

    val trouble = android.preference.PreferenceCategory(ctx).apply { setTitle(R.string.group_logs) }
    screen.addPreference(trouble)
    action(trouble, R.string.log_share, ctx.getString(R.string.log_share_summary)) {
        val text = TubeLog.deviceLine() + "\nTubeAuto " + runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrDefault("?") + "\nCar mode: " + CarMode.current(ctx) + "\n\n" + TubeLog.read(ctx).ifEmpty { "(log is empty)" }
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "TubeAuto car log").putExtra(Intent.EXTRA_TEXT, text), null))
    }
    action(trouble, R.string.log_clear, null) {
        TubeLog.clear(ctx)
        Toast.makeText(ctx, R.string.log_cleared, Toast.LENGTH_SHORT).show()
    }
    action(trouble, R.string.reset_all, ctx.getString(R.string.reset_all_summary)) {
        android.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.reset_all)
            .setMessage(R.string.reset_all_confirm)
            .setPositiveButton(R.string.reset_all) { _, _ ->
                prefs(ctx).edit().clear().apply()
                Toast.makeText(ctx, R.string.reset_done, Toast.LENGTH_SHORT).show()
                ctx.recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
    trouble.addPreference(android.preference.Preference(ctx).apply {
        setTitle(R.string.version)
        summary = "TubeAuto " + runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrDefault("?") +
            "\nBased on YT Auto by begati (MIT)"
        isSelectable = false
    })
}
