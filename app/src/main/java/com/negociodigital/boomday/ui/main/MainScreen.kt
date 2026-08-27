package com.negociodigital.boomday.ui.main

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.negociodigital.boomday.ui.explore.ExploreScreen
import com.negociodigital.boomday.ui.feed.FeedScreen
import com.negociodigital.boomday.ui.profile.ProfileScreen
import com.negociodigital.boomday.ui.ranking.RankingScreen

/**
 * Rutas internas de MainScreen.
 *
 * Estas rutas solo existen dentro de MainScreen y se navegan
 * a través del BottomNavigationBar.
 *
 * Estructura:
 * - Feed: Pantalla principal con videos
 * - Ranking: Leaderboard/clasificación
 * - Upload: Subir videos
 * - Explore: Buscar y descubrir
 * - Profile: Perfil del usuario
 */
sealed class MainRoute(
    val route: String,
    val title: String,
    val iconSelected: ImageVector,
    val iconUnselected: ImageVector
) {
    object Feed : MainRoute(
        route = "main_feed",
        title = "Inicio",
        iconSelected = Icons.Filled.Home,
        iconUnselected = Icons.Outlined.Home
    )

    object Ranking : MainRoute(
        route = "main_ranking",
        title = "Ranking",
        iconSelected = Icons.Filled.Star,
        iconUnselected = Icons.Outlined.Star
    )

    object Upload : MainRoute(
        route = "main_upload",
        title = "Crear",
        iconSelected = Icons.Filled.AddCircle,
        iconUnselected = Icons.Outlined.AddCircle
    )

    object Explore : MainRoute(
        route = "main_explore",
        title = "Explorar",
        iconSelected = Icons.Filled.Search,
        iconUnselected = Icons.Outlined.Search
    )

    object Profile : MainRoute(
        route = "main_profile",
        title = "Perfil",
        iconSelected = Icons.Filled.Person,
        iconUnselected = Icons.Outlined.Person
    )
}

/**
 * MainScreen - Pantalla contenedora con Bottom Navigation
 *
 * Responsabilidad ÚNICA:
 * - Mostrar BottomNavigationBar con 5 pestañas
 * - Gestionar navegación interna entre pantallas principales
 * - Mantener estado de navegación (backstack, animaciones)
 *
 * NO gestiona:
 * - Navegación de nivel superior (Splash, Login) → eso es NavGraph
 * - Lógica de negocio de cada pantalla → eso son los ViewModels
 *
 * Flujo:
 * 1. Usuario toca pestaña en NavBar
 * 2. Navega a la pantalla correspondiente
 * 3. Mantiene estado al cambiar de pestaña
 *
 * @param onLogoutSuccess Callback cuando el usuario cierra sesión desde Profile
 * @param onNavigateToUpload Callback cuando el usuario toca el tab "Crear". El flujo de
 *   grabación/subida vive fuera del NavBar (a pantalla completa, ver Routes.UPLOAD_FLOW
 *   en NavGraph.kt), así que este tab no navega dentro del NavHost interno: delega al
 *   NavController de nivel superior.
 */
@Composable
fun MainScreen(
    onLogoutSuccess: () -> Unit,
    onNavigateToUpload: () -> Unit
) {
    // Controlador de navegación interno (solo para MainScreen)
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Lista de pestañas en orden
    val items = listOf(
        MainRoute.Feed,
        MainRoute.Ranking,
        MainRoute.Upload,
        MainRoute.Explore,
        MainRoute.Profile
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                items.forEach { screen ->
                    // Verificar si la pestaña actual está seleccionada
                    val isSelected = currentDestination?.hierarchy?.any {
                        it.route == screen.route
                    } == true

                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = if (isSelected) {
                                    screen.iconSelected
                                } else {
                                    screen.iconUnselected
                                },
                                contentDescription = screen.title
                            )
                        },
                        label = { Text(screen.title) },
                        selected = isSelected,
                        onClick = {
                            if (screen == MainRoute.Upload) {
                                // El tab "Crear" no es un destino interno: abre el flujo de
                                // subida a pantalla completa (nivel superior, fuera del NavBar).
                                onNavigateToUpload()
                            } else {
                                navController.navigate(screen.route) {
                                    // Navegar a la raíz del grafo
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    // Evitar múltiples copias de la misma pantalla
                                    launchSingleTop = true
                                    // Restaurar estado al volver
                                    restoreState = true
                                }
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        // NavHost interno para las pantallas con NavBar
        NavHost(
            navController = navController,
            startDestination = MainRoute.Feed.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            // 🏠 FEED
            composable(MainRoute.Feed.route) {
                FeedScreen(
                    onProfileClick = {
                        navController.navigate(MainRoute.Profile.route)
                    }
                )
            }

            // 📊 RANKING
            composable(MainRoute.Ranking.route) {
                RankingScreen()
            }

            // ➕ UPLOAD
            // Nota: no se registra composable(MainRoute.Upload.route) aquí a propósito.
            // El tab "Crear" nunca navega a esta ruta interna (ver onClick arriba):
            // intercepta el click y delega a onNavigateToUpload() para abrir el flujo de
            // subida como ruta de nivel superior (Routes.UPLOAD_FLOW en NavGraph.kt).

            // 🔍 EXPLORE
            composable(MainRoute.Explore.route) {
                ExploreScreen()
            }

            // 👤 PROFILE
            composable(MainRoute.Profile.route) {
                ProfileScreen(
                    onLogoutSuccess = onLogoutSuccess
                )
            }
        }
    }
}

/**
 * Notas de arquitectura:
 *
 * 1. Navegación de dos niveles:
 *    - NavGraph (nivel superior): Splash → Login → MainScreen
 *    - MainScreen (nivel interno): Feed ↔ Ranking ↔ Upload ↔ Explore ↔ Profile
 *
 * 2. Gestión del estado:
 *    - saveState: Guarda el estado al cambiar de pestaña
 *    - restoreState: Restaura el estado al volver a una pestaña
 *    - launchSingleTop: Evita duplicar pantallas en el stack
 *
 * 3. Escalabilidad:
 *    - Para agregar más pestañas:
 *      → Crear objeto en MainRoute
 *      → Agregar a la lista `items`
 *      → Agregar composable() en el NavHost
 *    - Límite recomendado: 5 pestañas (buenas prácticas de UX)
 *
 * 4. Customización del NavBar:
 *    - Para cambiar colores: Modificar NavigationBar { colors = ... }
 *    - Para ocultar labels: Remover `label = { Text(...) }`
 *    - Para iconos personalizados: Usar painterResource en lugar de Icons
 */