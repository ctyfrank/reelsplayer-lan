package com.reelsplayer.lan

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.reelsplayer.lan.logging.AppLog
import com.reelsplayer.lan.logging.UriDiagnostics
import com.reelsplayer.lan.player.PlaybackEngine
import com.reelsplayer.lan.ui.LogActivity

class VideoAdapter(
    private val videos: List<Pair<String, Uri>>
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

    private val attachedHolders = mutableMapOf<Int, VideoViewHolder>()
    private val playerPositions = HashMap<ExoPlayer, Int>()

    /** Released players kept for the next page, so a swipe does not pay ExoPlayer construction. */
    private val pooledPlayers = ArrayDeque<ExoPlayer>(PLAYER_POOL_SIZE)

    private var currentPosition = -1
    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null
    private var userPaused = false

    class VideoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val playerView: PlayerView = view.findViewById(R.id.playerView)
        val pauseIcon: ImageView = view.findViewById(R.id.pauseIcon)
        val speedLabel: TextView = view.findViewById(R.id.speedLabel)
        val progressFill: View = view.findViewById(R.id.progressFill)
        var player: ExoPlayer? = null
        var pendingHideRunnable: Runnable? = null
        var longPressRunnable: Runnable? = null
        var isSpeedMode = false
        var attachedPosition: Int = RecyclerView.NO_POSITION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video, parent, false)
        return VideoViewHolder(view)
    }

    @OptIn(UnstableApi::class)
    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        // no-op, managed in attach/detach
    }

    override fun getItemCount() = videos.size

    @OptIn(UnstableApi::class)
    override fun onViewAttachedToWindow(holder: VideoViewHolder) {
        super.onViewAttachedToWindow(holder)
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return
        val (name, uri) = videos[position]
        AppLog.d("Player", "bind item=$position name=$name")

        attachedHolders[position]?.let { old ->
            old.playerView.player = null
            old.player?.let { recyclePlayer(it) }
            old.player = null
        }

        val player = obtainPlayer(holder.itemView.context)
        holder.player = player
        holder.playerView.player = player

        playerPositions[player] = position
        // A pooled player still holds the previous clip, so always reset the media item.
        player.setMediaItem(MediaItem.fromUri(uri), /* resetPosition = */ true)
        player.repeatMode = ExoPlayer.REPEAT_MODE_ONE
        player.prepare()
        player.playWhenReady = (position == currentPosition)

        holder.attachedPosition = position
        attachedHolders[position] = holder

        setupTouchListener(holder)
    }

    @OptIn(UnstableApi::class)
    private fun obtainPlayer(context: android.content.Context): ExoPlayer =
        pooledPlayers.removeLastOrNull() ?: PlaybackEngine.newPlayer(context).also { player ->
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    val position = playerPositions[player] ?: return
                    val view = attachedHolders[position]?.itemView ?: return
                    val (name, uri) = videos.getOrNull(position) ?: Pair("?", null)
                    AppLog.e(
                        "Player",
                        buildString {
                            append("playback failed item=").append(position)
                            append(" name=").append(name)
                            append(" uri=").append(uri?.let { UriDiagnostics.describe(view.context, it) })
                            append(" code=").append(error.errorCodeName)
                            append(" pos=").append(player.currentPosition)
                            append(" duration=").append(player.duration)
                        },
                        error
                    )
                    Snackbar.make(view, R.string.playback_error, Snackbar.LENGTH_LONG)
                        .setAction(R.string.log_view_action) {
                            view.context.startActivity(Intent(view.context, LogActivity::class.java))
                        }
                        .show()
                }
            })
        }

    @OptIn(UnstableApi::class)
    private fun recyclePlayer(player: ExoPlayer) {
        playerPositions.remove(player)
        player.stop()
        player.clearMediaItems()
        player.playbackParameters = PlaybackParameters(1f)
        if (pooledPlayers.size < PLAYER_POOL_SIZE) {
            pooledPlayers.addLast(player)
        } else {
            player.release()
        }
    }

    private fun setupTouchListener(holder: VideoViewHolder) {
        // Click listener for tap-to-pause — works regardless of OnTouchListener return value
        holder.playerView.isClickable = true
        holder.playerView.setOnClickListener {
            if (!holder.isSpeedMode) handleTap(holder)
        }

        // Touch listener for x2 speed — always returns false so ViewPager2 can swipe
        holder.playerView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    holder.isSpeedMode = false
                    val inRightZone = event.x > v.width * 0.67f
                    if (inRightZone) {
                        val runnable = Runnable {
                            holder.isSpeedMode = true
                            // Now lock touch to us — ViewPager2 won't intercept anymore
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            holder.player?.playbackParameters = PlaybackParameters(2f)
                            holder.speedLabel.visibility = View.VISIBLE
                        }
                        holder.longPressRunnable = runnable
                        handler.postDelayed(runnable, 300)
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!holder.isSpeedMode) {
                        // Not yet in speed mode — cancel pending long press
                        holder.longPressRunnable?.let { handler.removeCallbacks(it) }
                        holder.longPressRunnable = null
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    holder.longPressRunnable?.let { handler.removeCallbacks(it) }
                    holder.longPressRunnable = null

                    if (holder.isSpeedMode) {
                        holder.player?.playbackParameters = PlaybackParameters(1f)
                        holder.speedLabel.visibility = View.GONE
                        holder.isSpeedMode = false
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
            }
            // Always false — let ViewPager2 handle swipes, let clickListener handle taps
            false
        }
    }

    private fun handleTap(holder: VideoViewHolder) {
        val p = holder.player ?: return
        holder.pendingHideRunnable?.let { r -> handler.removeCallbacks(r) }
        if (p.isPlaying) {
            p.pause()
            userPaused = true
            holder.pauseIcon.setImageResource(android.R.drawable.ic_media_pause)
            holder.pauseIcon.visibility = View.VISIBLE
        } else {
            p.play()
            userPaused = false
            holder.pauseIcon.setImageResource(android.R.drawable.ic_media_play)
            holder.pauseIcon.visibility = View.VISIBLE
            val hideRunnable = Runnable { holder.pauseIcon.visibility = View.GONE }
            holder.pendingHideRunnable = hideRunnable
            handler.postDelayed(hideRunnable, 400)
        }
    }

    @OptIn(UnstableApi::class)
    override fun onViewDetachedFromWindow(holder: VideoViewHolder) {
        super.onViewDetachedFromWindow(holder)
        val position = holder.attachedPosition
        holder.longPressRunnable?.let { handler.removeCallbacks(it) }
        holder.longPressRunnable = null
        holder.pendingHideRunnable?.let { handler.removeCallbacks(it) }
        holder.pendingHideRunnable = null
        holder.isSpeedMode = false
        holder.playerView.player = null
        holder.player?.let { recyclePlayer(it) }
        holder.player = null
        holder.pauseIcon.visibility = View.GONE
        holder.speedLabel.visibility = View.GONE
        holder.progressFill.scaleX = 0f
        if (position != RecyclerView.NO_POSITION) attachedHolders.remove(position)
        holder.attachedPosition = RecyclerView.NO_POSITION
    }

    @OptIn(UnstableApi::class)
    fun onPageSelected(position: Int) {
        val previousPosition = currentPosition
        currentPosition = position
        userPaused = false
        AppLog.d("Player", "page selected $position/${videos.size}")
        for ((pos, holder) in attachedHolders) {
            val player = holder.player ?: continue
            if (pos == position) {
                if (previousPosition != position) {
                    player.seekTo(0)
                }
                player.playWhenReady = true
                player.playbackParameters = PlaybackParameters(1f)
                holder.pauseIcon.visibility = View.GONE
                holder.speedLabel.visibility = View.GONE
            } else {
                player.playWhenReady = false
            }
        }
        startProgressUpdates()

        // Give the clip after the next one a head start: the adjacent page is already
        // prepared by ExoPlayer, and prefetching it would fight the player for the file.
        videos.getOrNull(position + 2)?.let { (_, uri) -> PlaybackEngine.prefetch(uri) }
    }

    private fun startProgressUpdates() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                val holder = attachedHolders[currentPosition] ?: return
                val player = holder.player ?: return
                val duration = player.duration
                if (duration > 0) {
                    val progress = player.currentPosition.toFloat() / duration.toFloat()
                    holder.progressFill.scaleX = progress
                }
                handler.postDelayed(this, 200)
            }
        }
        progressRunnable = runnable
        handler.post(runnable)
    }

    fun pauseCurrent() {
        attachedHolders[currentPosition]?.player?.pause()
        progressRunnable?.let { handler.removeCallbacks(it) }
    }

    fun resumeCurrent() {
        val holder = attachedHolders[currentPosition] ?: return
        if (!userPaused) {
            holder.player?.playWhenReady = true
            holder.pauseIcon.visibility = View.GONE
        }
        startProgressUpdates()
    }

    fun releaseAll() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
        for ((_, holder) in attachedHolders) {
            holder.longPressRunnable?.let { handler.removeCallbacks(it) }
            holder.pendingHideRunnable?.let { handler.removeCallbacks(it) }
            holder.playerView.player = null
            holder.player?.let { recyclePlayer(it) }
            holder.player = null
        }
        attachedHolders.clear()
        playerPositions.clear()
        while (pooledPlayers.isNotEmpty()) {
            pooledPlayers.removeLast().release()
        }
        handler.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val PLAYER_POOL_SIZE = 2
    }
}