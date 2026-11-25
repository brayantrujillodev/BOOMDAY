package com.negociodigital.boomday.ui.explore

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.negociodigital.boomday.R
import com.negociodigital.boomday.data.model.Video
import com.negociodigital.boomday.ui.util.formatCount

/**
 * ExploreScreen - Descubrimiento: búsqueda, Trending (top de hoy) y Recientes.
 * Consume [ExploreViewModel.uiState]; maneja carga, error con reintento y vacío.
 */
@Composable
fun ExploreScreen(
    viewModel: ExploreViewModel = hiltViewModel(),
    // TODO: no existe aún una ruta para abrir un video por id (el Feed no recibe argumentos
    //  y MainRoute no define un destino de detalle). Se conecta desde MainScreen cuando exista.
    onVideoClick: (String) -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val hasData by remember { derivedStateOf { state.trending.isNotEmpty() || state.recent.isNotEmpty() } }
    val onRetry = remember(viewModel) { viewModel::refresh }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onScreenResumed() }
    val onQueryChange = remember(viewModel) { viewModel::onQueryChange }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ExploreHeader(
            query = state.query,
            onQueryChange = onQueryChange
        )

        if (state.isLoading && hasData) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        when {
            hasData -> {
                if (state.error != null) {
                    ExploreErrorBanner(onRetry = onRetry)
                }
                ExploreContent(
                    trending = state.trending,
                    recent = state.recent,
                    onVideoClick = onVideoClick
                )
            }
            state.isLoading -> ExploreLoadingState()
            state.error != null -> ExploreErrorState(messageRes = state.error!!, onRetry = onRetry)
            else -> ExploreEmptyState()
        }
    }
}

@Composable
private fun ExploreErrorBanner(onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.explore_partial_error_message),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.explore_retry_button))
            }
        }
    }
}

@Composable
private fun ExploreHeader(
    query: String,
    onQueryChange: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.explore_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.explore_search_placeholder)) },
                leadingIcon = {
                    Icon(imageVector = Icons.Filled.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Filled.Clear,
                                contentDescription = stringResource(R.string.explore_clear_search_cd)
                            )
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )
        }
    }
}

@Composable
private fun ExploreContent(
    trending: List<Video>,
    recent: List<Video>,
    onVideoClick: (String) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (trending.isNotEmpty()) {
            item(key = "header_trending", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(
                    title = stringResource(R.string.explore_trending_title),
                    subtitle = stringResource(R.string.explore_trending_subtitle)
                )
            }
            items(items = trending, key = { "t_${it.videoId}" }) { video ->
                VideoThumbnailCard(video = video, onClick = onVideoClick)
            }
        }

        if (recent.isNotEmpty()) {
            item(key = "header_recent", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(
                    title = stringResource(R.string.explore_recent_title),
                    subtitle = stringResource(R.string.explore_recent_subtitle)
                )
            }
            items(items = recent, key = { "r_${it.videoId}" }) { video ->
                VideoThumbnailCard(video = video, onClick = onVideoClick)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun VideoThumbnailCard(video: Video, onClick: (String) -> Unit) {
    Card(
        onClick = { onClick(video.videoId) },
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = video.thumbnailUrl,
                contentDescription = stringResource(R.string.explore_thumbnail_cd, video.userName),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(8.dp)
            ) {
                Text(
                    text = video.userName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.explore_views_format, formatCount(video.views)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun ExploreLoadingState() {
    val cd = stringResource(R.string.explore_loading_cd)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = cd },
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ExploreErrorState(@StringRes messageRes: Int, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.explore_error_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(messageRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text(stringResource(R.string.explore_retry_button))
        }
    }
}

@Composable
private fun ExploreEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.explore_empty_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.explore_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
