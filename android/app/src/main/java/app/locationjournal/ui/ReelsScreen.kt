package app.locationjournal.ui

import android.net.Uri
import android.view.ViewGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.locationjournal.data.ReelMedia
import app.locationjournal.data.ReelStateEntity
import app.locationjournal.domain.REEL_CATALOG
import app.locationjournal.domain.Reel
import app.locationjournal.domain.formatClock
import java.util.Locale

private const val REEL_PREFS_ID = "__prefs"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReelsScreen(viewModel: JournalViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mediaState = viewModel.reelMedia.collectAsState()
    val statesState = viewModel.reelStates.collectAsState()
    val mediaMap = mediaState.value
    val states = statesState.value
    val reels = REEL_CATALOG.filter { mediaMap[it.id]?.video?.exists() == true }

    val prefs = states.firstOrNull { it.id == REEL_PREFS_ID }
    val initialIndex = remember { (prefs?.index ?: 0).coerceAtLeast(0) }
    val muted = prefs?.muted ?: true

    val player = remember(context) { ExoPlayer.Builder(context).build() }
    val pagerState = rememberPagerState(
        pageCount = {
            val current = mediaState.value
            REEL_CATALOG.count { reel -> current[reel.id]?.video?.exists() == true }
        },
    )
    var isPlaying by remember { mutableStateOf(false) }
    var restored by remember { mutableStateOf(false) }
    var resharedIds by remember { mutableStateOf(emptySet<String>()) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player.pause()
                Lifecycle.Event.ON_START -> {
                    if (player.playWhenReady && player.currentMediaItem != null) player.play()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }

    val activeReel = reels.getOrNull(pagerState.currentPage)
    DisposableEffect(activeReel?.id, mediaMap) {
        val media = activeReel?.let { mediaMap[it.id] }
        if (media != null && media.video.exists()) {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(media.video)))
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.volume = if (muted) 0f else 1f
            player.prepare()
            player.playWhenReady = true
        } else {
            player.stop()
        }
        onDispose { player.pause() }
    }

    LaunchedEffect(reels.size) {
        if (!restored && reels.isNotEmpty()) {
            restored = true
            pagerState.scrollToPage(initialIndex.coerceIn(0, reels.lastIndex))
        }
    }
    LaunchedEffect(restored, pagerState.currentPage) {
        if (restored && reels.isNotEmpty()) viewModel.setReelPrefs(pagerState.currentPage, muted)
    }
    LaunchedEffect(restored, activeReel?.id) {
        if (restored) activeReel?.let { viewModel.recordView(it.id) }
    }

    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        if (reels.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    "No local reels",
                    "Reel videos are bundled with the app and never downloaded. Once they are available, swipe through them here.",
                )
            }
        } else {
            VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val reel = reels[page]
                val reelState = states.firstOrNull { it.id == reel.id }
                ReelPage(
                    reel = reel,
                    media = mediaMap[reel.id],
                    reelState = reelState,
                    player = player,
                    active = page == pagerState.currentPage,
                    isPlaying = isPlaying,
                    muted = muted,
                    reshared = reel.id in resharedIds,
                    page = page,
                    total = reels.size,
                    viewModel = viewModel,
                    onToggleMute = { viewModel.setReelPrefs(page, !muted) },
                    onToggleReshare = {
                        if (reel.id in resharedIds) {
                            viewModel.undoReshare(reel.id)
                            resharedIds = resharedIds - reel.id
                        } else {
                            viewModel.reshare(reel.id)
                            resharedIds = resharedIds + reel.id
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ReelPage(
    reel: Reel,
    media: ReelMedia?,
    reelState: ReelStateEntity?,
    player: ExoPlayer,
    active: Boolean,
    isPlaying: Boolean,
    muted: Boolean,
    reshared: Boolean,
    page: Int,
    total: Int,
    viewModel: JournalViewModel,
    onToggleMute: () -> Unit,
    onToggleReshare: () -> Unit,
) {
    val commentFlow = remember(reel.id) { viewModel.reelComments(reel.id) }
    val userComments by commentFlow.collectAsState(initial = emptyList())
    var draft by remember { mutableStateOf("") }
    var showComments by remember { mutableStateOf(false) }
    var expandedCaption by remember { mutableStateOf(false) }

    val likes = displayCount(reelState?.likes, reel.likes)
    val views = displayCount(reelState?.views, reel.views)
    val shares = displayCount(reelState?.shares, reel.shares)
    val reshares = displayCount(reelState?.reshares, reel.reshares)
    val liked = reelState?.liked == true
    val saved = reelState?.saved == true
    val rows = remember(reel.comments, userComments) {
        reel.comments.map { ReelCommentRow(it.id, it.author, it.text, "seed", false) } +
            userComments.map { ReelCommentRow(it.id, it.author, it.text, formatClock(it.time), it.mine) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (media != null && media.video.exists()) {
            AndroidView(
                modifier = Modifier.fillMaxSize().clickable {
                    if (active) {
                        if (player.isPlaying) player.pause() else player.play()
                    }
                },
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        controllerAutoShow = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                update = { view -> view.player = if (active) player else null },
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Video is not available on this device", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Column(Modifier.align(Alignment.TopStart).padding(16.dp)) {
            Text(reel.place, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${page + 1} of $total",
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        IconButton(
            onClick = onToggleMute,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        ) {
            Icon(
                imageVector = if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                contentDescription = if (muted) "Unmute" else "Mute",
                tint = Color.White,
            )
        }

        if (active && !isPlaying) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Play",
                modifier = Modifier.align(Alignment.Center).size(56.dp),
                tint = Color.White.copy(alpha = 0.85f),
            )
        }

        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ReelAction(
                icon = if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                label = formatCount(likes),
                tint = if (liked) MaterialTheme.colorScheme.primary else Color.White,
                onClick = { viewModel.toggleLike(reel.id) },
            )
            ReelAction(
                icon = if (showComments) Icons.Default.Close else Icons.Default.Comment,
                label = rows.size.toString(),
                onClick = { showComments = !showComments },
            )
            ReelAction(
                icon = Icons.Default.Share,
                label = formatCount(shares),
                onClick = { viewModel.shareReel(reel.id) },
            )
            ReelAction(
                icon = Icons.Default.Repeat,
                label = formatCount(reshares),
                tint = if (reshared) MaterialTheme.colorScheme.primary else Color.White,
                onClick = onToggleReshare,
            )
            ReelAction(
                icon = if (saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                label = if (saved) "Saved" else "Save",
                tint = if (saved) MaterialTheme.colorScheme.primary else Color.White,
                onClick = { viewModel.toggleSave(reel.id) },
            )
        }

        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(reel.title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Sound: ${reel.sound}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
            Text(
                reel.caption,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expandedCaption) Int.MAX_VALUE else 2,
                modifier = Modifier.clickable { expandedCaption = !expandedCaption },
            )
            Text(
                "${formatCount(views)} views · ${formatCount(shares)} shares · ${formatCount(reshares)} reshares",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
            )

            if (showComments) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Comments (${rows.size})", style = MaterialTheme.typography.titleSmall)
                        LazyColumn(Modifier.heightIn(max = 200.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(rows, key = { it.id }) { row ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "${row.author} · ${row.timeLabel}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(row.text, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    if (row.mine) {
                                        IconButton(onClick = { viewModel.deleteReelComment(row.id) }) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Delete comment",
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = draft,
                                onValueChange = { draft = it },
                                label = { Text("Add a comment") },
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                            )
                            FilledTonalButton(
                                enabled = draft.isNotBlank(),
                                onClick = { viewModel.addReelComment(reel.id, draft); draft = "" },
                            ) {
                                Icon(Icons.Default.Send, contentDescription = "Post comment")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReelAction(icon: ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(4.dp),
    ) {
        Icon(icon, contentDescription = label, tint = tint)
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

private data class ReelCommentRow(
    val id: String,
    val author: String,
    val text: String,
    val timeLabel: String,
    val mine: Boolean,
)

private fun displayCount(value: Int?, fallback: Int): Int = value?.takeIf { it > 0 } ?: fallback

private fun formatCount(value: Int): String =
    if (value >= 1000) String.format(Locale.US, "%.1fk", value / 1000f) else value.toString()
