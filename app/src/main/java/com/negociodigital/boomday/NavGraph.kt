package com.negociodigital.boomday

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.negociodigital.boomday.uy.auth.LoginScreen
import com.negociodigital.boomday.uy.auth.LoginViewModel
import com.negociodigital.boomday.uy.profile.ProfileScreen
import com.negociodigital.boomday.uy.feed.FeedScreen
import com.negociodigital.boomday.uy.splash.SplashScreen

object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val FEED = "feed"
    const val PROFILE = "profile"
}

@Composable
fun NavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Routes.SPLASH,
        modifier = modifier
    ) {

        // 1️⃣ SPLASH
        composable(Routes.SPLASH) {
            SplashScreen(
                onUserLoggedIn = {
                    navController.navigate(Routes.FEED) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
                onUserNotLoggedIn = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                }
            )
        }

        // 2️⃣ LOGIN
        composable(Routes.LOGIN) {
            val viewModel: LoginViewModel = hiltViewModel()
            val context = LocalContext.current

            val isLoading by viewModel.isLoading.collectAsState()
            val errorMessage by viewModel.errorMessage.collectAsState()
            val isLoggedIn by viewModel.isLoggedIn.collectAsState()
            val signInIntent by viewModel.signInIntent.collectAsState()

            // Launcher para Google Sign-In
            val launcher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    viewModel.handleGoogleSignInResult(result.data)
                } else {
                    // Usuario canceló
                    viewModel.clearSignInIntent()
                }
            }

            // Lanzar el Intent cuando esté listo
            LaunchedEffect(signInIntent) {
                signInIntent?.let { intent ->
                    launcher.launch(intent)
                    viewModel.clearSignInIntent()
                }
            }

            // Navegar cuando login sea exitoso
            LaunchedEffect(isLoggedIn) {
                if (isLoggedIn) {
                    navController.navigate(Routes.FEED) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                }
            }

            LoginScreen(
                onGoogleSignInClick = {
                    viewModel.prepareGoogleSignIn(context)
                },
                isLoading = isLoading,
                errorMessage = errorMessage
            )
        }

        // 3️⃣ FEED / HOME
        composable(Routes.FEED) {
            FeedScreen(
                onProfileClick = {
                    navController.navigate(Routes.PROFILE)
                }
            )
        }

        // 4️⃣ PROFILE
        composable(Routes.PROFILE) {
            ProfileScreen(
                onLogoutSuccess = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}