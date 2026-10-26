package com.example.vrvideoplayer

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.preference.PreferenceManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import com.google.cardboard.sdk.CardboardView
import java.util.Locale

/**
 * Plays one video inside the Cardboard viewer.
 *
 * Controls (the viewer's button is delivered to the app as a screen tap):
 *  - First press ......... recenters the view on where you are looking, then starts the video
 *  - Press ............... play / pause (replays from the start once the video has ended)
 *  - Double press ........ recenter: moves the screen (or the front of a 360 video) to where
 *                          you are looking right now
 *  - Volume up / down .... skip forward / back (the step is a setting)
 *  - Earphone button, Bluetooth gamepad A / Enter: same as the viewer's button (for viewers
 *                          whose button does not touch the screen)
 *  - Auto-start setting .. starts the video by itself a few seconds after opening, for viewers
 *                          that have no usable button at all
 *  - The dot in the middle of your view points at the in-headset buttons (control bar: look
 *                          down; or it stays up while paused). Keep the dot on a button for the
 *                          "look time" (2 s by default; can be turned off in Settings) to click it,
 *                          or click the mouse while the dot is on it.
 *  - X icon (top left) ... leaves the player; the phone's back button does the same
 *  - Gear icon (top right) scan the QR code on your viewer to set up the lens correction
 *                          (both icons are drawn by the Cardboard SDK and need a finger, so
 *                          use them before the phone goes into the headset)
 *
 * A status bar (play/pause, time, progress) appears in the headset for a few seconds after
 * every action.
 */
class VrPlayerActivity : AppCompatActivity() {

    private lateinit var cardboardView: CardboardView
    private lateinit var renderer: VideoRenderer
    private lateinit var settings: VrSettings
    private var menuPage = MenuPage.BAR
    private var player: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var prepared = false
    private var started = false // false until the viewer button is pressed for the first time
    private var resumePlayback = false
    private var pendingPress: Runnable? = null
    private var isShutDown = false

