package com.negociodigital.boomday.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.negociodigital.boomday.R
import com.negociodigital.boomday.data.model.Video
import com.negociodigital.boomday.ui.util.formatCount
import kotlinx.coroutines.delay

/**
 * FeedScreen - Feed vertical de videos efímeros (estilo TikTok), un video a pantalla
 * completa a la vez sobre un [VerticalPager].
 *
 * Reglas de recursos respetadas aquí:
 * - Una sola instancia de [ExoPlayer] para toda la lista (creada en [FeedPagerContent],
 *   NO por item): reasignamos su [MediaItem] cuando cambia la página activa.
 * - Las páginas no activas solo muestran su `thumbnailUrl` (Coil), nunca decodifican video
 *   fuera de pantalla.
 * - El player se libera en `DisposableEffect(Unit) { onDispose { player.release() } }` a
 *   nivel de pantalla, así que cambiar de tab en MainScreen (que saca FeedScreen de
 *   composición) libera el recurso de forma natural.
 */
@Composable
fun FeedScreen(
    onProfileClick: () -> Unit = {},
    onCommentsClick: (String) -> Unit = {},
    onShareClick: (String) -> Unit = {},
    viewModel: FeedViewModel = hiltViewModel()
) {
    val feedState by viewModel.feedState.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = feedState) {
            FeedState.Loading -> FeedLoadingState()

            is FeedState.Success -> FeedPagerContent(
                videos = state.videos,
                isRefreshing = false,
                onVideoViewed = viewModel::onVideoViewed,
                onProfileClick = onProfileClick,
                onCommentsClick = onCommentsClick,
                onShareClick = onShareClick
            )

            is FeedState.Refreshing -> FeedPagerContent(
                videos = state.videos,
                isRefreshing = true,
                onVideoViewed = viewModel::onVideoViewed,
                onProfileClick = onProfileClick,
                onCommentsClick = onCommentsClick,
                onShareClick = onShareClick
            )

            FeedState.Empty -> FeedEmptyState(onRefresh = viewModel::refreshFeed)

            is FeedState.Error -> FeedErrorState(
                message = state.message,
                onRetry = viewModel::refreshFeed
            )
        }
    }
}

