package com.example.vrvideoplayer

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * The About screen: version, author, contact links and credits. The texts and links are in
 * res/values/strings.xml (about_*, contact_email, github_url, linkedin_url).
 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        } catch (e: Exception) {
            ""
        }
        findViewById<TextView>(R.id.version_label).text = getString(R.string.about_version, version)

        findViewById<Button>(R.id.email_button).setOnClickListener { sendEmail() }
        findViewById<Button>(R.id.github_button).setOnClickListener {
            openLink(getString(R.string.github_url))
        }
        findViewById<Button>(R.id.linkedin_button).setOnClickListener {
            openLink(getString(R.string.linkedin_url))
        }
    }

    private fun sendEmail() {
        val address = getString(R.string.contact_email)
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(address))
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.contact_email_subject))
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.error_no_email_app, address), Toast.LENGTH_LONG).show()
        }
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }
}
