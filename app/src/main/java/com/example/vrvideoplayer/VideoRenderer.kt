package com.example.vrvideoplayer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.Surface
import com.google.cardboard.sdk.CardboardView
import com.google.cardboard.sdk.HeadTransform
import com.google.cardboard.sdk.Viewport
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** What the status bar in the headset shows. */
data class HudState(
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** A short message such as "Recentered", or null. */
    val note: String?,
)

/**
 * Draws a video into a Cardboard headset.
 *
 * How it works:
 *  1. When the GL surface is created we make an OpenGL "external" texture and wrap it in a
 *     [SurfaceTexture] / [Surface]. The Surface is handed to the video player
 *     ([onVideoSurfaceReady]), so every decoded video frame lands in that texture.
 *  2. [CardboardView] calls [onNewFrame] once per frame (we pull in the newest video frame and
 *     read the head orientation) and then [onDrawEye] once per eye. The Cardboard SDK supplies
 *     the per-eye view and projection matrices and applies the lens-distortion correction after
 *     we are done, so all we do is draw the scene normally.
 *  3. The scene is either a flat "cinema screen" quad ([VideoMode.FLAT_2D], [VideoMode.FLAT_SBS_3D]
 *     and [VideoMode.FLAT_TB_3D]) or a sphere around the viewer ([VideoMode.SPHERE_360]). Captions
 *     and the status bar are drawn on top as small flat pictures ([TextOverlay]).
 *
 * Threading: [onNewFrame], [onDrawEye] and [onSurfaceCreated] run on the GL thread. [recenter],
 * [setVideoAspect], [setCaptionText], [setHud] and [release] may be called from any thread.
 */
