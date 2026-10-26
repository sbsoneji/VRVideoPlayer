package com.example.vrvideoplayer

import android.content.Context
import android.util.Log
import com.google.cardboard.sdk.Initialize
import com.google.cardboard.sdk.QrCode
import com.google.cardboard.sdk.deviceparams.DeviceParamsUtils

/**
 * Helpers around the viewer (headset) profile that the Cardboard SDK keeps.
 *
 * The SDK saves the profile from a scanned QR code in this app's private storage, so it stays
 * available after the app is closed. If nothing was ever scanned, the SDK uses the original
 * Google Cardboard profile.
 */
object ViewerInfo {
    private const val TAG = "VRVideo"

    /** A short description of the viewer that will be used, for display in the app. */
    fun describe(context: Context): String {
        return try {
            Initialize.initialize(context)
            val bytes = QrCode.getSavedDeviceParams()
            if (bytes == null) {
                context.getString(R.string.viewer_default)
            } else {
                val params = DeviceParamsUtils.parseCardboardDeviceParams(bytes)
                val name = params?.let { "${it.vendor} ${it.model}".trim() }.orEmpty()
                if (name.isEmpty()) context.getString(R.string.viewer_saved) else name
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read the saved viewer", t)
            context.getString(R.string.viewer_unknown)
        }
    }

    /** Opens the QR scanner. Returns false if the scanner could not be started. */
    fun scan(context: Context): Boolean {
        return try {
            Initialize.initialize(context)
            QrCode.scanQrCodeAndSaveDeviceParams()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Could not start the viewer scanner", t)
            false
        }
    }
}
