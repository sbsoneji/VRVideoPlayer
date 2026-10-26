package com.example.vrvideoplayer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLUtils
import java.util.concurrent.atomic.AtomicInteger

/**
 * A picture drawn with the normal Android Canvas (text, shapes) that is shown inside the 3D
 * scene as a flat quad. It is used for captions and for the status bar.
 *
 * Call [setDrawer] from any thread to change what is shown (null hides it). Everything else
 * runs on the GL thread.
 */
class TextOverlay(private val widthPx: Int, private val heightPx: Int) {

    private val bitmap: Bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private var textureId = 0

    @Volatile
    private var drawer: ((Canvas, Int, Int) -> Unit)? = null
    private val version = AtomicInteger(0)
    private var uploadedVersion = -1

    /** Width divided by height of the picture. */
    val aspect: Float get() = widthPx.toFloat() / heightPx

    /** Replaces what is drawn. [newDrawer] paints onto a transparent canvas of width x height. */
    fun setDrawer(newDrawer: ((Canvas, Int, Int) -> Unit)?) {
        drawer = newDrawer
        version.incrementAndGet()
    }

    /** GL thread: creates the OpenGL texture. Call again whenever the GL context is re-created. */
    fun onGlCreated() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        uploadedVersion = -1 // forces a new upload into the new texture
    }

    /**
     * GL thread, once per frame: uploads the picture to the GPU if it changed.
     * Returns true if there is something to draw.
     */
    fun prepare(): Boolean {
        val currentDrawer = drawer ?: return false
        val currentVersion = version.get()
        if (currentVersion != uploadedVersion) {
            bitmap.eraseColor(Color.TRANSPARENT)
            currentDrawer(canvas, widthPx, heightPx)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            uploadedVersion = currentVersion
        }
        return true
    }

    /** GL thread: makes this overlay's texture the active one (texture unit 0). */
    fun bind() {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
    }
}
