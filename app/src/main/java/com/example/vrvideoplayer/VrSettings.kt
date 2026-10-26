package com.example.vrvideoplayer

import android.content.Context
import androidx.preference.PreferenceManager

/** The keys under which each setting is stored. They must match res/xml/preferences.xml. */
object Keys {
    const val SCREEN_SIZE = "screen_size"
    const val SCREEN_DISTANCE = "screen_distance"
    const val SCREEN_HEIGHT = "screen_height"
    const val ASPECT_MODE = "aspect_mode"
    const val BRIGHTNESS = "brightness"
    const val CONTRAST = "contrast"
    const val SATURATION = "saturation"
    const val LAYOUT_3D = "layout_3d"
    const val SWAP_EYES = "swap_eyes"
    const val EYE_SHIFT = "eye_shift"
    const val PHONE_BRIGHTNESS_OVERRIDE = "phone_brightness_override"
    const val PHONE_BRIGHTNESS = "phone_brightness"
    const val VIEWER_INFO = "viewer_info"
    const val PLAYBACK_SPEED = "playback_speed"
    const val SKIP_SECONDS = "skip_seconds"
    const val LOOP = "loop"
    const val AUTO_START = "auto_start"
    const val AUTO_START_DELAY = "auto_start_delay"
    const val AUDIO_LANGUAGE = "audio_language"
    const val CAPTIONS_ENABLED = "captions_enabled"
    const val SUBTITLE_LANGUAGE = "subtitle_language"
    const val CAPTION_SIZE = "caption_size"
    const val CAPTION_COLOR = "caption_color"
    const val CAPTION_BACKGROUND = "caption_background"
    const val CAPTION_OFFSET = "caption_offset"
    const val MENU_OFFSET = "menu_offset"
    const val POINTER_FIX = "pointer_fix"
    const val POINTER_CAL = "pointer_cal"
    const val DWELL_CLICK = "dwell_click"
    const val DWELL_SECONDS = "dwell_seconds"
    const val SHOW_DOT = "show_dot"
    const val RESET = "reset_settings"
}

/**
 * A snapshot of all user settings, read once when a video starts. The default values here must
 * match the defaultValue entries in res/xml/preferences.xml.
 */
