package com.example.zepp_gestures

import android.content.ContentValues
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayout
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

class MainActivity : AppCompatActivity() {

    var server: ImuHttpServer? = null
    var service: GestureRecognitionService? = null
    val latestGestureMessage = AtomicReference("No gesture detected")
    val gestures = GestureConfig.gestures

    // Selected once on the mode-select screen. null until the user picks
    // Debug or Production. Persisted across rotation via savedInstanceState.
    var selectedProdMode: Boolean? = null
        private set

    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val STATE_PROD_MODE = "selectedProdMode"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState != null && savedInstanceState.containsKey(STATE_PROD_MODE)) {
            selectedProdMode = savedInstanceState.getBoolean(STATE_PROD_MODE)
        }

        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        tabLayout.addTab(tabLayout.newTab().setText("Graphs"))
        tabLayout.addTab(tabLayout.newTab().setText("Events"))
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val fragment = when (tab.position) {
                    0 -> DebugFragment()
                    1 -> ProdFragment()
                    else -> DebugFragment()
                }
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainer, fragment)
                    .commit()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        findViewById<Button>(R.id.backToModeBtn).setOnClickListener {
            backToModeSelect()
        }

        if (savedInstanceState == null) {
            if (selectedProdMode == null) {
                showModeSelect()
            } else {
                showTabsWithDefaultFragment()
            }
        } else {
            // Configuration change: keep top bar visibility in sync with
            // whether a mode was chosen. The fragment manager restores the
            // last committed fragment on its own, so we don't replace it.
            findViewById<View>(R.id.topBar).visibility =
                if (selectedProdMode == null) View.GONE else View.VISIBLE
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selectedProdMode?.let { outState.putBoolean(STATE_PROD_MODE, it) }
    }

    private fun showModeSelect() {
        findViewById<View>(R.id.topBar).visibility = View.GONE
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, ModeSelectFragment())
            .commit()
    }

    private fun showTabsWithDefaultFragment() {
        findViewById<View>(R.id.topBar).visibility = View.VISIBLE
        // Always reset the tab selection to the first tab so that returning
        // to the tabs after a mode change starts on Graphs.
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        tabLayout.getTabAt(0)?.select()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, DebugFragment())
            .commit()
    }

    fun onModeSelected(prodMode: Boolean) {
        selectedProdMode = prodMode
        showTabsWithDefaultFragment()
    }

    private fun backToModeSelect() {
        // Stop any running server so the next pick of debug/prod starts a
        // clean service with the correct flags. stopServer() is idempotent
        // when no server is running.
        stopServer()
        selectedProdMode = null
        showModeSelect()
    }

    override fun onDestroy() {
        server?.stop()
        super.onDestroy()
    }

    fun startServer(prodMode: Boolean = false) {
        if (server != null) return
        val svc = GestureRecognitionService(
            gestureConfig = gestures,
            latestGestureMessage = latestGestureMessage,
            onGestureSegmentReady = { samples ->
                handler.post {
                    exportCsv(samples, "gesture_segment")
                }
            },
            passivityTrackingEnabled = !prodMode,
            logActivationGestures = !prodMode
        )
        service = svc
        server = ImuHttpServer(svc, 8080).apply {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        }
    }

    fun stopServer() {
        val s = server ?: return
        val svc = service ?: return
        val (blue, red) = svc.getPoints()
        val samples = svc.getSessionSamples()
        val events = svc.getMatchEvents()
        if (samples.isNotEmpty()) {
            exportCsv(samples, "stop_server_export")
        }
        if (events.isNotEmpty()) {
            exportMatchEventsCsv(events, blue, red)
        }
        exportPointsCsv(blue, red)
        s.stop()
        server = null
        service = null
    }

    fun exportAllCsv() {
        val samples = service?.getSessionSamples().orEmpty()
        if (samples.isEmpty()) {
            Toast.makeText(this, "No samples to export", Toast.LENGTH_SHORT).show()
            return
        }
        exportCsv(samples, "imu_samples")
    }

    fun exportCsv(samples: List<ImuSample>, prefix: String) {
        if (samples.isEmpty()) {
            Toast.makeText(this, "No samples to export", Toast.LENGTH_SHORT).show()
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "${prefix}_$timestamp.csv"

        val csv = StringBuilder()
        csv.append("ts,gx,gy,gz,ax,ay,az\n")
        samples.forEach { s ->
            csv.append(s.ts).append(',')
                .append(s.gx).append(',')
                .append(s.gy).append(',')
                .append(s.gz).append(',')
                .append(s.ax).append(',')
                .append(s.ay).append(',')
                .append(s.az).append('\n')
        }

        writeToDownloads(fileName, csv.toString(), "Exported to Downloads/$fileName")
    }

    private fun exportMatchEventsCsv(events: List<MatchEvent>, blue: Int, red: Int) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "match_events_$timestamp.csv"
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

        val csv = StringBuilder()
        csv.append("# result: blue=$blue red=$red\n")
        csv.append("timestamp,datetime,event\n")
        events.forEach { e ->
            csv.append(e.ts).append(',')
                .append(fmt.format(Date(e.ts))).append(',')
                .append(e.event).append('\n')
        }

        writeToDownloads(fileName, csv.toString(), "Events exported to Downloads/$fileName")
    }

    private fun exportPointsCsv(blue: Int, red: Int) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "points_$timestamp.csv"
        val csv = StringBuilder()
        csv.append("blue_points,red_points\n")
        csv.append(blue).append(',').append(red).append('\n')

        writeToDownloads(fileName, csv.toString(), "Points exported to Downloads/$fileName")
    }

    private fun writeToDownloads(fileName: String, content: String, successMessage: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Toast.makeText(this, "Failed to create file", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            } ?: run {
                Toast.makeText(this, "Failed to open file", Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(this, successMessage, Toast.LENGTH_LONG).show()
        } catch (e: IOException) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
