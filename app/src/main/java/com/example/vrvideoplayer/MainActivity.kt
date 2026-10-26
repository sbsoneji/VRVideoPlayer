package com.example.vrvideoplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText

/**
 * The normal (non-VR) start screen: pick a video file or paste a URL, choose how the video is
 * laid out, and start the VR player.
 */
class MainActivity : AppCompatActivity() {

    /** The video that will be played: a content:// URI from the file picker, or an http(s) URL. */
    private var source: Uri? = null

    /** Optional subtitle file. */
    private var subtitle: Uri? = null
    private var subtitleName: String? = null

    private lateinit var sourceLabel: TextView
    private lateinit var subtitleLabel: TextView
    private lateinit var viewerLabel: TextView
    private lateinit var urlInput: TextInputEditText
    private lateinit var modeGroup: RadioGroup
    private lateinit var stereoSwitch: CompoundButton
    private lateinit var startButton: Button

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                keepAccess(uri)
                setSource(uri, displayNameOf(uri), guessMode = true)
            }
        }

    private val pickSubtitle =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                keepAccess(uri)
                setSubtitle(uri, displayNameOf(uri))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sourceLabel = findViewById(R.id.source_label)
        subtitleLabel = findViewById(R.id.subtitle_label)
        viewerLabel = findViewById(R.id.viewer_label)
        urlInput = findViewById(R.id.url_input)
        modeGroup = findViewById(R.id.mode_group)
        stereoSwitch = findViewById(R.id.stereo_switch)
        startButton = findViewById(R.id.start_button)

        findViewById<Button>(R.id.pick_button).setOnClickListener {
            pickVideo.launch(arrayOf("video/*"))
        }
        findViewById<Button>(R.id.use_url_button).setOnClickListener { useUrl() }
        findViewById<Button>(R.id.subtitle_button).setOnClickListener {
            pickSubtitle.launch(arrayOf("*/*"))
        }
        findViewById<Button>(R.id.subtitle_clear_button).setOnClickListener { setSubtitle(null, null) }
        startButton.setOnClickListener { start() }
        findViewById<Button>(R.id.qr_button).setOnClickListener { scanViewerQr() }
        findViewById<View>(R.id.about_button).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        findViewById<Button>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Bring back the chosen video after e.g. a rotation. The radio buttons and the switch
        // restore themselves, so the video-type guess is not repeated.
        savedInstanceState?.getString(STATE_SOURCE)?.let { saved ->
            val label = savedInstanceState.getString(STATE_LABEL) ?: saved
            setSource(Uri.parse(saved), label, guessMode = false)
        }
        savedInstanceState?.getString(STATE_SUBTITLE)?.let { saved ->
            setSubtitle(Uri.parse(saved), savedInstanceState.getString(STATE_SUBTITLE_NAME))
        }
    }

    override fun onResume() {
        super.onResume()
        // Shows which viewer will be used. It is read again every time we come back to this
        // screen, so a freshly scanned viewer appears here.
        viewerLabel.text = getString(R.string.viewer_line, ViewerInfo.describe(this))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        source?.let {
            outState.putString(STATE_SOURCE, it.toString())
            outState.putString(STATE_LABEL, sourceLabel.text.toString())
        }
        subtitle?.let {
            outState.putString(STATE_SUBTITLE, it.toString())
            outState.putString(STATE_SUBTITLE_NAME, subtitleName)
        }
    }

    private fun keepAccess(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Not every file provider allows keeping access; we can still read it now.
        }
    }

    private fun setSource(uri: Uri, name: String, guessMode: Boolean) {
        source = uri
        sourceLabel.text = name
        startButton.isEnabled = true
        if (guessMode) {
            modeGroup.check(
                when (VideoMode.guessFrom(name)) {
                    VideoMode.FLAT_2D -> R.id.mode_2d
                    VideoMode.FLAT_SBS_3D -> R.id.mode_sbs
                    VideoMode.FLAT_TB_3D -> R.id.mode_tb
                    VideoMode.SPHERE_360 -> R.id.mode_360
                },
            )
        }
    }

    private fun setSubtitle(uri: Uri?, name: String?) {
        subtitle = uri
        subtitleName = name
        subtitleLabel.text = name ?: getString(R.string.no_subtitle)
    }

    private fun useUrl() {
        val text = urlInput.text?.toString()?.trim().orEmpty()
        val uri = Uri.parse(text)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            urlInput.error = getString(R.string.error_bad_url)
            return
        }
        urlInput.error = null
        setSource(uri, text, guessMode = true)
    }

    private fun start() {
        val uri = source ?: return
        val mode = when (modeGroup.checkedRadioButtonId) {
            R.id.mode_sbs -> VideoMode.FLAT_SBS_3D
            R.id.mode_tb -> VideoMode.FLAT_TB_3D
            R.id.mode_360 -> VideoMode.SPHERE_360
            else -> VideoMode.FLAT_2D
        }
        startActivity(
            VrPlayerActivity.createIntent(
                this,
                uri,
                mode,
                stereo = stereoSwitch.isChecked,
                subtitleUri = subtitle,
                subtitleName = subtitleName,
            ),
        )
    }

    /**
     * Opens the Cardboard SDK's QR scanner. Scanning the code on your viewer saves its lens
     * measurements, so the picture is corrected for your exact headset. The saved viewer is
     * remembered; the scan is only needed once per viewer.
     */
    private fun scanViewerQr() {
        if (!ViewerInfo.scan(this)) {
            Toast.makeText(this, R.string.error_qr_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    /** A human-readable name for a picked file, used for the label and for guessing the mode. */
    private fun displayNameOf(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0) {
                        cursor.getString(column)?.let { return it }
                    }
                }
            }
        return uri.lastPathSegment ?: uri.toString()
    }

    private companion object {
        const val STATE_SOURCE = "source"
        const val STATE_LABEL = "label"
        const val STATE_SUBTITLE = "subtitle"
        const val STATE_SUBTITLE_NAME = "subtitle_name"
    }
}
