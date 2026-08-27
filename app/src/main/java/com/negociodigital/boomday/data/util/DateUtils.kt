package com.negociodigital.boomday.data.util

import java.time.LocalDate
import java.time.ZoneId

/**
 * Zona horaria de referencia del producto: BoomDay está enfocado en el lanzamiento de
 * Neiva, Colombia. Colombia no observa horario de verano, así que un ZoneId fijo es
 * seguro (no hay ambigüedad de offset a lo largo del año).
 */
private val BOGOTA_ZONE: ZoneId = ZoneId.of("America/Bogota")

/**
 * Única fuente de verdad para calcular el "día calendario" (zona horaria America/Bogota)
 * en formato ISO "yyyy-MM-dd" (ej. "2026-08-25"). Se usa como `Video.dayKey`: una clave de
 * igualdad que permite combinar `whereEqualTo` + `orderBy` en un campo distinto sin chocar
 * con la restricción de Firestore que exige que el primer `orderBy` recaiga sobre el mismo
 * campo que un filtro de desigualdad.
 *
 * IMPORTANTE: no dupliques esta lógica en otro sitio (p. ej. con
 * System.currentTimeMillis() + un offset manual calculado a mano) — usa siempre esta
 * función para que escritura (UploadRepository) y lectura (VideoRepository.getTopVideos)
 * queden consistentes con la misma zona horaria. `java.time.*` está disponible sin
 * coreLibraryDesugaring porque minSdk = 26 (API 26 ya trae java.time nativo).
 */
fun currentDayKeyBogota(): String = LocalDate.now(BOGOTA_ZONE).toString()
