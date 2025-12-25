package com.negociodigital.boomday.ui.navigation

sealed class Screen(val route: String) {
    object Feed : Screen("feed")
    object Profile : Screen("profile")
}
