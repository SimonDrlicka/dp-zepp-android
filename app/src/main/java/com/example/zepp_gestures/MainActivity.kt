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

    // Testing-mode pipeline. Mutually exclusive with [service] -- only one
    // can be wired up to [server] at a time (single port 8080).
    private var testingService: TestingService? = null
    private var testingAttemptListener: ((List<ImuSample>, Int, Long?, TestingGesture?) -> Unit)? = null
    private var testingFinishedListener: (() -> Unit)? = null
    private var testingProgressListener: ((Int, Int, Boolean, Boolean) -> Unit)? = null

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

    fun backToModeSelect() {
        // Stop any running server so the next pick starts a clean service
        // with the correct flags. Both stopServer() and stopTestingServer()
        // are idempotent when nothing is running.
        if (isTestingRunning()) {
            stopTestingServer()
        } else {
            stopServer()
        }
        selectedProdMode = null
        showModeSelect()
    }

    fun showTestingPhaseSelect() {
        findViewById<View>(R.id.topBar).visibility = View.GONE
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, TestingPhaseFragment())
            .commit()
    }

    fun showTestingPhase1() {
        findViewById<View>(R.id.topBar).visibility = View.GONE
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, TestingPhase1Fragment())
            .commit()
    }

    fun showTestingPhase2() {
        findViewById<View>(R.id.topBar).visibility = View.GONE
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, TestingPhase2Fragment())
            .commit()
    }

    /**
     * Spin up the HTTP server with a fresh [TestingService]. Returns
     * `true` on success, `false` if a server is already running or the
     * port couldn't be bound.
     */
    fun startTestingServer(
        attemptCount: Int,
        onAttemptCompleted: (List<ImuSample>, Int, Long?, TestingGesture?) -> Unit,
        onTestFinished: () -> Unit,
        onProgressChanged: (Int, Int, Boolean, Boolean) -> Unit
    ): Boolean {
        if (server != null) return false

        testingAttemptListener = onAttemptCompleted
        testingFinishedListener = onTestFinished
        testingProgressListener = onProgressChanged

        val svc = TestingService(
            attemptCount = attemptCount,
            onAttemptCompleted = { samples, idx, detectedAtTs, detectedGesture ->
                testingAttemptListener?.invoke(samples, idx, detectedAtTs, detectedGesture)
            },
            onTestFinished = { testingFinishedListener?.invoke() },
            onProgressChanged = { c, t, cap, paused ->
                testingProgressListener?.invoke(c, t, cap, paused)
            }
        )
        testingService = svc

        return try {
            server = ImuHttpServer(svc, 8080).apply {
                start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            }
            true
        } catch (e: Exception) {
            testingService = null
            testingAttemptListener = null
            testingFinishedListener = null
            testingProgressListener = null
            server = null
            false
        }
    }

    fun stopTestingServer() {
        testingService?.stop()
        server?.stop()
        server = null
        testingService = null
        testingAttemptListener = null
        testingFinishedListener = null
        testingProgressListener = null
    }

    fun isTestingRunning(): Boolean = testingService != null

    /**
     * Proxy to [TestingService.skipCurrentAttempt]. No-op when no test is
     * running or the test is already inside the tail-capture window.
     */
    fun skipCurrentTestingAttempt() {
        testingService?.skipCurrentAttempt()
    }

    /**
     * Re-attach the listener trio to the currently-running [TestingService]
     * after a fragment view recreates (e.g. after a rotation). Safe to call
     * even if no test is running -- the listeners are stored unconditionally
     * and will simply never fire.
     */
    fun bindTestingCallbacks(
        onAttemptCompleted: (List<ImuSample>, Int, Long?, TestingGesture?) -> Unit,
        onTestFinished: () -> Unit,
        onProgressChanged: (Int, Int, Boolean, Boolean) -> Unit
    ) {
        testingAttemptListener = onAttemptCompleted
        testingFinishedListener = onTestFinished
        testingProgressListener = onProgressChanged
    }

    fun unbindTestingCallbacks() {
        testingAttemptListener = null
        testingFinishedListener = null
        testingProgressListener = null
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
        exportCsvAndReturnName(samples, prefix)
    }

    /**
     * Variant of [exportCsv] for testing-mode attempts. Adds a `detected`
     * column whose value is:
     *  - the recognised gesture's numeric ID (1-8) on the row whose
     *    timestamp equals [detectedAtTs];
     *  - `-` on every other row;
     *  - `-` on every row if [detectedAtTs] or [detectedGestureId] is
     *    null (skipped attempts have no recognised gesture).
     *
     * Returns the saved filename on success, `null` otherwise.
     */
    fun exportTestingCsvAndReturnName(
        samples: List<ImuSample>,
        prefix: String,
        detectedAtTs: Long?,
        detectedGestureId: Int?
    ): String? {
        if (samples.isEmpty()) {
            Toast.makeText(this, "No samples to export", Toast.LENGTH_SHORT).show()
            return null
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "${prefix}_$timestamp.csv"

        val canMark = detectedAtTs != null && detectedGestureId != null
        val markerValue = detectedGestureId?.toString() ?: "-"

        val csv = StringBuilder()
        csv.append("ts,gx,gy,gz,ax,ay,az,detected\n")
        samples.forEach { s ->
            val marker = if (canMark && s.ts == detectedAtTs) markerValue else "-"
            csv.append(s.ts).append(',')
                .append(s.gx).append(',')
                .append(s.gy).append(',')
                .append(s.gz).append(',')
                .append(s.ax).append(',')
                .append(s.ay).append(',')
                .append(s.az).append(',')
                .append(marker).append('\n')
        }

        val ok = writeToDownloads(fileName, csv.toString(), "Exported to Downloads/$fileName")
        return if (ok) fileName else null
    }

    /**
     * Export the same IMU CSV format as [exportCsv] (`ts,gx,gy,gz,ax,ay,az`)
     * but return the filename on success so callers can list saved files.
     * Returns `null` if the sample list was empty or the write failed.
     */
    fun exportCsvAndReturnName(samples: List<ImuSample>, prefix: String): String? {
        if (samples.isEmpty()) {
            Toast.makeText(this, "No samples to export", Toast.LENGTH_SHORT).show()
            return null
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

        val ok = writeToDownloads(fileName, csv.toString(), "Exported to Downloads/$fileName")
        return if (ok) fileName else null
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

    private fun writeToDownloads(fileName: String, content: String, successMessage: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Toast.makeText(this, "Failed to create file", Toast.LENGTH_SHORT).show()
            return false
        }

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            } ?: run {
                Toast.makeText(this, "Failed to open file", Toast.LENGTH_SHORT).show()
                return false
            }
            Toast.makeText(this, successMessage, Toast.LENGTH_LONG).show()
            true
        } catch (e: IOException) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }
}
