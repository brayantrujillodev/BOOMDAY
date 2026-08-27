package com.negociodigital.boomday.ui.upload

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.negociodigital.boomday.R

/**
 * Pantalla de revisión del video grabado/importado (estado [UploadState.Recorded]).
 *
 * Usa una instancia de ExoPlayer propia de esta pantalla (creada con [remember], liberada
 * en `DisposableEffect(Unit) { onDispose { player.release() } }`) — no la instancia
 * compartida del feed, ver regla de una sola instancia reutilizada por pantalla de lista.
 *
 * Título/descripción/hashtags viven como estado local del composable (no en el
 * ViewModel): solo se envían al confirmar, como parámetros de `confirmUpload`.
 */
@Composable
internal fun VideoReviewScreen(
    recorded: UploadState.Recorded,
    onDiscard: () -> Unit,
    onSubmit: (title: String, description: String, hashtags: List<String>) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(recorded.uri))
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var hashtagsInput by remember { mutableStateOf("") }

    val durationSeconds = (recorded.durationMs / 1000L).toInt()
    val isTooShort = recorded.durationMs < UploadLimits.MIN_DURATION_MS
    val isTooLong = recorded.durationMs > UploadLimits.MAX_DURATION_MS
    val canSubmit = title.isNotBlank() && !isTooShort && !isTooLong

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(9f / 16f)) {
            AndroidView(
                factory = {
                    PlayerView(context).apply {
                        this.player = player
                        useController = false
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            IconButton(
                onClick = {
                    player.stop()
                    onClose()
                },
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
            ) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = stringResource(R.string.upload_close_cd))
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.upload_review_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.upload_review_duration_label, durationSeconds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isTooShort) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.upload_review_too_short_warning,
                        UploadLimits.MIN_DURATION_SECONDS
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (isTooLong) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.upload_review_too_long_warning,
                        UploadLimits.MAX_DURATION_SECONDS
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { if (it.length <= UploadLimits.MAX_TITLE_LENGTH) title = it },
                label = { Text(stringResource(R.string.upload_review_title_label)) },
                placeholder = { Text(stringResource(R.string.upload_review_title_placeholder)) },
                supportingText = {
                    Text(stringResource(R.string.upload_review_char_counter, title.length, UploadLimits.MAX_TITLE_LENGTH))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { if (it.length <= UploadLimits.MAX_DESCRIPTION_LENGTH) description = it },
                label = { Text(stringResource(R.string.upload_review_description_label)) },
                placeholder = { Text(stringResource(R.string.upload_review_description_placeholder)) },
                supportingText = {
                    Text(stringResource(R.string.upload_review_char_counter, description.length, UploadLimits.MAX_DESCRIPTION_LENGTH))
                },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = hashtagsInput,
                onValueChange = { hashtagsInput = it },
                label = { Text(stringResource(R.string.upload_review_hashtags_label)) },
                placeholder = { Text(stringResource(R.string.upload_review_hashtags_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.upload_review_hashtags_helper),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        player.stop()
                        onDiscard()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.upload_review_discard_button))
                }
                Button(
                    onClick = {
                        val hashtags = parseHashtags(hashtagsInput)
                        onSubmit(title.trim(), description.trim(), hashtags)
                    },
                    enabled = canSubmit,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.upload_review_submit_button))
                }
            }
        }
    }
}

/**
 * Convierte el texto libre de hashtags (separados por comas y/o espacios) en una lista
 * de tags sin el prefijo "#" y sin entradas vacías.
 */
private fun parseHashtags(input: String): List<String> {
    return input
        .split(Regex("[,\\s]+"))
        .map { it.trim().removePrefix("#") }
        .filter { it.isNotEmpty() }
        .take(UploadLimits.MAX_HASHTAGS)
}
