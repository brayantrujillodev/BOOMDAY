package com.negociodigital.boomday.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun ProfileScreen(
    onLogoutSuccess: () -> Unit,
    onAccountDeleted: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val reauthIntent by viewModel.reauthIntent.collectAsState()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Launcher para el Google Sign-In de reautenticación (mismo patrón que el login en
    // NavGraph.kt): Firebase exige un login reciente antes de dejar borrar la cuenta.
    val reauthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.handleReauthResult(result.data)
    }

    LaunchedEffect(reauthIntent) {
        reauthIntent?.let { intent ->
            reauthLauncher.launch(intent)
            viewModel.clearReauthIntent()
        }
    }

    // 🔥 Detectar logout
    LaunchedEffect(state.isLoggedOut) {
        if (state.isLoggedOut) {
            onLogoutSuccess()
        }
    }

    LaunchedEffect(state.isAccountDeleted) {
        if (state.isAccountDeleted) {
            onAccountDeleted()
        }
    }

    when {
        state.isLoading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        state.user == null -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No hay usuario logueado")
            }
        }

        else -> {
            val user = state.user

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = user?.displayName ?: "Sin nombre",
                    style = MaterialTheme.typography.headlineMedium
                )

                Text(
                    text = user?.email ?: "Sin email"
                )

                Button(
                    onClick = { viewModel.logout() },
                    enabled = !state.isDeleting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Cerrar sesión")
                }

                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    enabled = !state.isDeleting,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Eliminar cuenta")
                }

                if (state.isDeleting) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text("Eliminando cuenta...")
                    }
                }

                state.error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (showDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = { Text("Eliminar cuenta") },
                    text = {
                        Text(
                            "Esta acción es permanente: se borrarán tu perfil y todos tus " +
                                "videos. No se puede deshacer."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showDeleteConfirm = false
                            viewModel.deleteAccount()
                        }) {
                            Text("Eliminar", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm = false }) {
                            Text("Cancelar")
                        }
                    }
                )
            }
        }
    }
}
