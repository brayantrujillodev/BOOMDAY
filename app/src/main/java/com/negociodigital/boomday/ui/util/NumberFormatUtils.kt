package com.negociodigital.boomday.ui.util

/**
 * Formateo compacto de contadores (vistas, likes, comentarios, shares) usado en Feed y
 * Ranking. Función pura de utilidad de UI, sin dependencia de ningún modelo de dominio.
 */
fun formatCount(value: Int): String = when {
    value >= 1_000_000 -> "${value / 1_000_000}M"
    value >= 1_000 -> "${value / 1_000}K"
    else -> value.toString()
}
