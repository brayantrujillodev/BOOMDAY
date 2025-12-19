package com.negociodigital.boomday.uy.splash

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import com.negociodigital.boomday.R

/**
 * Pantalla de Splash Screen para la aplicación BoomDay
 *
 * Esta pantalla se muestra al iniciar la aplicación y realiza las siguientes funciones:
 * - Muestra el branding de la aplicación (logo, nombre, tagline)
 * - Verifica el estado de autenticación del usuario con Firebase
 * - Redirige al flujo correspondiente según el estado de autenticación
 * - Se adapta a diferentes tamaños de pantalla y configuraciones de zoom
 *
 * @param onUserLoggedIn Callback que se ejecuta cuando el usuario está autenticado
 * @param onUserNotLoggedIn Callback que se ejecuta cuando el usuario no está autenticado
 */
@Composable
fun SplashScreen(
    onUserLoggedIn: () -> Unit,
    onUserNotLoggedIn: () -> Unit
) {
    // Instancia de Firebase Auth para verificar el estado de autenticación
    val auth = FirebaseAuth.getInstance()

    // Obtener configuración del dispositivo para responsive design
    val configuration = LocalConfiguration.current

    // Calcular si la pantalla es pequeña, mediana o grande
    val isSmallScreen = configuration.screenWidthDp < 360
    val isMediumScreen = configuration.screenWidthDp in 360..400

    // ============================================
    // CONFIGURACIÓN DE TAMAÑOS RESPONSIVOS
    // ============================================

    /**
     * Tamaño del logo adaptado al tamaño de pantalla
     * - Pantallas pequeñas (<360dp): 110dp
     * - Pantallas medianas (360-400dp): 140dp
     * - Pantallas grandes (>400dp): 160dp
     */
    val logoSize: Dp = when {
        isSmallScreen -> 110.dp
        isMediumScreen -> 140.dp
        else -> 160.dp
    }

    /**
     * Tamaño del título "BOOMDAY"
     * Usa sp (scale-independent pixels) para respetar configuración de zoom del usuario
     */
    val titleSize = when {
        isSmallScreen -> 30.sp
        isMediumScreen -> 34.sp
        else -> 38.sp
    }

    /**
     * Tamaño del subtítulo/tagline
     */
    val subtitleSize = when {
        isSmallScreen -> 12.sp
        isMediumScreen -> 13.sp
        else -> 14.sp
    }

    /**
     * Espaciado entre logo y título
     */
    val logoTitleSpacing = when {
        isSmallScreen -> 24.dp
        isMediumScreen -> 28.dp
        else -> 32.dp
    }

    // ============================================
    // ANIMACIONES
    // ============================================

    /**
     * Animación de fade-in para el logo
     * Oscila entre 40% y 100% de opacidad para crear un efecto de "respiración"
     * Duración: 2 segundos, con easing suave
     */
    val logoAlpha by rememberInfiniteTransition(label = "logoAlpha").animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    /**
     * Animación de escala sutil para el logo
     * Oscila entre 96% y 104% del tamaño original
     * Duración: 3 segundos para movimiento más lento y elegante
     */
    val logoScale by rememberInfiniteTransition(label = "logoScale").animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    /**
     * Animación de rotación muy sutil
     * Gira entre -3° y +3° para dar vida al logo
     * Duración: 4 segundos para movimiento casi imperceptible
     */
    val logoRotation by rememberInfiniteTransition(label = "logoRotation").animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rotation"
    )

    // ============================================
    // LÓGICA DE NAVEGACIÓN
    // ============================================

    /**
     * LaunchedEffect se ejecuta una sola vez al montar el composable
     * Espera 2.5 segundos y luego verifica el estado de autenticación
     */
    LaunchedEffect(Unit) {
        delay(2500) // Tiempo de visualización del splash

        // Verificar si hay un usuario autenticado
        if (auth.currentUser != null) {
            onUserLoggedIn()
        } else {
            onUserNotLoggedIn()
        }
    }

    // ============================================
    // UI DEL SPLASH SCREEN
    // ============================================

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F172A), // slate-900 (top)
                        Color(0xFF1E293B)  // slate-800 (bottom)
                    )
                )
            )
    ) {
        /**
         * Columna principal que contiene todos los elementos del splash
         * Usa weight(1f) para distribuir el espacio verticalmente
         */
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Espaciador superior flexible
            Spacer(modifier = Modifier.weight(1f))

            /**
             * Logo de BoomDay con animaciones aplicadas
             * - Alpha (opacidad) para efecto de respiración
             * - Scale (escala) para pulsación sutil
             * - Rotation (rotación) para movimiento orgánico
             */
            Image(
                painter = painterResource(id = R.drawable.logo_boomday),
                contentDescription = "Logo BoomDay",
                modifier = Modifier
                    .size(logoSize)
                    .graphicsLayer(
                        scaleX = logoScale,
                        scaleY = logoScale,
                        alpha = logoAlpha,
                        rotationZ = logoRotation
                    )
            )

            Spacer(modifier = Modifier.height(logoTitleSpacing))

            /**
             * Título principal: BOOMDAY
             * Usa FontWeight.Bold y letterSpacing para impacto visual
             */
            Text(
                text = "BOOMDAY",
                fontSize = titleSize,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 3.sp
            )

            Spacer(modifier = Modifier.height(if (isSmallScreen) 8.dp else 12.dp))

            /**
             * Tagline de la aplicación
             * Color gris claro para jerarquía visual
             */
            Text(
                text = "Conecta con oportunidades",
                fontSize = subtitleSize,
                fontWeight = FontWeight.Normal,
                color = Color(0xFFCBD5E1), // slate-300
                letterSpacing = 0.5.sp
            )

            // Espaciador inferior flexible
            Spacer(modifier = Modifier.weight(1f))

            /**
             * Indicador de carga
             * Muestra que la app está procesando la autenticación
             */
            Box(
                modifier = Modifier
                    .padding(bottom = if (isSmallScreen) 40.dp else 56.dp)
                    .size(if (isSmallScreen) 28.dp else 32.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF8B5CF6), // purple-500
                    strokeWidth = 2.5.dp
                )
            }
        }
    }
}