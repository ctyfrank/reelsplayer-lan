package com.reelsplayer.lan

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.viewpager2.widget.ViewPager2
import com.reelsplayer.lan.logging.AppLog
import com.reelsplayer.lan.smb.ServerStore
import com.reelsplayer.lan.smb.SmbErrors
import com.reelsplayer.lan.smb.SmbSession
import com.reelsplayer.lan.ui.BrowseActivity
import com.reelsplayer.lan.ui.LogActivity
import com.reelsplayer.lan.ui.ServersActivity

class MainActivity : AppCompatActivity() {

    private lateinit var landing: LinearLayout
    private lateinit var landingSubtitle: TextView
    private lateinit var pickBtn: Button
    private lateinit var lanBtn: Button
    private lateinit var logBtn: Button
    private lateinit var viewPager: ViewPager2
    private lateinit var hud: LinearLayout
    private lateinit var hudName: TextView
    private lateinit var hudCounter: TextView

    /** Relative path inside the picked folder (e.g. "sub/clip.mp4") mapped to its content URI. */
    private var videoFiles = mutableListOf<Pair<String, Uri>>()

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

        viewPager.offscreenPageLimit = 1
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateHud(position)
                (viewPager.adapter as? VideoAdapter)?.onPageSelected(position)
            }
        })
    }

    // ------------------------------------------------------------ local files

    private fun loadFolder(treeUri: Uri) {
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
                showVideos(found)
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

    private fun loadLanFolder(serverId: String, path: String) {
        val server = ServerStore.get(this).find(serverId) ?: return
        (viewPager.adapter as? VideoAdapter)?.releaseAll()

        val token = ++scanToken
        showScanning(getString(R.string.scanning_lan_format, 0))
        AppLog.i(
            "Scan",
            "lan scan start server=${server.label.ifBlank { server.host }} " +
                "host=${server.host} path=/$path"
        )

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
                    showVideos(it)
                }.onFailure {
                    val message = SmbErrors.message(this, it)
                    AppLog.e("Scan", "lan scan failed: $message", it)
                    showError(message)
                }
            }
        }.start()
    }

    // --------------------------------------------------------------- playlist

    private fun showVideos(found: List<Pair<String, Uri>>) {
        if (found.isEmpty()) {
            Toast.makeText(this, R.string.no_videos, Toast.LENGTH_SHORT).show()
            return
        }

        videoFiles = ArrayList(found)
        landing.visibility = View.GONE
        viewPager.visibility = View.VISIBLE
        hud.visibility = View.VISIBLE

        val adapter = VideoAdapter(videoFiles)
        viewPager.adapter = adapter
        viewPager.post { adapter.onPageSelected(0) }
        updateHud(0)
    }

    private fun showScanning(message: String) {
        landing.visibility = View.VISIBLE
        viewPager.visibility = View.GONE
        hud.visibility = View.GONE
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
        landing.visibility = View.VISIBLE
        viewPager.visibility = View.GONE
        hud.visibility = View.GONE
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun updateHud(position: Int) {
        if (position < 0 || position >= videoFiles.size) return
        hudName.text = videoFiles[position].first
        hudCounter.text = getString(R.string.counter_format, position + 1, videoFiles.size)
    }

    override fun onPause() {
        super.onPause()
        (viewPager.adapter as? VideoAdapter)?.pauseCurrent()
    }

    override fun onResume() {
        super.onResume()
        (viewPager.adapter as? VideoAdapter)?.resumeCurrent()
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
    }
}