package com.example.vrvideoplayer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.util.Locale
import kotlin.math.min

/**
 * The in-headset control panel. It is a flat picture floating in front of you, below the
 * screen. You use a button by looking at it (the dot in the middle of your view) until it
 * is clicked, or by clicking the mouse while the dot is on it.
 */
enum class MenuPage(
    /** Size of the picture, in pixels. Button positions below are in these pixels. */
    val bitmapWidth: Int,
    val bitmapHeight: Int,
    /** Size and place in the 3D scene: width in metres, distance from you, and how far below
     *  (negative) or above the screen's centre height it sits, in degrees. */
    val widthMeters: Float,
    val distance: Float,
    val pitchDeg: Float,
) {
    /** The row of main buttons: play, skip, volume, ... */
    BAR(1536, 400, 2.8f, 2.2f, -34f),

    /** The settings page. */
    SETTINGS(1536, 900, 3.2f, 2.4f, -20f);

    val heightMeters: Float get() = widthMeters * bitmapHeight / bitmapWidth
}

data class MenuButton(
    val id: String,
    val label: String,
    /** Position in the page's picture, in pixels. */
    val rect: RectF,
    /** If true, keeping the dot on the button clicks it again and again (for + / - buttons). */
    val repeatable: Boolean = false,
    /** If set, the button is drawn as a seek bar filled to this fraction (0..1). */
    val progress: Float? = null,
    /** Name of the picture drawn on the button (see MenuIcons), or null. */
    val icon: String? = null,
)

data class MenuText(
    val text: String,
    val x: Float,
    val y: Float,
    val size: Float,
    val align: Paint.Align = Paint.Align.LEFT,
    val dim: Boolean = false,
)

/** Everything needed to draw one page and to know which button is under the dot. */
data class MenuModel(
    val page: MenuPage,
    val buttons: List<MenuButton>,
    val texts: List<MenuText>,
)

