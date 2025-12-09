package com.negociodigital.boomday

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Home : Screen("home")
    object Upload : Screen("upload")
    object Profile : Screen("profile")
    object Ranking : Screen("ranking")
}