    // Status bar in the headset.
    private var hudNote: String? = null
    private var hudHideAtMs = 0L
    private val hudTicker = object : Runnable {
        override fun run() {
            val exoPlayer = player
            if (exoPlayer == null || SystemClock.uptimeMillis() >= hudHideAtMs) {
                renderer.setHud(null)
                hudNote = null
                return
            }
            val duration = exoPlayer.duration
            renderer.setHud(
                HudState(
                    playing = exoPlayer.playWhenReady &&
                        exoPlayer.playbackState != Player.STATE_ENDED,
                    positionMs = exoPlayer.currentPosition,
                    durationMs = if (duration == C.TIME_UNSET) 0L else duration,
                    note = hudNote,
                ),
            )
            mainHandler.postDelayed(this, HUD_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        val mode = runCatching { VideoMode.valueOf(intent.getStringExtra(EXTRA_MODE).orEmpty()) }
            .getOrDefault(VideoMode.FLAT_2D)
        val stereo = intent.getBooleanExtra(EXTRA_STEREO, true)
        settings = VrSettings.load(this)
        Log.i(TAG, "onCreate: mode=$mode stereo=$stereo uri=$uri")

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Let the picture extend under a camera cut-out; it sits on the short edge.
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (settings.overridePhoneBrightness) {
                screenBrightness = (settings.phoneBrightnessPct / 100f).coerceIn(0.01f, 1f)
            }
        }
        enterImmersiveMode()

        player = createPlayer(uri)
        volumeControlStream = AudioManager.STREAM_MUSIC
        renderer = VideoRenderer(
            mode,
            settings,
            onVideoSurfaceReady = { surface -> onVideoSurfaceReady(surface) },
            onMenuAction = { id, fraction -> mainHandler.post { handleMenuAction(id, fraction) } },
            onCalibration = { step, result ->
                mainHandler.post { onCalibrationEvent(step, result) }
            },
            onMenuPick = { id, fraction ->
                mainHandler.post {
                    if (id != null) handleMenuAction(id, fraction) else onViewerButton()
                }
            },
        )

        // The Cardboard SDK's own demo apps use its GL view; do the same here.
        CardboardView.setUseCardboardGlSurfaceView(true)
        cardboardView = CardboardView(this).also { view ->
            // In stereo mode the view splits the screen for the two lenses and draws the
            // back / gear buttons; in mono mode it is a plain "magic window" preview.
            view.setStereoRenderMode(stereo)
            view.setRenderer(renderer)
            view.setOnTriggerEvent { onViewerClick() }
            view.setOnBackButtonClick { finish() }
            view.setOnSettingsButtonClick { view.scanViewerQrCode() }
            view.setOnViewDetachedRunnable { shutdown() }
        }
        setContentView(cardboardView)
        rebuildMenu()

        showHud(getString(R.string.hud_press_to_play), HUD_WAITING_MS)

        if (settings.autoStart) {
            mainHandler.postDelayed({
                if (!started) {
                    togglePlayback()
                }
            }, settings.autoStartDelaySec * 1000L)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::cardboardView.isInitialized) {
            return
        }
        Log.i(TAG, "onResume")
        enterImmersiveMode()
        cardboardView.onResume()
        if (resumePlayback) {
            player?.play()
        }
    }

    override fun onPause() {
        super.onPause()
        if (!::cardboardView.isInitialized) {
            return
        }
        Log.i(TAG, "onPause")
        resumePlayback = player?.playWhenReady == true
        player?.pause()
        // Stops the GL thread, so no frame is being drawn once we get to onDestroy().
        cardboardView.onPause()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        mainHandler.removeCallbacksAndMessages(null)
        if (::cardboardView.isInitialized) {
            shutdown()
        }
        player?.release()
        player = null
        if (::renderer.isInitialized) {
            renderer.release()
        }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enterImmersiveMode()
        }
    }

    private var pointerKnown = false

