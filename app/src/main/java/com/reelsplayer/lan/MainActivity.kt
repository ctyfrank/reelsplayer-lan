package com.reelsplayer.lan

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.viewpager2.widget.ViewPager2
import com.reelsplayer.lan.logging.AppLog
import com.reelsplayer.lan.settings.PlaybackSettings
import com.reelsplayer.lan.settings.ResumePoint
import com.reelsplayer.lan.settings.ResumeStore
import com.reelsplayer.lan.smb.ServerStore
import com.reelsplayer.lan.smb.SmbErrors
import com.reelsplayer.lan.smb.SmbSession
import com.reelsplayer.lan.ui.BrowseActivity
import com.reelsplayer.lan.ui.LogActivity
import com.reelsplayer.lan.ui.ServersActivity
import com.reelsplayer.lan.ui.SettingsActivity

class MainActivity : AppCompatActivity() {

    private lateinit var landing: LinearLayout
    private lateinit var landingSubtitle: TextView
    private lateinit var pickBtn: Button
    private lateinit var lanBtn: Button
    private lateinit var logBtn: Button
    private lateinit var settingsBtn: Button
    private lateinit var resumeBtn: Button
    private lateinit var exitBtn: ImageButton
    private lateinit var viewPager: ViewPager2
    private lateinit var hud: LinearLayout
    private lateinit var hudName: TextView
    private lateinit var hudCounter: TextView

    /** Relative path inside the picked folder (e.g. "sub/clip.mp4") mapped to its playback URI. */
    private var videoFiles = mutableListOf<Pair<String, Uri>>()

    /** True while the swipe feed covers the screen; back then leaves the feed instead of the app. */
    private var playbackActive = false

    private var currentIndex = 0
    private var lastBackPressAt = 0L

