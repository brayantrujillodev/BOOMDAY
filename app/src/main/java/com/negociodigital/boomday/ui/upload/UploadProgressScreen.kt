package com.negociodigital.boomday.ui.upload

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.negociodigital.boomday.R
import kotlinx.coroutines.delay

private const val SUCCESS_AUTO_DISMISS_DELAY_MS = 1500L

/**
 * Estado [UploadState.Uploading]: barra de progreso real (0-100) reportada por el
 * ViewModel/UploadRepository, con opción de cancelar la subida en curso.
 */
@Composable
internal fun UploadingScreen(progress: Int, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.upload_uploading_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(24.dp))
        LinearProgressIndicator(
            progress = { progress / 100f },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.upload_uploading_progress_label, progress),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.upload_uploading_cancel_button))
        }
    }
}

/**
 * Estado [UploadState.Success]: confirmación breve y navegación automática de vuelta al
 * feed tras [SUCCESS_AUTO_DISMISS_DELAY_MS].
 */
@Composable
internal fun UploadSuccessScreen(onFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(SUCCESS_AUTO_DISMISS_DELAY_MS)
        onFinished()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "🎉", fontSize = 64.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.upload_success_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.upload_success_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Estado [UploadState.Error]: mensaje de error real del ViewModel, con reintento
 * (vuelve a revisión si el video sigue disponible localmente, o a cámara si no) o
 * descarte definitivo.
 */
@Composable
internal fun UploadErrorScreen(
    message: String,
    canRetryToReview: Boolean,
    onRetry: () -> Unit,
    onDiscard: () -> Unit
) {
    OutcomeScreen(
        emoji = "⚠️",
        title = stringResource(R.string.upload_error_title),
        message = message,
        retryLabel = stringResource(R.string.upload_error_retry_button),
        discardLabel = stringResource(R.string.upload_error_discard_button),
        canRetry = canRetryToReview,
        onRetry = onRetry,
        onDiscard = onDiscard
    )
}

/**
 * Estado [UploadState.Cancelled]: similar a Error, permite reintentar (si el video sigue
 * disponible) o descartar.
 */
@Composable
internal fun UploadCancelledScreen(
    canRetryToReview: Boolean,
    onRetry: () -> Unit,
    onDiscard: () -> Unit
) {
    OutcomeScreen(
        emoji = "🚫",
        title = stringResource(R.string.upload_cancelled_title),
        message = stringResource(R.string.upload_cancelled_message),
        retryLabel = stringResource(R.string.upload_cancelled_retry_button),
        discardLabel = stringResource(R.string.upload_cancelled_discard_button),
        canRetry = canRetryToReview,
        onRetry = onRetry,
        onDiscard = onDiscard
    )
}

@Composable
private fun OutcomeScreen(
    emoji: String,
    title: String,
    message: String,
    retryLabel: String,
    discardLabel: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onDiscard: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = emoji, fontSize = 64.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
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
        if (canRetry) {
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(retryLabel)
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
        OutlinedButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) {
            Text(discardLabel)
        }
    }
}