class VideoRenderer(
    private val mode: VideoMode,
    initialSettings: VrSettings,
    /** Called on the GL thread when a Surface for the player to render into is available. */
    private val onVideoSurfaceReady: (Surface) -> Unit,
    /** Called on the GL thread when an in-headset button is clicked by looking at it. */
    private val onMenuAction: (String, Float) -> Unit,
    /** Called on the GL thread with the button under the mouse pointer after [clickAtPointer]. */
    private val onMenuPick: (String?, Float) -> Unit,
    /**
     * Mouse calibration progress, on the GL thread: the number of dots already clicked, and the
     * result when finished (4 numbers, or an empty array if it failed; null while running).
     */
    private val onCalibration: (Int, FloatArray?) -> Unit,
) : CardboardView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    /** The current settings. Replaced as a whole by [applySettings]. */
    @Volatile
    var settings: VrSettings = initialSettings
        private set

    // ---- State shared between threads ------------------------------------------------------

    /** Width / height of the decoded video frame, with pixel aspect ratio applied. */
    @Volatile
    private var videoAspect = 16f / 9f

    private val recenterRequested = AtomicBoolean(false)
    private val frameAvailable = AtomicBoolean(false)

    /** Guards [surfaceTexture], [surface] and [released]. */
    private val lock = Any()
    private var released = false
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null

    private val captionOverlay = TextOverlay(CAPTION_BITMAP_WIDTH, CAPTION_BITMAP_HEIGHT)
    private val hudOverlay = TextOverlay(HUD_BITMAP_WIDTH, HUD_BITMAP_HEIGHT)
    private val barOverlay = TextOverlay(MenuPage.BAR.bitmapWidth, MenuPage.BAR.bitmapHeight)
    private val settingsOverlay =
        TextOverlay(MenuPage.SETTINGS.bitmapWidth, MenuPage.SETTINGS.bitmapHeight)
    private val reticleOverlay = TextOverlay(RETICLE_BITMAP_SIZE, RETICLE_BITMAP_SIZE)

    @Volatile
    private var lastCaption: String = ""

    // In-headset menu. [menuModel] is set from any thread; the rest is GL-thread only.
    @Volatile
    private var menuModel: MenuModel? = null

    /** True while the control bar should stay up even when you are not looking down. */
    @Volatile
    var menuAlwaysVisible: Boolean = false

    /** The id of the button the dot is on right now, or null. */
    @Volatile
    var hoveredButtonId: String? = null
        private set

    /** Where on the hovered button the dot is, from 0 (left edge) to 1 (right edge). */
    @Volatile
    var hoveredFraction: Float = 0f
        private set

    private val dwellReset = AtomicBoolean(false)

    // The mouse pointer / finger position, as a fraction of the whole view (NaN = not known).
    @Volatile
    private var pointerX = Float.NaN

    @Volatile
    private var pointerY = Float.NaN
    private val pickRequested = AtomicBoolean(false)

    // Mouse calibration (see startCalibration).
    @Volatile
    var calibrating = false
        private set
    private val calStartRequested = AtomicBoolean(false)
    private val calClick = AtomicBoolean(false)
    private val calSeen = ArrayList<FloatArray>()
    private val calTarget = ArrayList<FloatArray>()
    private var calIndex = 0
    private var calReady = false
    private val calOverlay = TextOverlay(RETICLE_BITMAP_SIZE, RETICLE_BITMAP_SIZE)
    private val tanTmp = FloatArray(2)
    private var pointerOnRightEye = false
    private val projLeft = FloatArray(16)
    private val projRight = FloatArray(16)
    private val projMono = FloatArray(16)
    private var sawRight = false
    private val pointerDir = FloatArray(3)
    private var gazeBar = false
    private var menuReady = false
    private var reticleReady = false
    private var drawPage = MenuPage.BAR
    private var drawnModel: MenuModel? = null
    private var drawnHover: String? = null
    private var shownPage: MenuPage? = null
    private var lastHit: String? = null
    private var dwellStartMs = 0L
    private var dwellFired = false
    private var reticleKey = -1
    private val menuRot = FloatArray(16)
    private val axisIn = FloatArray(4)
    private val axisOut = FloatArray(4)
    private val menuCenter = FloatArray(3)
    private val menuNormal = FloatArray(3)
    private val menuRight = FloatArray(3)
    private val menuUp = FloatArray(3)

    // ---- GL-thread-only state -------------------------------------------------------------

    private var videoProgram = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uMvp = 0
    private var uTexMatrix = 0
    private var uUvScale = 0
    private var uUvOffset = 0
    private var uTexture = 0
    private var uBrightness = 0
    private var uContrast = 0
    private var uSaturation = 0

    private var overlayProgram = 0
    private var aOverlayPosition = 0
    private var aOverlayTexCoord = 0
    private var uOverlayMvp = 0
    private var uOverlayTexture = 0

    private var textureId = 0
    private var hasFrame = false
    private var loggedFirstFrame = false
    private var captionReady = false
    private var hudReady = false

    private var quad: Mesh? = null
    private var sphere: Mesh? = null
    private var overlayQuad: Mesh? = null

    private val headView = FloatArray(16)
    private val forward = FloatArray(3)
    private val texMatrix = FloatArray(16)
    private val group = FloatArray(16)
    private val model = FloatArray(16)
    private val modelView = FloatArray(16)
    private val mvp = FloatArray(16)
    private val viewRotationOnly = FloatArray(16)
    private val adjust = FloatArray(16)
    private val projection = FloatArray(16)

    /** Direction the screen / the front of the 360 video faces, set by [recenter]. */
    private var anchorYawDeg = 0f
    private var anchorPitchDeg = 0f

    init {
        Matrix.setIdentityM(texMatrix, 0)
    }

    // ---- Public API -------------------------------------------------------------------------

    /** Tells the renderer the shape of the video (decoded width * pixel ratio / height). */
    fun setVideoAspect(aspect: Float) {
        if (aspect.isFinite() && aspect > 0.05f) {
            videoAspect = aspect
        }
    }

    /**
     * Moves the screen (or the front of the 360 video) to where the viewer is looking right now.
     * Applied on the next frame.
     */
    fun recenter(yawOnly: Boolean = false) {
        recenterYawOnly = yawOnly
        recenterRequested.set(true)
    }

    @Volatile
    private var recenterYawOnly = false

    /** Replaces the settings (used when they are changed from the in-headset menu). */
    fun applySettings(newSettings: VrSettings) {
        settings = newSettings
        setCaptionText(lastCaption)
    }

    /** Sets what the in-headset menu shows; null hides it. */
    fun setMenu(model: MenuModel?) {
        menuModel = model
    }

    /** Tells the renderer where the mouse pointer or finger is (0..1 across the whole view). */
    fun setPointer(x: Float, y: Float) {
        pointerX = x
        pointerY = y
    }

    /**
     * Starts the mouse calibration: dots appear one after the other; put the mouse pointer on
     * each one and click. From the clicks the lens warp is measured exactly for this headset.
     */
    fun startCalibration() {
        calStartRequested.set(true)
    }

    /** A click while calibrating: records where the pointer was. */
    fun calibrationClick() {
        calClick.set(true)
    }

    /** Asks which button is under the pointer; the answer arrives through onMenuPick. */
    fun clickAtPointer() {
        pickRequested.set(true)
    }

    /** Ignore the current look until the dot leaves the button (after a mouse click, say). */
    fun cancelDwell() {
        dwellReset.set(true)
    }

    /** Shows [text] as a caption, or hides the caption when [text] is null or blank. */
    fun setCaptionText(text: CharSequence?) {
        val content = text?.toString()?.trim().orEmpty()
        lastCaption = content
        if (content.isEmpty() || !settings.captionsEnabled) {
            captionOverlay.setDrawer(null)
        } else {
            captionOverlay.setDrawer { canvas, w, h -> drawCaption(canvas, w, h, content) }
        }
    }

    /** Shows the status bar with [state], or hides it when [state] is null. */
    fun setHud(state: HudState?) {
        if (state == null) {
            hudOverlay.setDrawer(null)
        } else {
            hudOverlay.setDrawer { canvas, w, h -> drawHud(canvas, w, h, state) }
        }
    }

    /** Releases the video surface. Call once from the activity's onDestroy. */
    fun release() {
        synchronized(lock) {
            released = true
            surface?.release()
            surface = null
            surfaceTexture?.release()
            surfaceTexture = null
        }
    }

    // ---- SurfaceTexture.OnFrameAvailableListener (any thread) --------------------------------

    override fun onFrameAvailable(texture: SurfaceTexture) {
        frameAvailable.set(true)
    }

    // ---- CardboardView.Renderer (GL thread) --------------------------------------------------

    override fun onSurfaceCreated(config: EGLConfig) {
        Log.i(TAG, "onSurfaceCreated (GL surface is ready)")
        videoProgram = buildProgram(VIDEO_VERTEX_SHADER, VIDEO_FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(videoProgram, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(videoProgram, "aTexCoord")
        uMvp = GLES20.glGetUniformLocation(videoProgram, "uMvp")
        uTexMatrix = GLES20.glGetUniformLocation(videoProgram, "uTexMatrix")
        uUvScale = GLES20.glGetUniformLocation(videoProgram, "uUvScale")
        uUvOffset = GLES20.glGetUniformLocation(videoProgram, "uUvOffset")
        uTexture = GLES20.glGetUniformLocation(videoProgram, "uTexture")
        uBrightness = GLES20.glGetUniformLocation(videoProgram, "uBrightness")
        uContrast = GLES20.glGetUniformLocation(videoProgram, "uContrast")
        uSaturation = GLES20.glGetUniformLocation(videoProgram, "uSaturation")

        overlayProgram = buildProgram(OVERLAY_VERTEX_SHADER, OVERLAY_FRAGMENT_SHADER)
        aOverlayPosition = GLES20.glGetAttribLocation(overlayProgram, "aPosition")
        aOverlayTexCoord = GLES20.glGetAttribLocation(overlayProgram, "aTexCoord")
        uOverlayMvp = GLES20.glGetUniformLocation(overlayProgram, "uMvp")
        uOverlayTexture = GLES20.glGetUniformLocation(overlayProgram, "uTexture")

        quad = createQuad(flipV = false)
        overlayQuad = createQuad(flipV = true) // bitmaps are stored top row first
        sphere = createSphere()

        captionOverlay.onGlCreated()
        hudOverlay.onGlCreated()
        barOverlay.onGlCreated()
        settingsOverlay.onGlCreated()
        reticleOverlay.onGlCreated()
        calOverlay.onGlCreated()
        drawnModel = null
        reticleKey = -1

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        checkGlError("create video texture")

        hasFrame = false
        frameAvailable.set(false)

        val newTexture = SurfaceTexture(textureId)
        newTexture.setOnFrameAvailableListener(this)
        val newSurface = Surface(newTexture)

        synchronized(lock) {
            if (released) {
                newSurface.release()
                newTexture.release()
                return
            }
            // On a re-created GL context the old SurfaceTexture belongs to the dead context.
            surface?.release()
            surfaceTexture?.release()
            surfaceTexture = newTexture
            surface = newSurface
        }
        onVideoSurfaceReady(newSurface)
    }

    override fun onSurfaceChanged(width: Int, height: Int) {
        Log.i(TAG, "onSurfaceChanged ${width}x$height")
    }

    override fun onNewFrame(headTransform: HeadTransform) {
        headTransform.getHeadView(headView, 0)

        headTransform.getForwardVector(forward, 0)
        if (recenterRequested.getAndSet(false)) {
            // Turn the direction the head is facing into a yaw (left/right) and pitch (up/down).
            anchorYawDeg = Math.toDegrees(atan2(-forward[0].toDouble(), -forward[2].toDouble())).toFloat()
            if (!recenterYawOnly) {
                anchorPitchDeg =
                    Math.toDegrees(asin(forward[1].coerceIn(-1f, 1f).toDouble())).toFloat()
            }
        }

        // Pull the newest decoded video frame into the texture (must happen on the GL thread).
        synchronized(lock) {
            val texture = surfaceTexture
            if (!released && texture != null && frameAvailable.getAndSet(false)) {
                try {
                    texture.updateTexImage()
                    texture.getTransformMatrix(texMatrix)
                    hasFrame = true
                    if (!loggedFirstFrame) {
                        loggedFirstFrame = true
                        Log.i(TAG, "first video frame received")
                    }
                } catch (e: RuntimeException) {
                    Log.w(TAG, "updateTexImage failed", e)
                }
            }
        }

        captionReady = captionOverlay.prepare()
        hudReady = hudOverlay.prepare()
        updateMenu()
    }

    override fun onDrawEye(eye: CardboardView.Eye) {
        // Combine this eye's offset from the head with the head orientation.
        eye.applyHeadView(headView)

        val quadMesh = quad
        val sphereMesh = sphere
        val overlayMesh = overlayQuad
        if (quadMesh == null || sphereMesh == null || overlayMesh == null) {
            return
        }

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        buildProjection(eye)
        when (eye.eyeType) {
            CardboardView.Eye.LEFT -> System.arraycopy(projection, 0, projLeft, 0, 16)
            CardboardView.Eye.RIGHT -> {
                System.arraycopy(projection, 0, projRight, 0, 16)
                sawRight = true
            }
            else -> System.arraycopy(projection, 0, projMono, 0, 16)
        }
        val view = eye.eyeView

        // Placement of the flat screen: its distance, size and tilt come from the settings.
        val distance = settings.screenDistanceDm / 10f
        val sizeScale = (distance / REFERENCE_DISTANCE) * (settings.screenSizePct / 100f)
        var screenWidth = MAX_SCREEN_WIDTH * sizeScale
        var screenHeight = screenWidth / screenAspect()
        val maxHeight = MAX_SCREEN_HEIGHT * sizeScale
        if (screenHeight > maxHeight) {
            screenHeight = maxHeight
            screenWidth = screenHeight * screenAspect()
        }
        Matrix.setIdentityM(group, 0)
        Matrix.rotateM(group, 0, anchorYawDeg, 0f, 1f, 0f)
        Matrix.rotateM(group, 0, anchorPitchDeg + settings.screenHeightDeg, 1f, 0f, 0f)
        Matrix.translateM(group, 0, 0f, 0f, -distance)

        if (hasFrame) {
            drawVideo(eye, view, sphereMesh, quadMesh, screenWidth, screenHeight)
        }

        // Captions and status bar are drawn over the video, blended with its transparency.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        if (captionReady) {
            drawCaptionOverlay(eye, view, overlayMesh, screenWidth, screenHeight)
        }
        if (hudReady) {
            drawHudOverlay(eye, overlayMesh)
        }
        if (menuReady) {
            drawMenu(eye, view, overlayMesh)
        }
        if (reticleReady) {
            drawReticleDot(eye, overlayMesh)
        }
        if (calibrating && calReady) {
            drawCalibrationDot(eye, overlayMesh)
        }
        GLES20.glDisable(GLES20.GL_BLEND)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        checkGlError("onDrawEye")
    }

    override fun onFinishFrame(viewport: Viewport) {
        // Nothing to do.
    }

    override fun onRendererShutdown() {
        // GL resources die together with the GL context; the video surface is released by
        // release(), which the activity calls.
    }

    // ---- Drawing ---------------------------------------------------------------------------------

    /**
     * The perspective matrix for this eye, with the user's adjustments applied:
     *  - the eye image shift (moves the picture sideways in each eye to suit the user's eye
     *    distance), and
     *  - for 360 video, the zoom (a narrower field of view).
     */
    private fun buildProjection(eye: CardboardView.Eye) {
        val base = eye.getPerspective(Z_NEAR, Z_FAR)
        val direction = when (eye.eyeType) {
            CardboardView.Eye.LEFT -> -1f // "further apart" moves the left picture to the left
            CardboardView.Eye.RIGHT -> 1f
            else -> 0f
        }
        Matrix.setIdentityM(adjust, 0)
        if (mode == VideoMode.SPHERE_360) {
            val zoom = settings.screenSizePct / 100f
            adjust[0] = zoom
            adjust[5] = zoom
        }
        adjust[12] = direction * settings.eyeShift * EYE_SHIFT_STEP_NDC
        Matrix.multiplyMM(projection, 0, adjust, 0, base, 0)
    }

    private fun drawVideo(
        eye: CardboardView.Eye,
        view: FloatArray,
        sphereMesh: Mesh,
        quadMesh: Mesh,
        screenWidth: Float,
        screenHeight: Float,
    ) {
        GLES20.glUseProgram(videoProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glUniform1f(uBrightness, settings.brightnessPct / 100f)
        GLES20.glUniform1f(uContrast, settings.contrastPct / 100f)
        GLES20.glUniform1f(uSaturation, settings.saturationPct / 100f)

        // Which part of the video frame does this eye see?
        val isFirstEye = (eye.eyeType != CardboardView.Eye.RIGHT) != settings.swapEyes
        when (mode) {
            VideoMode.FLAT_SBS_3D -> {
                GLES20.glUniform2f(uUvScale, 0.5f, 1f)
                GLES20.glUniform2f(uUvOffset, if (isFirstEye) 0f else 0.5f, 0f)
            }
            VideoMode.FLAT_TB_3D -> {
                GLES20.glUniform2f(uUvScale, 1f, 0.5f)
                GLES20.glUniform2f(uUvOffset, 0f, if (isFirstEye) 0.5f else 0f)
            }
            else -> {
                GLES20.glUniform2f(uUvScale, 1f, 1f)
                GLES20.glUniform2f(uUvOffset, 0f, 0f)
            }
        }

        if (mode == VideoMode.SPHERE_360) {
            // The viewer sits at the centre of the sphere. Drop the (tiny) per-eye translation
            // from the view matrix so the sphere behaves like an infinitely distant backdrop.
            System.arraycopy(view, 0, viewRotationOnly, 0, 16)
            viewRotationOnly[12] = 0f
            viewRotationOnly[13] = 0f
            viewRotationOnly[14] = 0f

            Matrix.setIdentityM(model, 0)
            Matrix.rotateM(model, 0, anchorYawDeg, 0f, 1f, 0f)
            Matrix.multiplyMM(modelView, 0, viewRotationOnly, 0, model, 0)
            Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            sphereMesh.draw(aPosition, aTexCoord)
        } else {
            System.arraycopy(group, 0, model, 0, 16)
            Matrix.scaleM(model, 0, screenWidth, screenHeight, 1f)
            Matrix.multiplyMM(modelView, 0, view, 0, model, 0)
            Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            quadMesh.draw(aPosition, aTexCoord)
        }
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    /**
     * Captions: fixed to the bottom of the screen for flat video, fixed in front of the head
     * for 360 video (which has no screen to attach them to).
     */
    private fun drawCaptionOverlay(
        eye: CardboardView.Eye,
        view: FloatArray,
        overlayMesh: Mesh,
        screenWidth: Float,
        screenHeight: Float,
    ) {
        val offset = settings.captionOffsetPct / 100f
        val distance = settings.screenDistanceDm / 10f
        if (mode == VideoMode.SPHERE_360) {
            val width = HEAD_CAPTION_WIDTH
            val height = width / captionOverlay.aspect
            val centerY = HEAD_CAPTION_BOTTOM + height / 2f + offset
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, 0f, centerY, -HEAD_OVERLAY_DISTANCE)
            Matrix.scaleM(model, 0, width, height, 1f)
            // Head-locked: only the eye's own offset, not the head turning.
            Matrix.multiplyMM(modelView, 0, eye.eyeFromHead, 0, model, 0)
        } else {
            // With a very big screen (zoom up to 400%) the real screen edge is far outside your
            // view, so the caption is kept inside the part of the screen that you can see.
            val width = min(screenWidth * CAPTION_WIDTH_FRACTION, distance * CAPTION_MAX_WIDTH_PER_METER)
            val height = width / captionOverlay.aspect
            val visibleHeight = min(screenHeight, distance * CAPTION_VISIBLE_HEIGHT_PER_METER)
            val bottom = max(-screenHeight / 2f, -distance * CAPTION_MAX_DROP_PER_METER)
            // The caption text sits at the bottom of its picture, so the picture's bottom edge
            // is placed just above the bottom edge of the visible screen.
            val centerY = bottom + height / 2f + visibleHeight * (CAPTION_MARGIN + offset)
            System.arraycopy(group, 0, model, 0, 16)
            Matrix.translateM(model, 0, 0f, centerY, 0.01f)
            Matrix.scaleM(model, 0, width, height, 1f)
            Matrix.multiplyMM(modelView, 0, view, 0, model, 0)
        }
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)
        drawOverlay(captionOverlay, overlayMesh)
    }

    /** The status bar: always in front of the head, low in the field of view. */
    private fun drawHudOverlay(eye: CardboardView.Eye, overlayMesh: Mesh) {
        val width = HUD_WIDTH
        val height = width / hudOverlay.aspect
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, 0f, HUD_CENTER_Y, -HEAD_OVERLAY_DISTANCE)
        Matrix.scaleM(model, 0, width, height, 1f)
        Matrix.multiplyMM(modelView, 0, eye.eyeFromHead, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)
        drawOverlay(hudOverlay, overlayMesh)
    }

    private fun drawOverlay(overlay: TextOverlay, mesh: Mesh) {
        GLES20.glUseProgram(overlayProgram)
        overlay.bind()
        GLES20.glUniform1i(uOverlayTexture, 0)
        GLES20.glUniformMatrix4fv(uOverlayMvp, 1, false, mvp, 0)
        mesh.draw(aOverlayPosition, aOverlayTexCoord)
    }

    // ---- In-headset menu and the dot ------------------------------------------------------------

    /**
     * GL thread, once per frame: finds the button the dot is on, handles looking-to-click, and
     * prepares the pictures for the menu and the dot.
     */
    private fun updateMenu() {
        if (calStartRequested.getAndSet(false)) {
            calibrating = true
            calIndex = 0
            calSeen.clear()
            calTarget.clear()
            calClick.set(false)
            calOverlay.setDrawer { canvas, w, h -> drawReticle(canvas, w, h, 0f, false) }
            onCalibration(0, null)
        }
        if (calibrating) {
            updateCalibration()
            menuReady = false
            reticleReady = false
            hoveredButtonId = null
            calReady = calOverlay.prepare()
            return
        }
        calReady = false
        val now = SystemClock.uptimeMillis()
        val current = settings
        val menu = menuModel

        // Show the control bar when looking down (with some hysteresis so it does not flicker).
        val pitch = Math.toDegrees(asin(forward[1].coerceIn(-1f, 1f).toDouble())).toFloat()
        // The bar appears when you look down to within 15 degrees of its middle (so moving the
        // bar lower also moves the point where it appears) and goes away again when you look up.
        val barPitch = MenuPage.BAR.pitchDeg + current.menuOffsetDeg
        if (pitch < barPitch + BAR_SHOW_ABOVE_DEG) {
            gazeBar = true
        } else if (pitch > barPitch + BAR_HIDE_ABOVE_DEG) {
            gazeBar = false
        }
        val visible = menu != null &&
            (menu.page == MenuPage.SETTINGS || menuAlwaysVisible || gazeBar)

        // With look-click on, the dot (straight ahead) points; with it off, the mouse pointer does.
        val ray: FloatArray? = when {
            current.dwellClick -> forward
            pointerRay(pointerDir) -> pointerDir
            else -> null
        }
        val hit = if (menu != null && visible && ray != null) hitTest(menu, ray) else null
        hoveredButtonId = hit
        if (pickRequested.getAndSet(false)) {
            onMenuPick(hit, hoveredFraction)
        }

        // When the page changes, require the dot to leave first so nothing is clicked by accident.
        if (menu?.page != shownPage) {
            shownPage = menu?.page
            lastHit = hit
            dwellStartMs = now
            dwellFired = true
        }
        if (dwellReset.getAndSet(false)) {
            lastHit = hit
            dwellStartMs = now
            dwellFired = true
        }
        if (hit != lastHit) {
            lastHit = hit
            dwellStartMs = now
            dwellFired = false
        }

        var progress = 0f
        if (hit != null && current.dwellClick && !dwellFired && menu != null) {
            val needed = current.dwellSeconds.coerceAtLeast(1) * 1000L
            val elapsed = now - dwellStartMs
            progress = (elapsed.toFloat() / needed).coerceIn(0f, 1f)
            if (elapsed >= needed) {
                val button = menu.buttons.firstOrNull { it.id == hit }
                onMenuAction(hit, hoveredFraction)
                if (button != null && button.repeatable) {
                    dwellStartMs = now - needed + REPEAT_INTERVAL_MS
                } else {
                    dwellFired = true
                }
                progress = 0f
            }
        }

        // Menu picture (redrawn only when something changed).
        if (menu != null) {
            val overlay = if (menu.page == MenuPage.SETTINGS) settingsOverlay else barOverlay
            if (menu !== drawnModel || hit != drawnHover) {
                overlay.setDrawer { canvas, _, _ -> MenuPainter.draw(canvas, menu, hit) }
                drawnModel = menu
                drawnHover = hit
            }
            drawPage = menu.page
            activeMenuOverlay = overlay
            menuReady = visible && overlay.prepare()
        } else {
            menuReady = false
        }

        // The dot, with a ring that fills while you look at a button.
        if (current.showDot) {
            val key = (progress * RETICLE_STEPS).toInt() * 2 + (if (hit != null) 1 else 0)
            if (key != reticleKey) {
                reticleKey = key
                val hovering = hit != null
                val shown = progress
                reticleOverlay.setDrawer { canvas, w, h -> drawReticle(canvas, w, h, shown, hovering) }
            }
            reticleReady = reticleOverlay.prepare()
        } else {
            reticleReady = false
        }
    }

    private var activeMenuOverlay: TextOverlay = barOverlay

    /** Sets [out] to the menu's rotation matrix applied to (x, y, z), without translation. */
    private fun rotateAxis(x: Float, y: Float, z: Float, out: FloatArray) {
        axisIn[0] = x
        axisIn[1] = y
        axisIn[2] = z
        axisIn[3] = 0f
        Matrix.multiplyMV(axisOut, 0, menuRot, 0, axisIn, 0)
        out[0] = axisOut[0]
        out[1] = axisOut[1]
        out[2] = axisOut[2]
    }

    private fun updateCalibration() {
        if (!calClick.getAndSet(false)) {
            return
        }
        if (!pointerTan(tanTmp)) {
            return
        }
        // Everything is stored as seen by the left eye (the right eye is the mirror image).
        val mirror = if (pointerOnRightEye) -1f else 1f
        val target = CAL_TARGETS[calIndex]
        calSeen.add(floatArrayOf(tanTmp[0] * mirror, tanTmp[1]))
        calTarget.add(floatArrayOf(target[0] * mirror, target[1]))
        calIndex++
        if (calIndex >= CAL_TARGETS.size) {
            val result = fitCalibration()
            calibrating = false
            onCalibration(calIndex, result ?: FloatArray(0))
        } else {
            onCalibration(calIndex, null)
        }
    }

    /**
     * Finds k1, k2 and an offset so that  target = seen * (1 + k1 r^2 + k2 r^4) + offset  fits
     * all clicks best (least squares). Returns null if it cannot be solved.
     */
    private fun fitCalibration(): FloatArray? {
        val ata = Array(4) { DoubleArray(5) }
        fun add(row: DoubleArray, b: Double) {
            for (i in 0 until 4) {
                for (j in 0 until 4) {
                    ata[i][j] += row[i] * row[j]
                }
                ata[i][4] += row[i] * b
            }
        }
        for (i in calSeen.indices) {
            val qx = calSeen[i][0].toDouble()
            val qy = calSeen[i][1].toDouble()
            val tx = calTarget[i][0].toDouble()
            val ty = calTarget[i][1].toDouble()
            val r2 = qx * qx + qy * qy
            val r4 = r2 * r2
            add(doubleArrayOf(qx * r2, qx * r4, 1.0, 0.0), tx - qx)
            add(doubleArrayOf(qy * r2, qy * r4, 0.0, 1.0), ty - qy)
        }
        for (col in 0 until 4) {
            var pivot = col
            for (row in col + 1 until 4) {
                if (kotlin.math.abs(ata[row][col]) > kotlin.math.abs(ata[pivot][col])) pivot = row
            }
            if (kotlin.math.abs(ata[pivot][col]) < 1e-9) {
                return null
            }
            val swap = ata[col]
            ata[col] = ata[pivot]
            ata[pivot] = swap
            for (row in 0 until 4) {
                if (row != col) {
                    val factor = ata[row][col] / ata[col][col]
                    for (k in col until 5) {
                        ata[row][k] -= factor * ata[col][k]
                    }
                }
            }
        }
        val result = FloatArray(4) { (ata[it][4] / ata[it][it]).toFloat() }
        for (v in result) {
            if (v.isNaN() || kotlin.math.abs(v) > 6f) {
                return null
            }
        }
        Log.i(TAG, "pointer calibration: ${result.joinToString()}")
        return result
    }

    private fun drawCalibrationDot(eye: CardboardView.Eye, overlayMesh: Mesh) {
        val target = CAL_TARGETS[calIndex.coerceAtMost(CAL_TARGETS.size - 1)]
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, target[0] * CAL_DISTANCE, target[1] * CAL_DISTANCE, -CAL_DISTANCE)
        Matrix.scaleM(model, 0, CAL_SIZE, CAL_SIZE, 1f)
        Matrix.multiplyMM(modelView, 0, eye.eyeFromHead, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)
        drawOverlay(calOverlay, overlayMesh)
    }

    /**
     * The pointer position as an angle (tangent) seen through the lens, before any correction.
     * Returns false if the pointer is not known yet.
     */
    private fun pointerTan(out: FloatArray): Boolean {
        val nx = pointerX
        val ny = pointerY
        if (nx.isNaN() || ny.isNaN()) {
            return false
        }
        val proj: FloatArray
        val ex: Float
        pointerOnRightEye = false
        if (sawRight) {
            if (nx < 0.5f) {
                proj = projLeft
                ex = nx * 2f
            } else {
                proj = projRight
                ex = (nx - 0.5f) * 2f
                pointerOnRightEye = true
            }
        } else {
            proj = projMono
            ex = nx
        }
        if (proj[0] == 0f || proj[5] == 0f) {
            return false
        }
        out[0] = (ex * 2f - 1f + proj[8]) / proj[0]
        out[1] = (1f - 2f * ny + proj[9]) / proj[5]
        return true
    }

    /** Applies the lens-warp correction (measured by the calibration, or a typical one). */
    private fun correctTan(q: FloatArray) {
        val mirror = if (pointerOnRightEye) -1f else 1f
        var x = q[0] * mirror
        var y = q[1]
        val r2 = x * x + y * y
        val strength = settings.pointerFixPct / 100f
        val cal = settings.pointerCal
        if (cal != null) {
            val scale = 1f + strength * (cal[0] * r2 + cal[1] * r2 * r2)
            x = x * scale + cal[2]
            y = y * scale + cal[3]
        } else {
            val scale = 1f + strength * (POINTER_K1 * r2 + POINTER_K2 * r2 * r2)
            x *= scale
            y *= scale
        }
        q[0] = x * mirror
        q[1] = y
    }

    /**
     * Turns the pointer position into a direction in the world (the same space as [forward]),
     * using the projection of the eye under the pointer. The lens distortion is ignored, so the
     * result is approximate, most of all near the edges. Returns false if not known yet.
     */
    private fun pointerRay(out: FloatArray): Boolean {
        if (!pointerTan(tanTmp)) {
            return false
        }
        correctTan(tanTmp)
        val dx = tanTmp[0]
        val dy = tanTmp[1]
        val dz = -1f
        val wx = headView[0] * dx + headView[1] * dy + headView[2] * dz
        val wy = headView[4] * dx + headView[5] * dy + headView[6] * dz
        val wz = headView[8] * dx + headView[9] * dy + headView[10] * dz
        val length = kotlin.math.sqrt(wx * wx + wy * wy + wz * wz)
        if (length < 1e-6f) {
            return false
        }
        out[0] = wx / length
        out[1] = wy / length
        out[2] = wz / length
        return true
    }

    /** Which button of [menu] does the line of sight (the dot) pass through? */
    private fun hitTest(menu: MenuModel, ray: FloatArray): String? {
        val page = menu.page
        Matrix.setIdentityM(menuRot, 0)
        Matrix.rotateM(menuRot, 0, anchorYawDeg, 0f, 1f, 0f)
        Matrix.rotateM(menuRot, 0, pageTilt(page), 1f, 0f, 0f)
        rotateAxis(0f, 0f, -page.distance, menuCenter)
        rotateAxis(0f, 0f, 1f, menuNormal)
        rotateAxis(1f, 0f, 0f, menuRight)
        rotateAxis(0f, 1f, 0f, menuUp)

        val facing = ray[0] * menuNormal[0] + ray[1] * menuNormal[1] + ray[2] * menuNormal[2]
        if (facing > -0.05f) {
            return null // looking away from the menu
        }
        val centerAlong = menuCenter[0] * menuNormal[0] + menuCenter[1] * menuNormal[1] +
            menuCenter[2] * menuNormal[2]
        val t = centerAlong / facing
        if (t <= 0f) {
            return null
        }
        val dx = ray[0] * t - menuCenter[0]
        val dy = ray[1] * t - menuCenter[1]
        val dz = ray[2] * t - menuCenter[2]
        val x = dx * menuRight[0] + dy * menuRight[1] + dz * menuRight[2]
        val y = dx * menuUp[0] + dy * menuUp[1] + dz * menuUp[2]
        val px = (x / page.widthMeters + 0.5f) * page.bitmapWidth
        val py = (0.5f - y / page.heightMeters) * page.bitmapHeight
        for (button in menu.buttons) {
            val r = button.rect
            if (px >= r.left - HIT_MARGIN_PX && px <= r.right + HIT_MARGIN_PX &&
                py >= r.top - HIT_MARGIN_PX && py <= r.bottom + HIT_MARGIN_PX
            ) {
                hoveredFraction = ((px - r.left) / r.width()).coerceIn(0f, 1f)
                return button.id
            }
        }
        return null
    }

    /** Tilt of a menu page in degrees; the control bar can be moved by the user. */
    private fun pageTilt(page: MenuPage): Float =
        if (page == MenuPage.BAR) page.pitchDeg + settings.menuOffsetDeg else page.pitchDeg

    /** The menu is fixed in the world, in the direction the screen faces, and below eye level. */
    private fun drawMenu(eye: CardboardView.Eye, view: FloatArray, overlayMesh: Mesh) {
        val page = drawPage
        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, anchorYawDeg, 0f, 1f, 0f)
        Matrix.rotateM(model, 0, pageTilt(page), 1f, 0f, 0f)
        Matrix.translateM(model, 0, 0f, 0f, -page.distance)
        Matrix.scaleM(model, 0, page.widthMeters, page.heightMeters, 1f)
        Matrix.multiplyMM(modelView, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)
        drawOverlay(activeMenuOverlay, overlayMesh)
    }

    /** The dot: always in the middle of the view, in front of the head. */
    private fun drawReticleDot(eye: CardboardView.Eye, overlayMesh: Mesh) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, 0f, 0f, -RETICLE_DISTANCE)
        Matrix.scaleM(model, 0, RETICLE_SIZE, RETICLE_SIZE, 1f)
        Matrix.multiplyMM(modelView, 0, eye.eyeFromHead, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)
        drawOverlay(reticleOverlay, overlayMesh)
    }

    private fun drawReticle(canvas: Canvas, width: Int, height: Int, progress: Float, hovering: Boolean) {
        val cx = width / 2f
        val cy = height / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (hovering) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 8f
            paint.color = Color.argb(110, 255, 255, 255)
            canvas.drawCircle(cx, cy, RETICLE_RING_RADIUS, paint)
            if (progress > 0f) {
                paint.color = Color.rgb(46, 160, 255)
                paint.strokeWidth = 10f
                canvas.drawArc(
                    RectF(cx - RETICLE_RING_RADIUS, cy - RETICLE_RING_RADIUS,
                        cx + RETICLE_RING_RADIUS, cy + RETICLE_RING_RADIUS),
                    -90f, 360f * progress, false, paint,
                )
            }
        }
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        canvas.drawCircle(cx, cy, RETICLE_DOT_RADIUS + 3f, paint)
        paint.color = if (hovering) Color.rgb(120, 200, 255) else Color.WHITE
        canvas.drawCircle(cx, cy, RETICLE_DOT_RADIUS, paint)
    }

    /** Shape (width / height) of one eye's picture on the virtual screen. */
    private fun screenAspect(): Float {
        val frameAspect = videoAspect
        val eyeAspect = when (mode) {
            VideoMode.FLAT_SBS_3D -> when (settings.layout3d) {
                // "Full" side-by-side: two complete pictures next to each other.
                "full" -> frameAspect / 2f
                // "Half": each picture is squeezed to half width and is stretched back out.
                "half" -> frameAspect
                else -> if (frameAspect >= FULL_SBS_MIN_ASPECT) frameAspect / 2f else frameAspect
            }
            VideoMode.FLAT_TB_3D -> when (settings.layout3d) {
                "full" -> frameAspect * 2f
                "half" -> frameAspect
                else -> if (frameAspect <= FULL_TB_MAX_ASPECT) frameAspect * 2f else frameAspect
            }
            else -> frameAspect
        }
        return when (settings.aspectMode) {
            "16:9" -> 16f / 9f
            "4:3" -> 4f / 3f
            "21:9" -> 21f / 9f
            "1:1" -> 1f
            "fill" -> MAX_SCREEN_WIDTH / MAX_SCREEN_HEIGHT
            else -> eyeAspect
        }
    }

    // ---- Overlay pictures --------------------------------------------------------------------------

    private fun drawCaption(canvas: Canvas, width: Int, height: Int, text: String) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = CAPTION_TEXT_SIZE_PX * settings.captionSizePct / 100f
            color = when (settings.captionColor) {
                "yellow" -> Color.rgb(255, 235, 59)
                "cyan" -> Color.rgb(0, 229, 255)
                "green" -> Color.rgb(118, 255, 3)
                else -> Color.WHITE
            }
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }
        val padding = 18f
        val textWidth = (width - 2 * padding).toInt()
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()

        var widest = 0f
        for (line in 0 until layout.lineCount) {
            widest = max(widest, layout.getLineWidth(line))
        }
        val boxWidth = min(width.toFloat(), widest + 2 * padding)
        val boxHeight = min(height.toFloat(), layout.height + 2 * padding)
        val left = (width - boxWidth) / 2f
        val top = height - boxHeight

        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((settings.captionBackgroundPct * 255) / 100, 0, 0, 0)
        }
        if (settings.captionBackgroundPct > 0) {
            canvas.drawRoundRect(RectF(left, top, left + boxWidth, height.toFloat()), 18f, 18f, background)
        }
        canvas.save()
        canvas.translate(padding, top + padding)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawHud(canvas: Canvas, width: Int, height: Int, state: HudState) {
        val w = width.toFloat()
        val h = height.toFloat()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        fill.color = Color.argb(200, 0, 0, 0)
        canvas.drawRoundRect(RectF(0f, 0f, w, h), h / 3f, h / 3f, fill)

        // Play / pause symbol, drawn as shapes so it does not depend on any font.
        fill.color = Color.WHITE
        val iconSize = h * 0.36f
        val cx = h * 0.55f
        val cy = h * 0.40f
        if (state.playing) {
            val bar = iconSize * 0.3f
            canvas.drawRect(cx - iconSize / 2, cy - iconSize / 2, cx - iconSize / 2 + bar, cy + iconSize / 2, fill)
            canvas.drawRect(cx + iconSize / 2 - bar, cy - iconSize / 2, cx + iconSize / 2, cy + iconSize / 2, fill)
        } else {
            val triangle = Path().apply {
                moveTo(cx - iconSize / 2, cy - iconSize / 2)
                lineTo(cx + iconSize / 2, cy)
                lineTo(cx - iconSize / 2, cy + iconSize / 2)
                close()
            }
            canvas.drawPath(triangle, fill)
        }

        // Text: a message if there is one, otherwise the time.
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = h * 0.30f
        }
        val line = state.note
            ?: if (state.durationMs > 0) {
                "${formatTime(state.positionMs)} / ${formatTime(state.durationMs)}"
            } else {
                formatTime(state.positionMs)
            }
        canvas.drawText(line, h * 1.05f, h * 0.40f + text.textSize * 0.35f, text)

        // Progress bar.
        val barLeft = h * 0.35f
        val barRight = w - h * 0.35f
        val barTop = h * 0.74f
        val barBottom = h * 0.82f
        fill.color = Color.argb(90, 255, 255, 255)
        canvas.drawRoundRect(RectF(barLeft, barTop, barRight, barBottom), 8f, 8f, fill)
        if (state.durationMs > 0) {
            val fraction = (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
            fill.color = Color.WHITE
            canvas.drawRoundRect(
                RectF(barLeft, barTop, barLeft + (barRight - barLeft) * fraction, barBottom), 8f, 8f, fill,
            )
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L)) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    // ---- Geometry ------------------------------------------------------------------------------------

    /**
     * A 1x1 quad centred on the origin, facing +Z. Scaled and placed with the model matrix.
     * With [flipV] the picture is turned upside down, which is what a Canvas bitmap needs.
     */
    private fun createQuad(flipV: Boolean): Mesh {
        val positions = floatArrayOf(
            -0.5f, -0.5f, 0f,
            0.5f, -0.5f, 0f,
            -0.5f, 0.5f, 0f,
            0.5f, 0.5f, 0f,
        )
        val bottom = if (flipV) 1f else 0f
        val top = if (flipV) 0f else 1f
        val texCoords = floatArrayOf(
            0f, bottom,
            1f, bottom,
            0f, top,
            1f, top,
        )
        return Mesh(positions, texCoords, null)
    }

    /**
     * A sphere seen from the inside, mapped with equirectangular texture coordinates: the
     * horizontal middle of the video lies straight ahead (-Z), the top of the video is "up".
     */
    private fun createSphere(): Mesh {
        val cols = SPHERE_LON_SEGMENTS
        val rows = SPHERE_LAT_SEGMENTS
        val vertexCount = (cols + 1) * (rows + 1)
        val positions = FloatArray(vertexCount * 3)
        val texCoords = FloatArray(vertexCount * 2)
        val pi = PI.toFloat()

        var p = 0
        var t = 0
        for (row in 0..rows) {
            val v = row.toFloat() / rows // 0 = bottom of the video, 1 = top
            val lat = (v - 0.5f) * pi // -90 .. +90 degrees
            for (col in 0..cols) {
                val u = col.toFloat() / cols // 0 = left edge of the video, 1 = right edge
                val lon = (u - 0.5f) * 2f * pi // -180 .. +180 degrees
                positions[p++] = SPHERE_RADIUS * cos(lat) * sin(lon)
                positions[p++] = SPHERE_RADIUS * sin(lat)
                positions[p++] = -SPHERE_RADIUS * cos(lat) * cos(lon)
                texCoords[t++] = u
                texCoords[t++] = v
            }
        }

        val indices = ShortArray(rows * cols * 6)
        var i = 0
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val a = row * (cols + 1) + col
                val b = a + cols + 1
                indices[i++] = a.toShort()
                indices[i++] = b.toShort()
                indices[i++] = (a + 1).toShort()
                indices[i++] = (a + 1).toShort()
                indices[i++] = b.toShort()
                indices[i++] = (b + 1).toShort()
            }
        }
        return Mesh(positions, texCoords, indices)
    }

    /** Vertex data kept in direct buffers and drawn with client-side vertex arrays. */
    private class Mesh(positions: FloatArray, texCoords: FloatArray, indices: ShortArray?) {
        private val positionBuffer: FloatBuffer = toFloatBuffer(positions)
        private val texCoordBuffer: FloatBuffer = toFloatBuffer(texCoords)
        private val indexBuffer: ShortBuffer? = indices?.let { toShortBuffer(it) }
        private val vertexCount = positions.size / 3
        private val indexCount = indices?.size ?: 0

        fun draw(aPosition: Int, aTexCoord: Int) {
            // The Cardboard SDK may leave buffers bound; make sure our client-side arrays are used.
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)

            positionBuffer.position(0)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, positionBuffer)
            GLES20.glEnableVertexAttribArray(aPosition)
            texCoordBuffer.position(0)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
            GLES20.glEnableVertexAttribArray(aTexCoord)

            val indexData = indexBuffer
            if (indexData != null) {
                indexData.position(0)
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexData)
            } else {
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, vertexCount)
            }

            GLES20.glDisableVertexAttribArray(aPosition)
            GLES20.glDisableVertexAttribArray(aTexCoord)
        }
    }

    companion object {
        private const val TAG = "VRVideo"

        private const val Z_NEAR = 0.1f
        private const val Z_FAR = 100f

        // Flat screen: at the reference distance of 3 m the 100% size is 4.4 m x 3 m (about 73
        // degrees wide). Other distances scale the size too, so the picture looks equally big
        // from the viewer's seat and only the depth impression changes.
        private const val REFERENCE_DISTANCE = 3f
        private const val MAX_SCREEN_WIDTH = 4.4f
        private const val MAX_SCREEN_HEIGHT = 3f

        // A side-by-side frame at least this wide is treated as "full" SBS (two complete
        // pictures); a top-bottom frame at most this wide as "full" top-bottom.
        private const val FULL_SBS_MIN_ASPECT = 3f
        private const val FULL_TB_MAX_ASPECT = 1.2f

        // One step of the eye image shift setting, as a fraction of half the eye picture width.
        private const val EYE_SHIFT_STEP_NDC = 0.005f

        // 360 sphere. Radius must stay below Z_FAR.
        private const val SPHERE_RADIUS = 50f
        private const val SPHERE_LON_SEGMENTS = 64
        private const val SPHERE_LAT_SEGMENTS = 32

        // Captions and status bar.
        private const val CAPTION_BITMAP_WIDTH = 1280
        private const val CAPTION_BITMAP_HEIGHT = 384
        private const val CAPTION_TEXT_SIZE_PX = 56f
        private const val CAPTION_WIDTH_FRACTION = 0.9f
        private const val CAPTION_MARGIN = 0.03f // of the visible screen height
        // Captions stay inside what you can see even when the screen is zoomed far past your view.
        private const val CAPTION_MAX_WIDTH_PER_METER = 1.3f
        private const val CAPTION_VISIBLE_HEIGHT_PER_METER = 1.0f
        private const val CAPTION_MAX_DROP_PER_METER = 0.5f

        // In-headset menu and the dot.
        private const val BAR_SHOW_ABOVE_DEG = 15f // show the bar when this close to its middle
        private const val BAR_HIDE_ABOVE_DEG = 27f
        // Lens-warp coefficients used to undo the distortion for the mouse pointer (the values
        // of the Cardboard V2 viewer); strength is set by the "mouse pointer correction" setting.
        // Mouse calibration dots: positions as angle tangents, shown 4 m away.
        private val CAL_TARGETS = arrayOf(
            floatArrayOf(0f, 0f), floatArrayOf(-0.55f, -0.45f), floatArrayOf(0.55f, -0.45f),
            floatArrayOf(-0.55f, 0.45f), floatArrayOf(0.55f, 0.45f), floatArrayOf(0f, 0.45f),
            floatArrayOf(0f, -0.45f), floatArrayOf(-0.55f, 0f), floatArrayOf(0.55f, 0f),
        )
        private const val CAL_DISTANCE = 4f
        private const val CAL_SIZE = 0.5f
        private const val POINTER_K1 = 0.34f
        private const val POINTER_K2 = 0.55f
        private const val HIT_MARGIN_PX = 6f
        private const val REPEAT_INTERVAL_MS = 600L
        private const val RETICLE_BITMAP_SIZE = 128
        private const val RETICLE_DISTANCE = 2f
        private const val RETICLE_SIZE = 0.2f // metres
        private const val RETICLE_DOT_RADIUS = 9f
        private const val RETICLE_RING_RADIUS = 42f
        private const val RETICLE_STEPS = 24
        private const val HUD_BITMAP_WIDTH = 1024
        private const val HUD_BITMAP_HEIGHT = 192

        // Things that stay in front of the head (status bar, captions of 360 video), in metres.
        private const val HEAD_OVERLAY_DISTANCE = 2f
        private const val HEAD_CAPTION_WIDTH = 2.4f
        private const val HEAD_CAPTION_BOTTOM = -0.75f
        private const val HUD_WIDTH = 1.5f
        private const val HUD_CENTER_Y = -0.55f

        // OpenGL ES 2.0 shaders. The video lives in an "external" texture, so the fragment
        // shader needs the OES extension. uTexMatrix is the transform that SurfaceTexture
        // provides (it undoes cropping/rotation/flipping done by the video decoder). Brightness,
        // contrast and saturation are applied per pixel.
        private const val VIDEO_VERTEX_SHADER = """
uniform mat4 uMvp;
uniform mat4 uTexMatrix;
uniform vec2 uUvScale;
uniform vec2 uUvOffset;
attribute vec4 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTexCoord;
void main() {
  gl_Position = uMvp * aPosition;
  vec2 uv = aTexCoord * uUvScale + uUvOffset;
  vTexCoord = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
}
"""

        private const val VIDEO_FRAGMENT_SHADER = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uTexture;
uniform float uBrightness;
uniform float uContrast;
uniform float uSaturation;
varying vec2 vTexCoord;
void main() {
  vec3 rgb = texture2D(uTexture, vTexCoord).rgb;
  float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
  rgb = mix(vec3(luma), rgb, uSaturation);
  rgb = (rgb - 0.5) * uContrast + 0.5;
  rgb = rgb * uBrightness;
  gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}
"""

        // Captions and the status bar are ordinary 2D textures with transparency.
        private const val OVERLAY_VERTEX_SHADER = """
uniform mat4 uMvp;
attribute vec4 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTexCoord;
void main() {
  gl_Position = uMvp * aPosition;
  vTexCoord = aTexCoord;
}
"""

        private const val OVERLAY_FRAGMENT_SHADER = """
precision mediump float;
uniform sampler2D uTexture;
varying vec2 vTexCoord;
void main() {
  gl_FragColor = texture2D(uTexture, vTexCoord);
}
"""

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw RuntimeException("Could not compile shader: $log")
            }
            return shader
        }

        private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
            val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                throw RuntimeException("Could not link program: $log")
            }
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            return program
        }

        private fun checkGlError(what: String) {
            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                Log.e(TAG, "$what: GL error 0x${Integer.toHexString(error)}")
            }
        }
    }
}

/** Copies [data] into a direct buffer, which is what OpenGL needs to read vertex data. */
private fun toFloatBuffer(data: FloatArray): FloatBuffer {
    val buffer = ByteBuffer.allocateDirect(data.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    buffer.put(data)
    buffer.position(0)
    return buffer
}

private fun toShortBuffer(data: ShortArray): ShortBuffer {
    val buffer = ByteBuffer.allocateDirect(data.size * 2)
        .order(ByteOrder.nativeOrder())
        .asShortBuffer()
    buffer.put(data)
    buffer.position(0)
    return buffer
}