    /** Where the current playlist came from, so playback can be resumed later. */
    private var sourceType = ""
    private var sourceId = ""
    private var sourcePath = ""

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Bumped on every folder pick so that a scan started earlier can stop early. */
    @Volatile
    private var scanToken = 0

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { loadFolder(it) }
    }

    private val lanPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val serverId = data.getStringExtra(BrowseActivity.RESULT_SERVER_ID) ?: return@registerForActivityResult
        val path = data.getStringExtra(BrowseActivity.RESULT_PATH).orEmpty()
        loadLanFolder(serverId, path)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        landing = findViewById(R.id.landing)
        landingSubtitle = findViewById(R.id.landingSubtitle)
        pickBtn = findViewById(R.id.pickBtn)
        lanBtn = findViewById(R.id.lanBtn)
        logBtn = findViewById(R.id.logBtn)
        settingsBtn = findViewById(R.id.settingsBtn)
        resumeBtn = findViewById(R.id.resumeBtn)
        exitBtn = findViewById(R.id.exitBtn)
        viewPager = findViewById(R.id.viewPager)
        hud = findViewById(R.id.hud)
        hudName = findViewById(R.id.hudName)
        hudCounter = findViewById(R.id.hudCounter)

        pickBtn.setOnClickListener {
            folderPicker.launch(null)
        }
        lanBtn.setOnClickListener {
            lanPicker.launch(Intent(this, ServersActivity::class.java))
        }
        logBtn.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        resumeBtn.setOnClickListener {
            resumeLastSession()
        }
        exitBtn.setOnClickListener {
            exitPlayback()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (playbackActive) {
                    exitPlayback()
                    return
                }
                // At the landing screen: first back warns, a second one within two seconds exits.
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackPressAt < EXIT_WINDOW_MS) {
                    finish()
                } else {
                    lastBackPressAt = now
                    Toast.makeText(this@MainActivity, R.string.press_back_again, Toast.LENGTH_SHORT).show()
                }
            }
        })

        viewPager.offscreenPageLimit = 1
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentIndex = position
                updateHud(position)
                (viewPager.adapter as? VideoAdapter)?.onPageSelected(position)
                saveResume()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        (viewPager.adapter as? VideoAdapter)?.resumeCurrent()
        refreshResumeButton()
    }

    override fun onPause() {
        super.onPause()
        saveResume()
        (viewPager.adapter as? VideoAdapter)?.pauseCurrent()
    }

    // ------------------------------------------------------------ local files

    private fun loadFolder(
        treeUri: Uri,
        resumeName: String? = null,
        resumeIndex: Int = 0,
        resumeMs: Long = 0L
    ) {
        // Release old players before loading new folder
        (viewPager.adapter as? VideoAdapter)?.releaseAll()

        try {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Permission usable for this session, just not persistable
        }

        val tree = DocumentFile.fromTreeUri(this, treeUri) ?: return
        val token = ++scanToken
        showScanning(getString(R.string.scanning_format, 0))
        AppLog.i("Scan", "local scan start uri=$treeUri")

        sourceType = ResumeStore.SOURCE_LOCAL
        sourceId = ""
        sourcePath = treeUri.toString()

        // Walking the whole tree means many content-resolver round trips, so keep it off the UI thread.
        Thread {
            val found = ArrayList<Pair<String, Uri>>()
            collectVideos(
                dir = tree,
                pathPrefix = "",
                visited = HashSet(),
                out = found,
                isStale = { scanToken != token },
                onFound = { count ->
                    if (count % PROGRESS_UPDATE_EVERY == 0) {
                        mainHandler.post {
                            if (scanToken == token) {
                                landingSubtitle.text = getString(R.string.scanning_format, count)
                            }
                        }
                    }
                }
            )

            // Sorting by relative path keeps the videos of each subfolder grouped in swipe order.
            found.sortWith { a, b -> a.first.compareTo(b.first, ignoreCase = true) }

            mainHandler.post {
                if (scanToken != token) return@post
                finishScanning()
                AppLog.i("Scan", "local scan done videos=${found.size}")
                showVideos(found, resumeName, resumeIndex, resumeMs)
            }
        }.start()
    }

    /**
     * Depth-first walk of [dir], appending every video below it to [out].
     *
     * @param pathPrefix path of [dir] relative to the picked folder, with a trailing slash.
     */
    private fun collectVideos(
        dir: DocumentFile,
        pathPrefix: String,
        visited: MutableSet<String>,
        out: MutableList<Pair<String, Uri>>,
        isStale: () -> Boolean,
        onFound: (Int) -> Unit
    ) {
        if (isStale()) return
        // Guards against providers that report a document as its own descendant.
        if (!visited.add(dir.uri.toString())) return

        val children = try {
            dir.listFiles()
        } catch (_: Exception) {
            emptyArray<DocumentFile>()
        }

        for (child in children) {
            if (isStale()) return
            val name = child.name ?: continue

            if (child.isDirectory) {
                collectVideos(child, "$pathPrefix$name/", visited, out, isStale, onFound)
                continue
            }

            val type = try {
                child.type
            } catch (_: Exception) {
                null
            }
            if (type?.startsWith("video/") == true) {
                out.add(Pair("$pathPrefix$name", child.uri))
                onFound(out.size)
            }
        }
    }

    // ------------------------------------------------------------- LAN files

    private fun loadLanFolder(
        serverId: String,
        path: String,
        resumeName: String? = null,
        resumeIndex: Int = 0,
        resumeMs: Long = 0L
    ) {
        val server = ServerStore.get(this).find(serverId) ?: return
        (viewPager.adapter as? VideoAdapter)?.releaseAll()

        val token = ++scanToken
        showScanning(getString(R.string.scanning_lan_format, 0))
        AppLog.i(
            "Scan",
            "lan scan start server=${server.label.ifBlank { server.host }} " +
                "host=${server.host} path=/$path"
        )

        sourceType = ResumeStore.SOURCE_LAN
        sourceId = serverId
        sourcePath = path

        Thread {
            val outcome = runCatching {
                SmbSession.scanVideos(
                    server = server,
                    path = path,
                    isStale = { scanToken != token },
                    onFound = { count ->
                        if (count % PROGRESS_UPDATE_EVERY == 0) {
                            mainHandler.post {
                                if (scanToken == token) {
                                    landingSubtitle.text =
                                        getString(R.string.scanning_lan_format, count)
                                }
                            }
                        }
                    }
                )
            }

            mainHandler.post {
                if (scanToken != token) return@post
                finishScanning()
                outcome.onSuccess {
                    AppLog.i("Scan", "lan scan done videos=${it.size}")
                    showVideos(it, resumeName, resumeIndex, resumeMs)
                }.onFailure {
                    val message = SmbErrors.message(this, it)
                    AppLog.e("Scan", "lan scan failed: $message", it)
                    showError(message)
                }
            }
        }.start()
    }

    // --------------------------------------------------------------- playlist

    private class Arrangement(
        val items: List<Pair<String, Uri>>,
        val index: Int,
        val seekMs: Long
    )

    private fun arrange(
        found: List<Pair<String, Uri>>,
        resumeName: String?,
        resumeIndex: Int,
        resumeMs: Long
    ): Arrangement {
        val list = ArrayList(found)
        val resolved = resolveIndex(list, resumeName, resumeIndex)
        // Only seek when the saved item is actually the one we are about to start.
        val seekMs = if (resumeName != null && list.getOrNull(resolved)?.first == resumeName) {
            resumeMs
        } else {
            0L
        }

        if (PlaybackSettings.order(this) == PlaybackSettings.Order.SHUFFLE) {
            if (resumeName != null) {
                // Keep the resumed video first, shuffle everything else.
                val head = list.removeAt(resolved)
                list.shuffle()
                list.add(0, head)
                return Arrangement(list, 0, seekMs)
            }
            list.shuffle()
            return Arrangement(list, 0, 0L)
        }

        return Arrangement(list, resolved, seekMs)
    }

    private fun resolveIndex(list: List<Pair<String, Uri>>, name: String?, index: Int): Int {
        if (name != null) {
            val byName = list.indexOfFirst { it.first == name }
            if (byName >= 0) return byName
        }
        return index.coerceIn(0, (list.size - 1).coerceAtLeast(0))
    }

    private fun showVideos(
        found: List<Pair<String, Uri>>,
        resumeName: String? = null,
        resumeIndex: Int = 0,
        resumeMs: Long = 0L
    ) {
        if (found.isEmpty()) {
            Toast.makeText(this, R.string.no_videos, Toast.LENGTH_SHORT).show()
            return
        }

        val arrangement = arrange(found, resumeName, resumeIndex, resumeMs)
        videoFiles = ArrayList(arrangement.items)
        currentIndex = arrangement.index
        playbackActive = true

        landing.visibility = View.GONE
        resumeBtn.visibility = View.GONE
        viewPager.visibility = View.VISIBLE
        hud.visibility = View.VISIBLE
        exitBtn.visibility = View.VISIBLE

        val adapter = VideoAdapter(
            videos = videoFiles,
            onEndMode = PlaybackSettings.onEnd(this),
            onRequestNext = { position ->
                // "Play the next one" mode: advance the pager when a video finishes.
                if (position + 1 < videoFiles.size) {
                    viewPager.setCurrentItem(position + 1, true)
                }
            }
        )
        if (arrangement.seekMs > 0) {
            adapter.requestSeekTo(arrangement.seekMs)
            AppLog.i("Player", "resume at item=${arrangement.index} pos=${arrangement.seekMs}ms")
        }

        viewPager.adapter = adapter
        adapter.onPageSelected(arrangement.index)
        viewPager.post {
            if (viewPager.currentItem != arrangement.index) {
                viewPager.setCurrentItem(arrangement.index, false)
            }
        }
        updateHud(arrangement.index)
        saveResume()
    }

    /** Leaves the swipe feed and returns to the landing screen, keeping the playlist for resume. */
    private fun exitPlayback() {
        if (!playbackActive) return
        saveResume()
        playbackActive = false

        (viewPager.adapter as? VideoAdapter)?.releaseAll()
        viewPager.adapter = null
        viewPager.visibility = View.GONE
        hud.visibility = View.GONE
        exitBtn.visibility = View.GONE
        landing.visibility = View.VISIBLE
        finishScanning()

        AppLog.i("Player", "playback exited at item=$currentIndex/${videoFiles.size}")
        refreshResumeButton()
    }

    // ---------------------------------------------------------------- resume

    private fun saveResume() {
        if (!playbackActive) return
        val item = videoFiles.getOrNull(currentIndex) ?: return
        val position = (viewPager.adapter as? VideoAdapter)?.currentPositionMs() ?: 0L
        ResumeStore.save(
            this,
            ResumePoint(
                sourceType = sourceType,
                sourceId = sourceId,
                sourcePath = sourcePath,
                itemName = item.first,
                index = currentIndex,
                positionMs = position,
                total = videoFiles.size
            )
        )
    }

    private fun refreshResumeButton() {
        if (playbackActive) return
        val point = ResumeStore.load(this)
        if (point == null) {
            resumeBtn.visibility = View.GONE
            return
        }
        if (point.sourceType == ResumeStore.SOURCE_LAN &&
            ServerStore.get(this).find(point.sourceId) == null
        ) {
            ResumeStore.clear(this)
            resumeBtn.visibility = View.GONE
            return
        }
        resumeBtn.text = getString(R.string.resume_playback_format, point.total)
        resumeBtn.visibility = View.VISIBLE
    }

    private fun resumeLastSession() {
        val point = ResumeStore.load(this) ?: run {
            refreshResumeButton()
            return
        }
        AppLog.i(
            "Resume",
            "resume ${point.sourceType} source=${point.sourcePath} item=${point.itemName} " +
                "index=${point.index} pos=${point.positionMs}"
        )

        // Same playlist still in memory: resume without rescanning the share.
        if (videoFiles.isNotEmpty() &&
            point.sourceType == sourceType &&
            point.sourceId == sourceId &&
            point.sourcePath == sourcePath
        ) {
            showVideos(videoFiles, point.itemName, point.index, point.positionMs)
            return
        }

        when (point.sourceType) {
            ResumeStore.SOURCE_LAN -> loadLanFolder(
                point.sourceId,
                point.sourcePath,
                point.itemName,
                point.index,
                point.positionMs
            )

            else -> loadFolder(
                Uri.parse(point.sourcePath),
                point.itemName,
                point.index,
                point.positionMs
            )
        }
    }

    // ------------------------------------------------------------- ui helpers

    private fun showScanning(message: String) {
        landing.visibility = View.VISIBLE
        viewPager.visibility = View.GONE
        hud.visibility = View.GONE
        exitBtn.visibility = View.GONE
        resumeBtn.visibility = View.GONE
        playbackActive = false
        pickBtn.isEnabled = false
        lanBtn.isEnabled = false
        landingSubtitle.text = message
    }

    private fun finishScanning() {
        pickBtn.isEnabled = true
        lanBtn.isEnabled = true
        landingSubtitle.setText(R.string.landing_subtitle)
    }

    private fun showError(message: String) {
        playbackActive = false
        landing.visibility = View.VISIBLE
        viewPager.visibility = View.GONE
        hud.visibility = View.GONE
        exitBtn.visibility = View.GONE
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        refreshResumeButton()
    }

    private fun updateHud(position: Int) {
        if (position < 0 || position >= videoFiles.size) return
        hudName.text = videoFiles[position].first
        hudCounter.text = getString(R.string.counter_format, position + 1, videoFiles.size)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop a scan that is still running so its results are dropped.
        scanToken++
        (viewPager.adapter as? VideoAdapter)?.releaseAll()
    }

    private companion object {
        /** How many videos to find between two "Scanning…" updates. */
        const val PROGRESS_UPDATE_EVERY = 25

        /** Window in which a second back press at the landing screen exits the app. */
        const val EXIT_WINDOW_MS = 2_000L
    }
}