@Composable
private fun FeedPagerContent(
    videos: List<Video>,
    isRefreshing: Boolean,
    onVideoViewed: (String) -> Unit,
    onProfileClick: () -> Unit,
    onCommentsClick: (String) -> Unit,
    onShareClick: (String) -> Unit
) {
    val context = LocalContext.current

    // Instancia ÚNICA de ExoPlayer para todo el pager. NUNCA crear una por item: en una
    // lista vertical eso revienta la memoria en scroll.
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    // Pausa (no libera) al ir a background sin salir del composable; retoma al volver.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player.pause()
                Lifecycle.Event.ON_RESUME -> if (player.mediaItemCount > 0) player.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pagerState = rememberPagerState(pageCount = { videos.size })

    // IMPORTANTE: la key del efecto de abajo NUNCA debe ser `videos` (la lista completa).
    // `Video` es un data class con igualdad estructural, y `views` de CUALQUIER video de la
    // lista se incrementa constantemente por escrituras ajenas (otro usuario ve el video,
    // da like, comenta, etc.). Si `videos` fuera key, cualquiera de esos cambios reinicia el
    // efecto: setMediaItem + seekTo(0) + play() de nuevo, y el video que el usuario lleva
    // rato mirando salta a 0:00 — ese era el bug bloqueante reportado.
    //
    // En vez de eso, derivamos únicamente el id del video activo en la página actual. Un
    // `String?` compara por igualdad estructural de su contenido, así que si `videos` cambia
    // de identidad pero el video en `currentPage` sigue siendo "el mismo" (mismo videoId,
    // views distinto), `currentVideoId` no cambia y el LaunchedEffect de abajo NO se reinicia.
    val currentVideoId = remember(pagerState.currentPage, videos) {
        videos.getOrNull(pagerState.currentPage)?.videoId
    }

    // Reasigna el MediaItem de la instancia única cuando el video ACTIVO realmente cambia
    // (cambio real de página, o la página actual pasó a apuntar a otro video tras un
    // refresh), y notifica la vista a los 2s.
    //
    // La key es `currentVideoId`, no `videos` ni el índice de página: por eso, al usar
    // `LaunchedEffect(currentVideoId)`, Compose SOLO relanza la corrutina (con un closure
    // fresco que ve el `videos` más reciente) cuando el video activo cambia de verdad.
    //
    // El pequeño `delay(150)` al inicio funciona como debounce: en un swipe rápido por
    // varias páginas, cada cambio de `currentVideoId` cancela la corrutina anterior (todavía
    // esperando esos 150ms) y arranca una nueva, así que `prepare()`/`play()` solo se ejecuta
    // para la página donde el usuario finalmente se detiene — nunca para las páginas de
    // tránsito. Esto es equivalente a `snapshotFlow { currentPage }.debounce(150)
    // .collectLatest { ... }`, pero evita la trampa de closure obsoleto que tendría ese
    // enfoque si el efecto se lanzara una sola vez con key `Unit` (en ese caso `videos`
    // quedaría fijo al valor de la primera composición, porque el efecto nunca se
    // reiniciaría para capturar un `videos` más nuevo).
    //
    // El `delay(2000)` posterior preserva el comportamiento original: si el usuario cambia
    // de página (o el video activo cambia) antes de los 2s, la corrutina se cancela y
    // `onVideoViewed` nunca se llama para esa página — se sigue disparando exactamente una
    // vez por video realmente visto (≥2s), sin duplicados ni pérdidas.
    LaunchedEffect(currentVideoId) {
        if (currentVideoId == null) return@LaunchedEffect

        delay(150)

        val video = videos.firstOrNull { it.videoId == currentVideoId } ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(video.videoUrl))
        player.seekTo(0)
        player.prepare()
        player.play()

        delay(2000)
        onVideoViewed(video.videoId)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { index -> videos[index].videoId }
        ) { page ->
            val video = videos[page]
            FeedPageItem(
                video = video,
                isActive = pagerState.currentPage == page,
                player = player,
                onProfileClick = onProfileClick,
                onCommentsClick = { onCommentsClick(video.videoId) },
                onShareClick = { onShareClick(video.videoId) }
            )
        }

        if (isRefreshing) {
            FeedRefreshingBanner(modifier = Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun FeedPageItem(
    video: Video,
    isActive: Boolean,
    player: ExoPlayer,
    onProfileClick: () -> Unit,
    onCommentsClick: () -> Unit,
    onShareClick: () -> Unit
) {
    val playerCd = stringResource(R.string.feed_player_cd)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (isActive) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        contentDescription = playerCd
                    }
                },
                update = { view -> view.player = player },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AsyncImage(
                model = video.thumbnailUrl,
                contentDescription = stringResource(R.string.feed_thumbnail_cd),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        // Scrim inferior para legibilidad del texto sobre el video/thumbnail.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))
                    )
                )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .padding(16.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.userName,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                if (video.title.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = video.title,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (video.hashtags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = video.hashtags.joinToString(separator = " ") { "#$it" },
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                FeedAvatarButton(
                    avatarUrl = video.userAvatar,
                    contentDescription = stringResource(R.string.feed_avatar_cd, video.userName),
                    onClick = onProfileClick
                )
                FeedActionButton(
                    icon = Icons.Filled.Favorite,
                    label = formatCount(video.likes),
                    contentDescription = stringResource(R.string.feed_likes_cd, formatCount(video.likes)),
                    onClick = {}
                )
                FeedEmojiActionButton(
                    emoji = "💬",
                    label = formatCount(video.comments),
                    contentDescription = stringResource(R.string.feed_comments_cd, formatCount(video.comments)),
                    onClick = onCommentsClick
                )
                FeedActionButton(
                    icon = Icons.Filled.Share,
                    label = formatCount(video.shares),
                    contentDescription = stringResource(R.string.feed_shares_cd, formatCount(video.shares)),
                    onClick = onShareClick
                )
            }
        }
    }
}

@Composable
private fun FeedActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    contentDescription: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(30.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun FeedAvatarButton(
    avatarUrl: String,
    contentDescription: String,
    onClick: () -> Unit
) {
    AsyncImage(
        model = avatarUrl,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.25f))
            .clickable(onClick = onClick)
    )
}

@Composable
private fun FeedEmojiActionButton(
    emoji: String,
    label: String,
    contentDescription: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
    ) {
        Text(text = emoji, fontSize = 26.sp, modifier = Modifier.padding(bottom = 2.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun FeedRefreshingBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(top = 12.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = Color.White
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.feed_refreshing_label),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * Skeleton vertical: insinúa el layout final (bloque de texto inferior + columna de
 * acciones) en vez de un spinner aislado en el centro.
 */
@Composable
private fun FeedLoadingState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
                .fillMaxWidth(0.65f)
        ) {
            SkeletonBlock(widthFraction = 0.5f, height = 20.dp)
            Spacer(modifier = Modifier.height(10.dp))
            SkeletonBlock(widthFraction = 1f, height = 14.dp)
            Spacer(modifier = Modifier.height(6.dp))
            SkeletonBlock(widthFraction = 0.7f, height = 14.dp)
        }
        CircularProgressIndicator(
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
private fun SkeletonBlock(widthFraction: Float, height: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f))
    )
}

@Composable
private fun FeedEmptyState(onRefresh: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(text = "🎬", fontSize = 72.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.feed_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.feed_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRefresh) {
                Text(stringResource(R.string.feed_empty_refresh_button))
            }
        }
    }
}

@Composable
private fun FeedErrorState(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(text = "⚠️", fontSize = 72.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.feed_error_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRetry) {
                Text(stringResource(R.string.feed_error_retry_button))
            }
        }
    }
}
