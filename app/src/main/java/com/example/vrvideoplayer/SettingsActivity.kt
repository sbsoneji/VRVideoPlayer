package com.example.vrvideoplayer

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager

/**
 * The settings screen. The list of settings is in res/xml/preferences.xml; each one is stored
 * automatically and read again by [VrSettings.load] when a video starts.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<Preference>(Keys.VIEWER_INFO)?.setOnPreferenceClickListener {
                if (!ViewerInfo.scan(requireContext())) {
                    Toast.makeText(requireContext(), R.string.error_qr_unavailable, Toast.LENGTH_LONG).show()
                }
                true
            }

            findPreference<Preference>(Keys.RESET)?.setOnPreferenceClickListener {
                PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().clear().apply()
                // Rebuild the screen so every control shows its default again.
                setPreferencesFromResource(R.xml.preferences, null)
                updateViewerSummary()
                Toast.makeText(requireContext(), R.string.settings_reset_done, Toast.LENGTH_SHORT).show()
                true
            }
        }

        override fun onResume() {
            super.onResume()
            // The viewer may have just been scanned; show the current one.
            updateViewerSummary()
        }

        private fun updateViewerSummary() {
            findPreference<Preference>(Keys.VIEWER_INFO)?.summary =
                getString(R.string.pref_viewer_summary, ViewerInfo.describe(requireContext()))
        }
    }
}
