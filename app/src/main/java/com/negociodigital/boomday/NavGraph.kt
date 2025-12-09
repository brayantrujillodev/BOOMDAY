package com.negociodigital.boomday

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.negociodigital.boomday.uy.home.HomeScreen
import com.negociodigital.boomday.uy.login.LoginScreen

@Composable
fun NavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Login.route
    ) {
        composable(Screen.Login.route) {
            LoginScreen(navController)
        }

        composable(Screen.Home.route) {
            HomeScreen(navController)
        }

        // Las otras pantallas se agregarán después
    }
}