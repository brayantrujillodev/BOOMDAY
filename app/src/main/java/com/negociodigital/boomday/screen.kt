package com.negociodigital.boomday

sealed class Screen(val route: String) {
    object Feed : Screen("feed")
    object Profile : Screen("profile")
}