    /** Remembers where the mouse pointer or finger is, so a click can pick the button under it. */
    private fun trackPointer(event: MotionEvent) {
        if (!::cardboardView.isInitialized || !::renderer.isInitialized) {
            return
        }
        val width = cardboardView.width
        val height = cardboardView.height
        if (width <= 0 || height <= 0) {
            return
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_HOVER_ENTER -> {
                renderer.setPointer(event.x / width, event.y / height)
                pointerKnown = true
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        trackPointer(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        trackPointer(ev)
        return super.dispatchGenericMotionEvent(ev)
    }

    /**
     * A click or tap. With look-click on, buttons are chosen by the dot; with it off, by the
     * place where the mouse pointer (or finger) is.
     */
    private fun onViewerClick() {
        if (renderer.calibrating) {
            renderer.calibrationClick()
            return
        }
        if (!settings.dwellClick && pointerKnown) {
            renderer.clickAtPointer()
        } else {
            onViewerButton()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                seekBy(settings.skipSeconds * 1000L)
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                seekBy(-settings.skipSeconds * 1000L)
                true
            }
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                // Ignore auto-repeat while a button is held down.
                if ((event?.repeatCount ?: 0) == 0) {
                    onViewerButton()
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // ---- Player ----------------------------------------------------------------------------------

    private fun createPlayer(uri: Uri): ExoPlayer {
        val exoPlayer = ExoPlayer.Builder(this).build()
        exoPlayer.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    renderer.setVideoAspect(
                        videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height,
                    )
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                rebuildMenu()
            }

            override fun onTracksChanged(tracks: Tracks) {
                rebuildMenu()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.i(TAG, "playback state = $playbackState")
                rebuildMenu()
                when (playbackState) {
                    Player.STATE_BUFFERING -> if (started) showHud(getString(R.string.hud_loading))
                    Player.STATE_ENDED -> showHud(getString(R.string.hud_ended), HUD_WAITING_MS)
                    else -> Unit
                }
            }

            override fun onCues(cueGroup: CueGroup) {
                val text = cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n")
                renderer.setCaptionText(text)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "player error", error)
                Toast.makeText(
                    this@VrPlayerActivity,
                    getString(R.string.error_playback, error.errorCodeName),
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            }
        })

        val item = MediaItem.Builder().setUri(uri)
        intent.getStringExtra(EXTRA_SUBTITLE_URI)?.let { subtitle ->
            val name = intent.getStringExtra(EXTRA_SUBTITLE_NAME) ?: subtitle
            item.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle))
                        .setMimeType(subtitleMimeType(name))
                        .setLanguage(settings.subtitleLanguage.ifBlank { null })
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build(),
                ),
            )
        }
        exoPlayer.setMediaItem(item.build())

        exoPlayer.playbackParameters = PlaybackParameters(settings.playbackSpeed)
        exoPlayer.repeatMode = if (settings.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        val tracks = exoPlayer.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !settings.captionsEnabled)
            .setSelectUndeterminedTextLanguage(true)
        if (settings.audioLanguage.isNotEmpty()) {
            tracks.setPreferredAudioLanguage(settings.audioLanguage)
        }
        if (settings.subtitleLanguage.isNotEmpty()) {
            tracks.setPreferredTextLanguage(settings.subtitleLanguage)
        }
        exoPlayer.trackSelectionParameters = tracks.build()

        // Start paused: the viewer is probably still in your hand. Press the viewer's button
        // once the phone is in the headset. prepare() is called once the video surface exists.
        exoPlayer.playWhenReady = false
        return exoPlayer
    }

    /** Called on the GL thread once the renderer has a surface for the decoder to draw into. */
    private fun onVideoSurfaceReady(surface: Surface) {
        Log.i(TAG, "video surface ready (valid=${surface.isValid})")
        mainHandler.post {
            val exoPlayer = player ?: return@post
            if (!surface.isValid) {
                return@post
            }
            exoPlayer.setVideoSurface(surface)
            if (!prepared) {
                prepared = true
                Log.i(TAG, "prepare()")
                exoPlayer.prepare()
            }
        }
    }

    private fun seekBy(deltaMs: Long) {
        val exoPlayer = player ?: return
        var target = exoPlayer.currentPosition + deltaMs
        val duration = exoPlayer.duration
        if (duration != C.TIME_UNSET) {
            target = target.coerceAtMost(duration)
        }
        exoPlayer.seekTo(target.coerceAtLeast(0L))
        val seconds = deltaMs / 1000
        showHud(if (seconds >= 0) "+$seconds s" else "$seconds s")
    }

    // ---- Viewer button -----------------------------------------------------------------------------

    /**
     * A single press toggles play/pause, but we wait a moment to see whether a second press
     * follows; two quick presses mean "recenter" instead.
     */
    private fun onViewerButton() {
        Log.i(TAG, "viewer button pressed")
        // A click (mouse or tap) while the dot is on an in-headset button presses that button.
        val hovered = renderer.hoveredButtonId
        if (hovered != null) {
            renderer.cancelDwell()
            handleMenuAction(hovered, renderer.hoveredFraction)
            return
        }
        val pending = pendingPress
        if (pending != null) {
            mainHandler.removeCallbacks(pending)
            pendingPress = null
            renderer.recenter()
            showHud(getString(R.string.hud_recentered))
            return
        }
        val press = Runnable {
            pendingPress = null
            togglePlayback()
        }
        pendingPress = press
        mainHandler.postDelayed(press, DOUBLE_PRESS_WINDOW_MS)
    }

    private fun togglePlayback(yawOnly: Boolean = false) {
        val exoPlayer = player ?: return
        when {
            !started -> {
                started = true
                // From the menu, only the left/right direction is taken over: you were looking
                // down at the button, so the screen must not be tilted down with it.
                renderer.recenter(yawOnly)
                exoPlayer.play()
                Log.i(TAG, "first play; state=${exoPlayer.playbackState}")
                showHud(if (prepared) null else getString(R.string.hud_loading))
            }
            exoPlayer.playbackState == Player.STATE_ENDED -> {
                exoPlayer.seekTo(0L)
                exoPlayer.play()
                showHud()
            }
            exoPlayer.playWhenReady -> {
                exoPlayer.pause()
                showHud()
            }
            else -> {
                exoPlayer.play()
                showHud()
            }
        }
    }

    // ---- In-headset menu -----------------------------------------------------------------------

    private fun prefs() = PreferenceManager.getDefaultSharedPreferences(this)

    /** Builds the menu pages for the current state and hands them to the renderer. */
    private fun rebuildMenu() {
        val exoPlayer = player ?: return
        if (!::renderer.isInitialized || !::settings.isInitialized) {
            return
        }
        val playing = exoPlayer.playWhenReady && exoPlayer.playbackState != Player.STATE_ENDED
        val state = MenuState(
            playing, settings, subtitleLabel(exoPlayer), audioLabel(exoPlayer),
            exoPlayer.currentPosition,
            exoPlayer.duration.let { if (it == C.TIME_UNSET) 0L else it },
        )
        val model = if (menuPage == MenuPage.SETTINGS) {
            MenuBuilder.settingsPage(state)
        } else {
            MenuBuilder.bar(state)
        }
        renderer.setMenu(model)
        // While nothing is playing the control bar stays up so there is always something to click.
        renderer.menuAlwaysVisible = !playing
        mainHandler.removeCallbacks(menuTicker)
        if (playing) {
            mainHandler.postDelayed(menuTicker, MENU_REFRESH_MS)
        }
    }

    /** Keeps the seek bar's position current while the video plays. */
    private val menuTicker = Runnable { rebuildMenu() }

    private fun handleMenuAction(id: String, fraction: Float = 0f) {
        val exoPlayer = player ?: return
        val s = settings
        when (id) {
            MenuIds.SEEK -> {
                val duration = exoPlayer.duration
                if (duration != C.TIME_UNSET && duration > 0) {
                    exoPlayer.seekTo((duration * fraction).toLong())
                    showHud(formatClock((duration * fraction).toLong()))
                }
            }
            MenuIds.CALIBRATE -> {
                menuPage = MenuPage.BAR
                rebuildMenu()
                renderer.startCalibration()
            }
            MenuIds.CALIBRATE_RESET -> {
                prefs().edit().remove(Keys.POINTER_CAL).apply()
                reloadSettings()
                showHud(getString(R.string.hud_cal_reset))
            }
            MenuIds.MOVE_DOWN -> stepInt(Keys.MENU_OFFSET, s.menuOffsetDeg, -5, -50, 20)
            MenuIds.MOVE_UP -> stepInt(Keys.MENU_OFFSET, s.menuOffsetDeg, 5, -50, 20)
            MenuIds.MENU_POS_DOWN -> stepInt(Keys.MENU_OFFSET, s.menuOffsetDeg, -5, -50, 20)
            MenuIds.MENU_POS_UP -> stepInt(Keys.MENU_OFFSET, s.menuOffsetDeg, 5, -50, 20)
            MenuIds.POINTER_FIX_DOWN -> stepInt(Keys.POINTER_FIX, s.pointerFixPct, -10, -100, 300)
            MenuIds.POINTER_FIX_UP -> stepInt(Keys.POINTER_FIX, s.pointerFixPct, 10, -100, 300)
            MenuIds.SKIP_BACK -> seekBy(-s.skipSeconds * 1000L)
            MenuIds.SKIP_FORWARD -> seekBy(s.skipSeconds * 1000L)
            MenuIds.PLAY -> togglePlayback(yawOnly = true)
            MenuIds.VOLUME_DOWN -> changeVolume(AudioManager.ADJUST_LOWER)
            MenuIds.VOLUME_UP -> changeVolume(AudioManager.ADJUST_RAISE)
            MenuIds.RECENTER -> {
                renderer.recenter(yawOnly = true)
                showHud(getString(R.string.hud_recentered))
            }
            MenuIds.OPEN_SETTINGS -> {
                menuPage = MenuPage.SETTINGS
                rebuildMenu()
            }
            MenuIds.BACK -> {
                menuPage = MenuPage.BAR
                rebuildMenu()
            }
            MenuIds.EXIT -> finish()

            MenuIds.ZOOM_DOWN -> stepInt(Keys.SCREEN_SIZE, s.screenSizePct, -25, 50, 400)
            MenuIds.ZOOM_UP -> stepInt(Keys.SCREEN_SIZE, s.screenSizePct, 25, 50, 400)
            MenuIds.DISTANCE_DOWN -> stepInt(Keys.SCREEN_DISTANCE, s.screenDistanceDm, -5, 15, 80)
            MenuIds.DISTANCE_UP -> stepInt(Keys.SCREEN_DISTANCE, s.screenDistanceDm, 5, 15, 80)
            MenuIds.HEIGHT_DOWN -> stepInt(Keys.SCREEN_HEIGHT, s.screenHeightDeg, -5, -30, 30)
            MenuIds.HEIGHT_UP -> stepInt(Keys.SCREEN_HEIGHT, s.screenHeightDeg, 5, -30, 30)
            MenuIds.BRIGHTNESS_DOWN -> stepInt(Keys.BRIGHTNESS, s.brightnessPct, -10, 20, 150)
            MenuIds.BRIGHTNESS_UP -> stepInt(Keys.BRIGHTNESS, s.brightnessPct, 10, 20, 150)
            MenuIds.CONTRAST_DOWN -> stepInt(Keys.CONTRAST, s.contrastPct, -10, 50, 150)
            MenuIds.CONTRAST_UP -> stepInt(Keys.CONTRAST, s.contrastPct, 10, 50, 150)
            MenuIds.CAPTION_POSITION_DOWN -> stepInt(Keys.CAPTION_OFFSET, s.captionOffsetPct, -10, -50, 50)
            MenuIds.CAPTION_POSITION_UP -> stepInt(Keys.CAPTION_OFFSET, s.captionOffsetPct, 10, -50, 50)
            MenuIds.EYE_DOWN -> stepInt(Keys.EYE_SHIFT, s.eyeShift, -1, -80, 80)
            MenuIds.EYE_UP -> stepInt(Keys.EYE_SHIFT, s.eyeShift, 1, -80, 80)
            MenuIds.CAPTION_SIZE_DOWN -> stepInt(Keys.CAPTION_SIZE, s.captionSizePct, -10, 50, 200)
            MenuIds.CAPTION_SIZE_UP -> stepInt(Keys.CAPTION_SIZE, s.captionSizePct, 10, 50, 200)
            MenuIds.SPEED_DOWN -> stepSpeed(-1)
            MenuIds.SPEED_UP -> stepSpeed(1)
            MenuIds.CAPTIONS -> {
                prefs().edit().putBoolean(Keys.CAPTIONS_ENABLED, !s.captionsEnabled).apply()
                reloadSettings()
            }
            MenuIds.GAZE_TOGGLE -> {
                prefs().edit().putBoolean(Keys.DWELL_CLICK, !s.dwellClick).apply()
                reloadSettings()
            }
            MenuIds.GAZE_TIME -> {
                val next = if (s.dwellSeconds >= 5) 1 else s.dwellSeconds + 1
                prefs().edit().putInt(Keys.DWELL_SECONDS, next).apply()
                reloadSettings()
            }
            MenuIds.SUBTITLE_TRACK -> cycleSubtitle(exoPlayer)
            MenuIds.AUDIO_TRACK -> cycleAudio(exoPlayer)
        }
    }

    private fun onCalibrationEvent(step: Int, result: FloatArray?) {
        when {
            result == null -> showHud(getString(R.string.hud_cal_step, step + 1, 9), HUD_WAITING_MS)
            result.size == 4 -> {
                prefs().edit().putString(Keys.POINTER_CAL, result.joinToString(",")).apply()
                reloadSettings()
                showHud(getString(R.string.hud_cal_done))
            }
            else -> showHud(getString(R.string.hud_cal_failed))
        }
    }

    /** Changes a whole-number setting, saves it (so it is kept for next time) and applies it. */
    private fun stepInt(key: String, current: Int, delta: Int, min: Int, max: Int) {
        prefs().edit().putInt(key, (current + delta).coerceIn(min, max)).apply()
        reloadSettings()
    }

    private fun stepSpeed(direction: Int) {
        val values = listOf("0.5", "0.75", "1.0", "1.25", "1.5", "2.0")
        val current = values.indices.minByOrNull {
            kotlin.math.abs(values[it].toFloat() - settings.playbackSpeed)
        } ?: 2
        val next = (current + direction).coerceIn(0, values.lastIndex)
        prefs().edit().putString(Keys.PLAYBACK_SPEED, values[next]).apply()
        reloadSettings()
    }

    /** Re-reads the saved settings and applies them to the picture, the player and the menu. */
    private fun reloadSettings() {
        settings = VrSettings.load(this)
        renderer.applySettings(settings)
        player?.let { exoPlayer ->
            exoPlayer.playbackParameters = PlaybackParameters(settings.playbackSpeed)
            exoPlayer.repeatMode =
                if (settings.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !settings.captionsEnabled)
                .build()
        }
        rebuildMenu()
    }

    private fun changeVolume(direction: Int) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val percent = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
        showHud(getString(R.string.hud_volume, percent))
    }

    private fun trackGroups(exoPlayer: ExoPlayer, type: Int): List<Tracks.Group> =
        exoPlayer.currentTracks.groups.filter { it.type == type && it.length > 0 }

    private fun trackLabel(group: Tracks.Group, index: Int): String {
        val format = group.getTrackFormat(0)
        val language = format.language
        val name = format.label
            ?: if (language != null && language != "und") {
                Locale.forLanguageTag(language).displayLanguage.ifBlank { language }
            } else {
                "Track ${index + 1}"
            }
        return name.take(16)
    }

    private fun subtitleLabel(exoPlayer: ExoPlayer): String {
        val groups = trackGroups(exoPlayer, C.TRACK_TYPE_TEXT)
        if (groups.isEmpty()) {
            return "None"
        }
        val disabled = C.TRACK_TYPE_TEXT in exoPlayer.trackSelectionParameters.disabledTrackTypes
        if (!settings.captionsEnabled || disabled) {
            return "Off"
        }
        val selected = groups.indexOfFirst { it.isSelected }
        return if (selected < 0) "Auto" else trackLabel(groups[selected], selected)
    }

    private fun audioLabel(exoPlayer: ExoPlayer): String {
        val groups = trackGroups(exoPlayer, C.TRACK_TYPE_AUDIO)
        if (groups.isEmpty()) {
            return "None"
        }
        val selected = groups.indexOfFirst { it.isSelected }
        return if (selected < 0) "Auto" else trackLabel(groups[selected], selected)
    }

    /** Subtitles: track 1, track 2, ..., off, and round again. */
    private fun cycleSubtitle(exoPlayer: ExoPlayer) {
        val groups = trackGroups(exoPlayer, C.TRACK_TYPE_TEXT)
        if (groups.isEmpty()) {
            showHud(getString(R.string.hud_no_subtitles))
            return
        }
        val off = !settings.captionsEnabled ||
            C.TRACK_TYPE_TEXT in exoPlayer.trackSelectionParameters.disabledTrackTypes
        val current = if (off) -1 else groups.indexOfFirst { it.isSelected }
        val next = current + 1
        if (next >= groups.size) {
            prefs().edit().putBoolean(Keys.CAPTIONS_ENABLED, false).apply()
            reloadSettings()
        } else {
            prefs().edit().putBoolean(Keys.CAPTIONS_ENABLED, true).apply()
            settings = VrSettings.load(this)
            renderer.applySettings(settings)
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .addOverride(TrackSelectionOverride(groups[next].mediaTrackGroup, 0))
                .build()
            rebuildMenu()
        }
    }

    /** Audio language / track: track 1, track 2, ... and round again. */
    private fun cycleAudio(exoPlayer: ExoPlayer) {
        val groups = trackGroups(exoPlayer, C.TRACK_TYPE_AUDIO)
        if (groups.size < 2) {
            showHud(getString(R.string.hud_one_audio))
            return
        }
        val next = (groups.indexOfFirst { it.isSelected } + 1) % groups.size
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .addOverride(TrackSelectionOverride(groups[next].mediaTrackGroup, 0))
            .build()
        rebuildMenu()
    }

    // ---- Status bar --------------------------------------------------------------------------------

    /** Shows the status bar in the headset for [durationMs], with an optional [note]. */
    private fun showHud(note: String? = null, durationMs: Long = HUD_VISIBLE_MS) {
        hudNote = note
        hudHideAtMs = SystemClock.uptimeMillis() + durationMs
        mainHandler.removeCallbacks(hudTicker)
        mainHandler.post(hudTicker)
    }

    // ---- Housekeeping ----------------------------------------------------------------------------

    /** Closes the Cardboard SDK objects. Safe to call more than once. */
    private fun shutdown() {
        if (isShutDown) {
            return
        }
        isShutDown = true
        cardboardView.onDestroy()
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    companion object {
        private const val TAG = "VRVideo"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_STEREO = "stereo"
        private const val EXTRA_SUBTITLE_URI = "subtitle_uri"
        private const val EXTRA_SUBTITLE_NAME = "subtitle_name"
        private const val DOUBLE_PRESS_WINDOW_MS = 350L
        private const val HUD_VISIBLE_MS = 3_000L
        private const val HUD_WAITING_MS = 10 * 60 * 1000L // stays until something happens
        private const val HUD_REFRESH_MS = 500L
        private const val MENU_REFRESH_MS = 1_000L

        /** Picks the subtitle format from the file name; SubRip (.srt) is the most common. */
        private fun subtitleMimeType(name: String): String {
            val n = name.lowercase()
            return when {
                n.endsWith(".vtt") -> MimeTypes.TEXT_VTT
                n.endsWith(".ass") || n.endsWith(".ssa") -> MimeTypes.TEXT_SSA
                n.endsWith(".ttml") || n.endsWith(".xml") || n.endsWith(".dfxp") ->
                    MimeTypes.APPLICATION_TTML
                else -> MimeTypes.APPLICATION_SUBRIP
            }
        }

        /**
         * Intent to play [uri] in [mode]. [stereo] = true splits the screen for a headset;
         * false shows a single full-screen view you can look around by moving the phone.
         * [subtitleUri] is an optional subtitle file, [subtitleName] its file name.
         */
        fun createIntent(
            context: Context,
            uri: Uri,
            mode: VideoMode,
            stereo: Boolean,
            subtitleUri: Uri?,
            subtitleName: String?,
        ): Intent {
            return Intent(context, VrPlayerActivity::class.java).apply {
                data = uri
                putExtra(EXTRA_MODE, mode.name)
                putExtra(EXTRA_STEREO, stereo)
                subtitleUri?.let {
                    putExtra(EXTRA_SUBTITLE_URI, it.toString())
                    putExtra(EXTRA_SUBTITLE_NAME, subtitleName ?: it.toString())
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}