data class VrSettings(
    // Screen
    val screenSizePct: Int = 100,
    /** Distance of the virtual screen in tenths of a metre (30 = 3 m). */
    val screenDistanceDm: Int = 30,
    /** Moves the screen up (+) or down (-), in degrees of view angle. */
    val screenHeightDeg: Int = 0,
    /** "auto", "16:9", "4:3", "21:9", "1:1" or "fill". */
    val aspectMode: String = "auto",
    // Picture
    val brightnessPct: Int = 100,
    val contrastPct: Int = 100,
    val saturationPct: Int = 100,
    // 3D
    /** "auto", "half" or "full". */
    val layout3d: String = "auto",
    val swapEyes: Boolean = false,
    // Headset
    /** Moves the two eye images apart (+) or together (-); one step is 0.25% of the eye width. */
    val eyeShift: Int = 0,
    val overridePhoneBrightness: Boolean = false,
    val phoneBrightnessPct: Int = 100,
    // Playback
    val playbackSpeed: Float = 1f,
    val skipSeconds: Int = 10,
    val loop: Boolean = false,
    val autoStart: Boolean = false,
    val autoStartDelaySec: Int = 8,
    val audioLanguage: String = "",
    // Captions
    val captionsEnabled: Boolean = true,
    val subtitleLanguage: String = "",
    val captionSizePct: Int = 100,
    /** "white", "yellow", "cyan" or "green". */
    val captionColor: String = "white",
    val captionBackgroundPct: Int = 60,
    /** Moves captions up (+) or down (-), as a percentage of the screen height. */
    val captionOffsetPct: Int = 0,
    // Pointer (the dot in the middle of the view)
    /** Looking at an in-headset button for [dwellSeconds] clicks it. */
    val dwellClick: Boolean = true,
    val dwellSeconds: Int = 2,
    val showDot: Boolean = true,
    /** Moves the control bar up (+) or down (-) from its normal place, in degrees. */
    val menuOffsetDeg: Int = 0,
    /** Strength of the mouse pointer's lens-warp correction, in percent (100 = normal). */
    val pointerFixPct: Int = 100,
    /** Result of the mouse calibration: lens-warp k1, k2 and the offset x, y. Null = none yet. */
    val pointerCal: FloatArray? = null,
) {
    companion object {
        private fun parseCal(text: String?): FloatArray? {
            val parts = text?.split(",")?.mapNotNull { it.trim().toFloatOrNull() } ?: return null
            return if (parts.size == 4) parts.toFloatArray() else null
        }

        fun load(context: Context): VrSettings {
            val d = VrSettings()
            val p = PreferenceManager.getDefaultSharedPreferences(context)
            return VrSettings(
                screenSizePct = p.getInt(Keys.SCREEN_SIZE, d.screenSizePct),
                screenDistanceDm = p.getInt(Keys.SCREEN_DISTANCE, d.screenDistanceDm),
                screenHeightDeg = p.getInt(Keys.SCREEN_HEIGHT, d.screenHeightDeg),
                aspectMode = p.getString(Keys.ASPECT_MODE, d.aspectMode) ?: d.aspectMode,
                brightnessPct = p.getInt(Keys.BRIGHTNESS, d.brightnessPct),
                contrastPct = p.getInt(Keys.CONTRAST, d.contrastPct),
                saturationPct = p.getInt(Keys.SATURATION, d.saturationPct),
                layout3d = p.getString(Keys.LAYOUT_3D, d.layout3d) ?: d.layout3d,
                swapEyes = p.getBoolean(Keys.SWAP_EYES, d.swapEyes),
                eyeShift = p.getInt(Keys.EYE_SHIFT, d.eyeShift),
                overridePhoneBrightness =
                    p.getBoolean(Keys.PHONE_BRIGHTNESS_OVERRIDE, d.overridePhoneBrightness),
                phoneBrightnessPct = p.getInt(Keys.PHONE_BRIGHTNESS, d.phoneBrightnessPct),
                playbackSpeed = p.getString(Keys.PLAYBACK_SPEED, null)?.toFloatOrNull()
                    ?: d.playbackSpeed,
                skipSeconds = p.getString(Keys.SKIP_SECONDS, null)?.toIntOrNull()
                    ?: d.skipSeconds,
                loop = p.getBoolean(Keys.LOOP, d.loop),
                autoStart = p.getBoolean(Keys.AUTO_START, d.autoStart),
                autoStartDelaySec = p.getInt(Keys.AUTO_START_DELAY, d.autoStartDelaySec),
                audioLanguage = p.getString(Keys.AUDIO_LANGUAGE, d.audioLanguage)?.trim().orEmpty(),
                captionsEnabled = p.getBoolean(Keys.CAPTIONS_ENABLED, d.captionsEnabled),
                subtitleLanguage =
                    p.getString(Keys.SUBTITLE_LANGUAGE, d.subtitleLanguage)?.trim().orEmpty(),
                captionSizePct = p.getInt(Keys.CAPTION_SIZE, d.captionSizePct),
                captionColor = p.getString(Keys.CAPTION_COLOR, d.captionColor) ?: d.captionColor,
                captionBackgroundPct = p.getInt(Keys.CAPTION_BACKGROUND, d.captionBackgroundPct),
                captionOffsetPct = p.getInt(Keys.CAPTION_OFFSET, d.captionOffsetPct),
                dwellClick = p.getBoolean(Keys.DWELL_CLICK, d.dwellClick),
                dwellSeconds = p.getInt(Keys.DWELL_SECONDS, d.dwellSeconds),
                showDot = p.getBoolean(Keys.SHOW_DOT, d.showDot),
                menuOffsetDeg = p.getInt(Keys.MENU_OFFSET, d.menuOffsetDeg),
                pointerFixPct = p.getInt(Keys.POINTER_FIX, d.pointerFixPct),
                pointerCal = parseCal(p.getString(Keys.POINTER_CAL, null)),
            )
        }
    }
}
