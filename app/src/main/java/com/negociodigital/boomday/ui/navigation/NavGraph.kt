package com.negociodigital.boomday.ui.navigation

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
import com.negociodigital.boomday.ui.login.LoginScreen
import com.negociodigital.boomday.ui.login.LoginViewModel
import com.negociodigital.boomday.ui.main.MainScreen
import com.negociodigital.boomday.ui.splash.SplashScreen
import com.negociodigital.boomday.ui.upload.UploadFlowScreen

/**
 * Definición de rutas de navegación de nivel superior.
 *
 * Estas rutas representan los flujos principales de la aplicación:
 * - SPLASH: Pantalla inicial que verifica autenticación
 * - LOGIN: Flujo de autenticación con Google
 * - MAIN: Pantalla principal con navegación inferior (Feed + Profile)
 * - UPLOAD_FLOW: Flujo de grabación/importación y subida de video, a pantalla completa
 *   y fuera del NavBar (como Reels/TikTok). Se lanza desde el tab "Crear" de MainScreen.
 */
object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val MAIN = "main"
    const val UPLOAD_FLOW = "upload_flow"
}

/**
 * NavGraph - Grafo de Navegación Principal
 *
 * Responsabilidad ÚNICA:
 * - Definir y conectar las pantallas de nivel superior de la aplicación
 * - Gestionar la navegación entre flujos principales (Splash → Login → Main)
 * - Configurar transiciones y comportamiento del backstack
 *
 * NO gestiona:
 * - Navegación interna dentro de MainScreen (Feed ↔ Profile)
 * - Lógica de negocio o autenticación (delegado a ViewModels)
 * - UI específica de cada pantalla
 *
 * Flujo de navegación:
 * 1. SPLASH → Verifica si hay usuario autenticado
 *    ├─ Usuario autenticado → MAIN
 *    └─ No autenticado → LOGIN
 *
 * 2. LOGIN → Usuario inicia sesión con Google
 *    └─ Login exitoso → MAIN
 *
 * 3. MAIN → Pantalla contenedora con NavBar
 *    └─ Logout → LOGIN (limpia todo el backstack)
 *
 * @param navController Controlador de navegación de nivel superior
 * @param modifier Modificador opcional para el NavHost
 */
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

        // ═══════════════════════════════════════════════════════════════
        // SPLASH SCREEN
        // ═══════════════════════════════════════════════════════════════
        /**
         * Pantalla de entrada de la aplicación.
         *
         * Función:
         * - Mostrar branding durante 2.5 segundos
         * - Verificar estado de autenticación con Firebase
         * - Redirigir según resultado
         *
         * Navegación:
         * - Usuario autenticado → MAIN (elimina Splash del stack)
         * - Usuario no autenticado → LOGIN (elimina Splash del stack)
         */
        composable(Routes.SPLASH) {
            SplashScreen(
                onUserLoggedIn = {
                    navController.navigate(Routes.MAIN) {
                        // Eliminar Splash del backstack para evitar volver a ella
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
                onUserNotLoggedIn = {
                    navController.navigate(Routes.LOGIN) {
                        // Eliminar Splash del backstack
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                }
            )
        }

        // ═══════════════════════════════════════════════════════════════
        // LOGIN SCREEN
        // ═══════════════════════════════════════════════════════════════
        /**
         * Pantalla de autenticación con Google Sign-In.
         *
         * Función:
         * - Mostrar UI de login
         * - Gestionar flujo de Google Sign-In con Activity Result API
         * - Redirigir a MAIN tras autenticación exitosa
         *
         * Componentes clave:
         * - LoginViewModel: Gestiona lógica de autenticación
         * - Activity Result Launcher: Maneja resultado de Google Sign-In
         *
         * Flujo técnico:
         * 1. Usuario presiona botón "Continuar con Google"
         * 2. ViewModel prepara Intent de Google Sign-In
         * 3. Launcher abre Activity de Google
         * 4. Usuario selecciona cuenta
         * 5. ViewModel procesa resultado
         * 6. Si es exitoso → navega a MAIN
         */
        composable(Routes.LOGIN) {
            // Inyección de ViewModel con Hilt
            val viewModel: LoginViewModel = hiltViewModel()
            val context = LocalContext.current

            // Estados del ViewModel
            val isLoading by viewModel.isLoading.collectAsState()
            val errorMessage by viewModel.errorMessage.collectAsState()
            val isLoggedIn by viewModel.isLoggedIn.collectAsState()
            val signInIntent by viewModel.signInIntent.collectAsState()

            /**
             * Launcher para Google Sign-In.
             * Registra un callback para manejar el resultado de la Activity.
             */
            val launcher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    // Usuario seleccionó una cuenta → procesar credenciales
                    viewModel.handleGoogleSignInResult(result.data)
                } else {
                    // Usuario canceló o hubo error → limpiar intent
                    viewModel.clearSignInIntent()
                }
            }

            /**
             * Efecto: Lanzar Google Sign-In cuando el ViewModel emite un Intent.
             * Se ejecuta cada vez que signInIntent cambia de null a un Intent válido.
             */
            LaunchedEffect(signInIntent) {
                signInIntent?.let { intent ->
                    launcher.launch(intent)
                    viewModel.clearSignInIntent()
                }
            }

            /**
             * Efecto: Navegar a MAIN cuando el login es exitoso.
             * Limpia todo el backstack para evitar volver a Login con el botón atrás.
             */
            LaunchedEffect(isLoggedIn) {
                if (isLoggedIn) {
                    navController.navigate(Routes.MAIN) {
                        // Limpiar todo el stack de navegación
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                }
            }

            // Renderizar UI de Login
            LoginScreen(
                onGoogleSignInClick = {
                    viewModel.prepareGoogleSignIn(context)
                },
                isLoading = isLoading,
                errorMessage = errorMessage
            )
        }

        // ═══════════════════════════════════════════════════════════════
        // MAIN SCREEN (Feed + Profile con Bottom Navigation)
        // ═══════════════════════════════════════════════════════════════
        /**
         * Pantalla principal con navegación inferior.
         *
         * Función:
         * - Contenedor de las pantallas principales (Feed, Profile)
         * - Gestionar navegación interna con BottomNavigationBar
         * - Manejar logout y retorno a LOGIN
         *
         * Nota: La navegación interna (Feed ↔ Profile) se gestiona
         * dentro de MainScreen, NO aquí. Este NavGraph solo conecta
         * los flujos principales.
         *
         * Navegación desde aquí:
         * - Logout desde Profile → LOGIN (limpia todo el backstack)
         */
        composable(Routes.MAIN) {
            MainScreen(
                onLogoutSuccess = {
                    navController.navigate(Routes.LOGIN) {
                        // Limpiar TODO el backstack hasta la raíz
                        // Esto garantiza que al presionar "atrás" desde Login
                        // la app se cierre en lugar de volver a MAIN
                        popUpTo(0) { inclusive = true }
                    }
                },
                onNavigateToUpload = {
                    navController.navigate(Routes.UPLOAD_FLOW) {
                        // Evita apilar UPLOAD_FLOW dos veces si el usuario hace doble tap en "Crear"
                        launchSingleTop = true
                    }
                }
            )
        }

        // ═══════════════════════════════════════════════════════════════
        // UPLOAD FLOW (grabación/importación + revisión + subida)
        // ═══════════════════════════════════════════════════════════════
        /**
         * Flujo de subida de video, a pantalla completa fuera del NavBar (como Reels/TikTok).
         *
         * Función:
         * - Grabar video con CameraX o importarlo desde la galería
         * - Revisar, titular y confirmar la subida (UploadViewModel)
         * - Volver a MAIN al terminar (éxito) o al cerrar el flujo manualmente
         *
         * Nota: es una única ruta porque UploadState (Idle/Recorded/Uploading/Success/
         * Error/Cancelled) ya funciona como máquina de estados de la pantalla; no hace
         * falta un NavHost anidado con sub-rutas para camera/review.
         *
         * Navegación desde aquí:
         * - Fin del flujo (éxito o cierre manual) → MAIN, sin dejar el flujo en el backstack
         */
        composable(Routes.UPLOAD_FLOW) {
            UploadFlowScreen(
                onFinish = {
                    // Vuelve a la instancia existente de MAIN (preserva su estado) sin
                    // dejar UPLOAD_FLOW en el backstack.
                    navController.popBackStack(Routes.MAIN, inclusive = false)
                }
            )
        }
    }
}

/**
 * Notas de arquitectura:
 *
 * 1. Separación de responsabilidades:
 *    - Este NavGraph: Navegación de ALTO NIVEL (Splash, Login, Main)
 *    - MainScreen: Navegación INTERNA (Feed, Profile, futuras pestañas)
 *
 * 2. Gestión del backstack:
 *    - Splash se elimina inmediatamente tras redirigir
 *    - Login se elimina tras autenticación exitosa
 *    - Main se limpia completamente al hacer logout
 *
 * 3. Escalabilidad:
 *    - Para agregar más flujos principales (ej: Onboarding, Settings):
 *      → Agregar ruta en Routes
 *      → Agregar composable() aquí
 *    - Para agregar más pestañas en Main:
 *      → Editar MainScreen.kt, NO este archivo
 *
 * 4. Testing:
 *    - Cada ruta puede testearse independientemente
 *    - Los callbacks (onUserLoggedIn, onLogoutSuccess) permiten
 *      inyectar comportamiento en tests
 */