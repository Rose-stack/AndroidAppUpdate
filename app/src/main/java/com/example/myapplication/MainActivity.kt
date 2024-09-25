package com.example.myapplication

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val client = OkHttpClient()
    private var downloadId: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }

        checkUpdates()

    }

    // check if an app update is available
    private fun checkUpdates() {
        lifecycleScope.launch {
            val latestVersionInfo = fetchVersionInfo()
            latestVersionInfo?.let { (versionCode, apkUrl) ->

                val currentVersionCode =
                    packageManager.getPackageInfo(packageName, 0).versionCode

                // Compare the current version code with the
                // latest version code from S3
                if (versionCode > currentVersionCode) {
                    // If the latest version is greater than the current version, show the update dialog
                    updateDialog(apkUrl)
                }
            }
        }
    }


    private suspend fun fetchVersionInfo(): Pair<Int, String>? {
        return withContext(Dispatchers.IO) {
            // update.json S3 URL
            val url =
                "https://android-app-tester.s3.amazonaws.com/update.json" // S3 URL for version info
            val request = Request.Builder().url(url).build()

            client.newCall(request).execute().use { response ->
                // Check if the response is successful
                // HTTP 200 OK
                if (response.isSuccessful) {
                    val jsonData = response.body?.string()
                    jsonData?.let {
                        // Extract the versionCode and apkUrl

                        val jsonObject = JSONObject(it)
                        val versionCode = jsonObject.getInt("versionCode")
                        val apkUrl = jsonObject.getString("apkUrl")
                        return@withContext Pair(versionCode, apkUrl)
                    }
                }
            }
            null
        }
    }

    private fun updateDialog(apkUrl: String) {
        AlertDialog.Builder(this)
            .setTitle("Update Available")
            .setMessage("A new version is available. Would you like to update?")
            .setPositiveButton("Yes") { _, _ -> downloadAndUpdate(apkUrl) }
            .setNegativeButton("No", null)
            .show()
    }

    private fun downloadAndUpdate(apkUrl: String) {
        // Get system DownloadManager ready
        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        // download your APK
        val request = DownloadManager.Request(Uri.parse(apkUrl))
        // Add a download notification
        request.setTitle("Downloading Update")
        request.setDescription("Downloading the latest version of the app.")
        // Tester should know when download is completed
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        // Remember to set the name of the downloaded APK
        // Try to make it the same as the S3 APK Object name
        request.setDestinationInExternalFilesDir(this, null, "app-release.apk")

        // This here sets the download ID track statuses
        downloadId = downloadManager.enqueue(request)

        // Launch a coroutine to poll the download status
        lifecycleScope.launch {
            while (true) {
                val status = withContext(Dispatchers.IO) {
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    downloadManager.query(query)?.use { cursor ->
                        // Confirm is a download is already available
                        // This is done based on the status
                        if (cursor.moveToFirst()) {
                            cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
                        } else {
                            null // No download found
                        }
                    }
                }

                // Possible download statuses
                when (status) {
                    // download status successful
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        // Retrieve the URI of the downloaded APK
                        val uri = downloadManager.getUriForDownloadedFile(downloadId)
                        uri?.let {
                            // Launch the installer
                            installApk(it)
                        }
                        return@launch // Exit the coroutine after installing
                    }

                    DownloadManager.STATUS_FAILED -> {
                        Toast.makeText(this@MainActivity, "Download failed.", Toast.LENGTH_LONG)
                            .show()
                        return@launch
                    }

                    null -> {
                        // No download found
                        // Download not complete
                        // Continue status check
                    }
                }
            }
        }
    }


    private fun installApk(apkUri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            // APK URI read permissions
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        // Start the installer
        startActivity(intent)
    }


    @Composable
    fun Greeting(name: String, modifier: Modifier = Modifier) {
        Text(
            text = "Test $name app! is working",
            modifier = modifier
        )
    }

    @Preview(showBackground = true)
    @Composable
    fun GreetingPreview() {
        MyApplicationTheme {
            Greeting("Android")
        }
    }

}