/** The values the menu shows. */
data class MenuState(
    val playing: Boolean,
    val settings: VrSettings,
    val subtitleLabel: String,
    val audioLabel: String,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

fun formatClock(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%d:%02d", m, sec)
}

/** Button ids: what each button does is decided in VrPlayerActivity.handleMenuAction. */
object MenuIds {
    const val SEEK = "seek"
    const val SKIP_BACK = "skip_back"
    const val PLAY = "play"
    const val SKIP_FORWARD = "skip_fwd"
    const val VOLUME_DOWN = "vol_down"
    const val VOLUME_UP = "vol_up"
    const val RECENTER = "recenter"
    const val OPEN_SETTINGS = "menu"
    const val EXIT = "exit"
    const val BACK = "back"
    const val ZOOM_DOWN = "zoom-"
    const val ZOOM_UP = "zoom+"
    const val DISTANCE_DOWN = "dist-"
    const val DISTANCE_UP = "dist+"
    const val HEIGHT_DOWN = "height-"
    const val HEIGHT_UP = "height+"
    const val BRIGHTNESS_DOWN = "bright-"
    const val BRIGHTNESS_UP = "bright+"
    const val CONTRAST_DOWN = "contrast-"
    const val CONTRAST_UP = "contrast+"
    const val CAPTION_POSITION_DOWN = "capoff-"
    const val CAPTION_POSITION_UP = "capoff+"
    const val EYE_DOWN = "eye-"
    const val EYE_UP = "eye+"
    const val SPEED_DOWN = "speed-"
    const val SPEED_UP = "speed+"
    const val CAPTIONS = "captions"
    const val CAPTION_SIZE_DOWN = "capsize-"
    const val CAPTION_SIZE_UP = "capsize+"
    const val SUBTITLE_TRACK = "subtitle"
    const val AUDIO_TRACK = "audio"
    const val GAZE_TOGGLE = "gaze"
    const val GAZE_TIME = "gaze_time"
    const val CALIBRATE = "calibrate"
    const val CALIBRATE_RESET = "calibrate_reset"
    const val MOVE_DOWN = "move_down"
    const val MOVE_UP = "move_up"
    const val MENU_POS_DOWN = "menupos-"
    const val MENU_POS_UP = "menupos+"
    const val POINTER_FIX_DOWN = "pfix-"
    const val POINTER_FIX_UP = "pfix+"
}

object MenuBuilder {

    fun bar(state: MenuState): MenuModel {
        val page = MenuPage.BAR
        val skip = state.settings.skipSeconds
        // id, picture, label, repeats while you keep looking at it
        class Item(val id: String, val icon: String, val label: String, val repeatable: Boolean)
        val items = listOf(
            Item(MenuIds.SKIP_BACK, "back", "−${skip}s", true),
            Item(MenuIds.PLAY, if (state.playing) "pause" else "play", if (state.playing) "Pause" else "Play", false),
            Item(MenuIds.SKIP_FORWARD, "forward", "+${skip}s", true),
            Item(MenuIds.VOLUME_DOWN, "vol_down", "Vol −", true),
            Item(MenuIds.VOLUME_UP, "vol_up", "Vol +", true),
            Item(MenuIds.ZOOM_DOWN, "zoom_out", "Zoom −", true),
            Item(MenuIds.ZOOM_UP, "zoom_in", "Zoom +", true),
            Item(MenuIds.RECENTER, "center", "Center", false),
            Item(MenuIds.OPEN_SETTINGS, "settings", "Settings", false),
            Item(MenuIds.EXIT, "exit", "Exit", false),
        )
        val gap = 10f
        val width = (page.bitmapWidth - gap * (items.size + 1)) / items.size
        val top = 140f
        val height = page.bitmapHeight - top - 24f
        val fraction = if (state.durationMs > 0) {
            (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
        } else 0f
        val seekLabel = if (state.durationMs > 0) {
            "${formatClock(state.positionMs)} / ${formatClock(state.durationMs)}"
        } else formatClock(state.positionMs)
        val seek = MenuButton(
            MenuIds.SEEK, seekLabel, RectF(40f, 24f, 1150f, 110f), progress = fraction,
        )
        val buttons = mutableListOf(seek)
        buttons += items.mapIndexed { index, item ->
            val left = gap + index * (width + gap)
            MenuButton(
                item.id, item.label, RectF(left, top, left + width, top + height),
                item.repeatable, icon = item.icon,
            )
        }
        buttons += MenuButton(
            MenuIds.MOVE_DOWN, "Lower", RectF(1360f, 24f, 1440f, 110f), true, icon = "down",
        )
        buttons += MenuButton(
            MenuIds.MOVE_UP, "Raise", RectF(1450f, 24f, 1520f, 110f), true, icon = "up",
        )
        val texts = listOf(
            MenuText("Zoom ${state.settings.screenSizePct}%", 1180f, 80f, 38f, Paint.Align.LEFT),
        )
        return MenuModel(page, buttons, texts)
    }

    fun settingsPage(state: MenuState): MenuModel {
        val s = state.settings
        val page = MenuPage.SETTINGS
        val buttons = mutableListOf<MenuButton>()
        val texts = mutableListOf<MenuText>()

        texts += MenuText("Settings", 30f, 62f, 48f)
        val hint = if (s.dwellClick) {
            "Look at a button for ${s.dwellSeconds} s to click it"
        } else {
            "Put the dot on a button and click"
        }
        texts += MenuText(hint, page.bitmapWidth - 30f, 62f, 28f, Paint.Align.RIGHT, dim = true)

        fun cellOrigin(column: Int, row: Int) = Pair(if (column == 0) 24f else 780f, 90f + row * 88f)

        fun stepper(
            column: Int, row: Int, label: String, value: String, downId: String, upId: String,
        ) {
            val (x0, y0) = cellOrigin(column, row)
            texts += MenuText(label, x0 + 8f, y0 + 52f, 34f)
            buttons += MenuButton(downId, "−", RectF(x0 + 340f, y0 + 4f, x0 + 424f, y0 + 76f), true)
            texts += MenuText(value, x0 + 494f, y0 + 52f, 34f, Paint.Align.CENTER)
            buttons += MenuButton(upId, "+", RectF(x0 + 564f, y0 + 4f, x0 + 648f, y0 + 76f), true)
        }

        fun cycle(column: Int, row: Int, label: String, value: String, id: String) {
            val (x0, y0) = cellOrigin(column, row)
            texts += MenuText(label, x0 + 8f, y0 + 52f, 34f)
            buttons += MenuButton(id, value, RectF(x0 + 340f, y0 + 4f, x0 + 648f, y0 + 76f))
        }

        fun pct(v: Int) = "$v%"

        // Left column
        stepper(0, 0, "Zoom", pct(s.screenSizePct), MenuIds.ZOOM_DOWN, MenuIds.ZOOM_UP)
        stepper(
            0, 1, "Distance", String.format(Locale.US, "%.1f m", s.screenDistanceDm / 10f),
            MenuIds.DISTANCE_DOWN, MenuIds.DISTANCE_UP,
        )
        stepper(0, 2, "Height", "${s.screenHeightDeg}°", MenuIds.HEIGHT_DOWN, MenuIds.HEIGHT_UP)
        stepper(0, 3, "Brightness", pct(s.brightnessPct), MenuIds.BRIGHTNESS_DOWN, MenuIds.BRIGHTNESS_UP)
        stepper(0, 4, "Contrast", pct(s.contrastPct), MenuIds.CONTRAST_DOWN, MenuIds.CONTRAST_UP)
        stepper(
            0, 5, "Caption position", pct(s.captionOffsetPct),
            MenuIds.CAPTION_POSITION_DOWN, MenuIds.CAPTION_POSITION_UP,
        )

        // Right column
        stepper(1, 0, "Eye distance", "${s.eyeShift}", MenuIds.EYE_DOWN, MenuIds.EYE_UP)
        stepper(
            1, 1, "Speed", String.format(Locale.US, "%.2f×", s.playbackSpeed),
            MenuIds.SPEED_DOWN, MenuIds.SPEED_UP,
        )
        cycle(1, 2, "Captions", if (s.captionsEnabled) "On" else "Off", MenuIds.CAPTIONS)
        stepper(1, 3, "Caption size", pct(s.captionSizePct), MenuIds.CAPTION_SIZE_DOWN, MenuIds.CAPTION_SIZE_UP)
        cycle(1, 4, "Subtitles", state.subtitleLabel, MenuIds.SUBTITLE_TRACK)
        cycle(1, 5, "Audio", state.audioLabel, MenuIds.AUDIO_TRACK)
        stepper(0, 6, "Pointer fix", pct(s.pointerFixPct), MenuIds.POINTER_FIX_DOWN, MenuIds.POINTER_FIX_UP)
        cycle(0, 7, "Mouse pointer", "Calibrate", MenuIds.CALIBRATE)
        cycle(1, 7, "Calibration", if (s.pointerCal != null) "Reset (saved)" else "Reset", MenuIds.CALIBRATE_RESET)
        stepper(1, 6, "Bar position", "${s.menuOffsetDeg}°", MenuIds.MENU_POS_DOWN, MenuIds.MENU_POS_UP)

        buttons += MenuButton(MenuIds.BACK, "Back", RectF(24f, 802f, 384f, 882f), icon = "back")
        buttons += MenuButton(MenuIds.EXIT, "Exit", RectF(400f, 802f, 760f, 882f), icon = "exit")
        buttons += MenuButton(
            MenuIds.GAZE_TOGGLE, "Look-click: " + if (s.dwellClick) "On" else "Off",
            RectF(776f, 802f, 1100f, 882f),
        )
        buttons += MenuButton(
            MenuIds.GAZE_TIME, "Look time: ${s.dwellSeconds}s", RectF(1116f, 802f, 1440f, 882f),
        )

        return MenuModel(page, buttons, texts)
    }
}

object MenuPainter {

    fun draw(canvas: Canvas, model: MenuModel, hoveredId: String?) {
        val page = model.page
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val text = Paint(Paint.ANTI_ALIAS_FLAG)

        fill.color = Color.argb(215, 12, 12, 16)
        canvas.drawRoundRect(
            RectF(0f, 0f, page.bitmapWidth.toFloat(), page.bitmapHeight.toFloat()), 36f, 36f, fill,
        )

        for (t in model.texts) {
            text.textSize = t.size
            text.textAlign = t.align
            text.color = if (t.dim) Color.argb(200, 190, 190, 190) else Color.WHITE
            canvas.drawText(t.text, t.x, t.y, text)
        }

        val baseSize = if (page == MenuPage.BAR) 46f else 38f
        for (b in model.buttons) {
            fill.color = if (b.id == hoveredId) Color.rgb(46, 125, 255) else Color.rgb(64, 66, 74)
            if (b.progress != null) {
                fill.color = if (b.id == hoveredId) Color.rgb(70, 74, 90) else Color.rgb(54, 56, 64)
                canvas.drawRoundRect(b.rect, 22f, 22f, fill)
                fill.color = Color.rgb(46, 125, 255)
                val filled = RectF(b.rect.left, b.rect.top,
                    b.rect.left + b.rect.width() * b.progress, b.rect.bottom)
                canvas.save()
                canvas.clipRect(filled)
                canvas.drawRoundRect(b.rect, 22f, 22f, fill)
                canvas.restore()
            } else {
                canvas.drawRoundRect(b.rect, 22f, 22f, fill)
            }

            text.color = Color.WHITE
            text.textAlign = Paint.Align.CENTER
            val h = b.rect.height()
            if (b.icon != null && h > 120f) {
                // Tall button: picture on top, small label below.
                MenuIcons.draw(canvas, b.icon, b.rect.centerX(), b.rect.top + h * 0.40f, h * 0.36f)
                text.textSize = 30f
                val maxW = b.rect.width() * 0.92f
                val w = text.measureText(b.label)
                if (w > maxW) text.textSize = 30f * maxW / w
                canvas.drawText(b.label, b.rect.centerX(), b.rect.bottom - 26f, text)
            } else {
                var cx = b.rect.centerX()
                if (b.icon != null) {
                    MenuIcons.draw(canvas, b.icon, b.rect.left + h * 0.75f, b.rect.centerY(), h * 0.5f)
                    cx += h * 0.35f
                }
                text.textSize = baseSize
                val maxWidth = b.rect.width() * 0.9f - (if (b.icon != null) h * 0.7f else 0f)
                val measured = text.measureText(b.label)
                if (measured > maxWidth) {
                    text.textSize = baseSize * min(1f, maxWidth / measured)
                }
                canvas.drawText(b.label, cx, b.rect.centerY() + text.textSize * 0.35f, text)
            }
        }
    }
}

/** Small pictures drawn with shapes, so they do not depend on any font. */
object MenuIcons {

    fun draw(canvas: Canvas, name: String, cx: Float, cy: Float, size: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        val r = size / 2f
        val stroke = size * 0.11f
        fun fillStyle() { p.style = Paint.Style.FILL }
        fun lineStyle() { p.style = Paint.Style.STROKE; p.strokeWidth = stroke }
        fun triangle(x: Float, y: Float, w: Float, h: Float, pointingRight: Boolean) {
            val path = Path()
            if (pointingRight) {
                path.moveTo(x, y - h / 2); path.lineTo(x + w, y); path.lineTo(x, y + h / 2)
            } else {
                path.moveTo(x, y - h / 2); path.lineTo(x - w, y); path.lineTo(x, y + h / 2)
            }
            path.close()
            canvas.drawPath(path, p)
        }
        fun speaker() {
            fillStyle()
            val body = Path()
            body.moveTo(cx - r, cy - r * 0.35f)
            body.lineTo(cx - r * 0.5f, cy - r * 0.35f)
            body.lineTo(cx, cy - r * 0.8f)
            body.lineTo(cx, cy + r * 0.8f)
            body.lineTo(cx - r * 0.5f, cy + r * 0.35f)
            body.lineTo(cx - r, cy + r * 0.35f)
            body.close()
            canvas.drawPath(body, p)
        }
        fun plusMinus(x: Float, y: Float, half: Float, plus: Boolean) {
            lineStyle()
            canvas.drawLine(x - half, y, x + half, y, p)
            if (plus) canvas.drawLine(x, y - half, x, y + half, p)
        }
        when (name) {
            "play" -> { fillStyle(); triangle(cx - r * 0.55f, cy, r * 1.5f, r * 1.7f, true) }
            "pause" -> {
                fillStyle()
                canvas.drawRoundRect(RectF(cx - r * 0.7f, cy - r * 0.8f, cx - r * 0.2f, cy + r * 0.8f), 4f, 4f, p)
                canvas.drawRoundRect(RectF(cx + r * 0.2f, cy - r * 0.8f, cx + r * 0.7f, cy + r * 0.8f), 4f, 4f, p)
            }
            "back" -> {
                fillStyle()
                triangle(cx + r * 0.05f, cy, r * 0.95f, r * 1.3f, false)
                triangle(cx + r * 0.95f, cy, r * 0.95f, r * 1.3f, false)
            }
            "forward" -> {
                fillStyle()
                triangle(cx - r * 0.95f, cy, r * 0.95f, r * 1.3f, true)
                triangle(cx - r * 0.05f, cy, r * 0.95f, r * 1.3f, true)
            }
            "vol_down", "vol_up" -> {
                speaker()
                plusMinus(cx + r * 0.65f, cy, r * 0.32f, name == "vol_up")
            }
            "zoom_in", "zoom_out" -> {
                lineStyle()
                canvas.drawCircle(cx - r * 0.1f, cy - r * 0.1f, r * 0.7f, p)
                canvas.drawLine(cx + r * 0.42f, cy + r * 0.42f, cx + r * 0.95f, cy + r * 0.95f, p)
                plusMinus(cx - r * 0.1f, cy - r * 0.1f, r * 0.33f, name == "zoom_in")
            }
            "center" -> {
                lineStyle()
                canvas.drawCircle(cx, cy, r * 0.5f, p)
                canvas.drawLine(cx, cy - r, cx, cy - r * 0.5f, p)
                canvas.drawLine(cx, cy + r * 0.5f, cx, cy + r, p)
                canvas.drawLine(cx - r, cy, cx - r * 0.5f, cy, p)
                canvas.drawLine(cx + r * 0.5f, cy, cx + r, cy, p)
            }
            "settings" -> {
                lineStyle()
                val ys = floatArrayOf(cy - r * 0.6f, cy, cy + r * 0.6f)
                val knobs = floatArrayOf(cx - r * 0.35f, cx + r * 0.4f, cx - r * 0.1f)
                for (i in 0..2) {
                    lineStyle()
                    canvas.drawLine(cx - r, ys[i], cx + r, ys[i], p)
                    fillStyle()
                    canvas.drawCircle(knobs[i], ys[i], r * 0.22f, p)
                }
            }
            "down", "up" -> {
                fillStyle()
                val sign = if (name == "down") 1f else -1f
                val path = Path()
                path.moveTo(cx - r * 0.8f, cy - sign * r * 0.45f)
                path.lineTo(cx + r * 0.8f, cy - sign * r * 0.45f)
                path.lineTo(cx, cy + sign * r * 0.55f)
                path.close()
                canvas.drawPath(path, p)
            }
            "exit" -> {
                lineStyle()
                canvas.drawLine(cx - r * 0.65f, cy - r * 0.65f, cx + r * 0.65f, cy + r * 0.65f, p)
                canvas.drawLine(cx - r * 0.65f, cy + r * 0.65f, cx + r * 0.65f, cy - r * 0.65f, p)
            }
        }
    }
}
