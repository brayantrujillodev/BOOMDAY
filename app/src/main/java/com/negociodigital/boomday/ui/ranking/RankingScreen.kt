package com.negociodigital.boomday.ui.ranking

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.negociodigital.boomday.R
import com.negociodigital.boomday.ui.util.formatCount
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * RankingScreen - Leaderboard diario de videos más vistos hoy en Neiva.
 *
 * Esta pantalla es el motor viral del producto (el screenshot del ranking es lo que la
 * gente comparte), así que prioriza legibilidad y contraste explícitos sobre depender del
 * esquema de color del theme: los colores de puesto/medalla se fijan aquí a propósito.
 *
 * Conectada a [RankingViewModel] (contrato ya cerrado, no se toca): consume
 * `rankingState` y muestra Loading/Success/Empty/Error tal como llega.
 */
@Composable
fun RankingScreen(viewModel: RankingViewModel = hiltViewModel()) {
    val rankingState by viewModel.rankingState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        RankingHeader()

        when (val state = rankingState) {
            RankingState.Loading -> RankingLoadingState()
            is RankingState.Success -> RankingList(ranking = state.ranking)
            RankingState.Empty -> RankingEmptyState()
            is RankingState.Error -> RankingErrorState(message = state.message)
        }
    }
}

@Composable
private fun RankingHeader() {
    val datePattern = stringResource(R.string.ranking_date_pattern)
    val today = remember(datePattern) {
        LocalDate.now().format(DateTimeFormatter.ofPattern(datePattern, Locale("es", "CO")))
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = Color(0xFFFFD700),
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.ranking_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${stringResource(R.string.ranking_location_today)} · $today",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun RankingList(ranking: List<RankedVideo>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items = ranking, key = { it.video.videoId }) { rankedVideo ->
            RankingCard(rankedVideo = rankedVideo)
        }
    }
}

@Composable
private fun RankingCard(rankedVideo: RankedVideo) {
    val position = rankedVideo.position
    val video = rankedVideo.video

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RankingPositionBadge(position = position)

            Spacer(modifier = Modifier.width(12.dp))

            AsyncImage(
                model = video.userAvatar,
                contentDescription = stringResource(R.string.ranking_avatar_cd, video.userName),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.userName,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.ranking_views_format, formatCount(video.views)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (position <= 3) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = medalColor(position),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/**
 * Círculo grande (56-64dp) con medalla oro/plata/bronce para el top 3. Colores explícitos
 * (no derivados del theme) para garantizar buen contraste día/noche en captura de pantalla.
 */
@Composable
private fun RankingPositionBadge(position: Int) {
    val isTopThree = position <= 3
    val backgroundColor = if (isTopThree) medalColor(position) else Color(0xFF2D2D3A)
    val textColor = Color.White

    Box(
        modifier = Modifier
            .size(60.dp)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "#$position",
            fontWeight = FontWeight.ExtraBold,
            fontSize = if (isTopThree) 20.sp else 18.sp,
            color = textColor
        )
    }
}

private fun medalColor(position: Int): Color = when (position) {
    1 -> Color(0xFFFFD700) // Oro
    2 -> Color(0xFFC0C0C0) // Plata
    3 -> Color(0xFFCD7F32) // Bronce
    else -> Color(0xFF2D2D3A)
}

@Composable
private fun RankingLoadingState() {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            userScrollEnabled = false
        ) {
            items(count = 6) {
                RankingSkeletonCard()
            }
        }
        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
private fun RankingSkeletonCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(18.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.3f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
                )
            }
        }
    }
}

@Composable
private fun RankingEmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(text = "🏆", fontSize = 72.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.ranking_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.ranking_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Nota: [RankingViewModel] no expone hoy una función pública de refresh/retry en su
 * contrato (a diferencia de FeedViewModel.refreshFeed()), así que este estado no ofrece
 * un botón "Reintentar" — solo el mensaje. Si se requiere reintentar manualmente, hace
 * falta agregar esa función al ViewModel primero.
 */
@Composable
private fun RankingErrorState(message: String) {
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
                text = stringResource(R.string.ranking_error_title),
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
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.ranking_error_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
