package com.negociodigital.boomday.ui.util

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestoreException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Criterio común (Feed/Explore/Ranking) para distinguir "sin conexión" de error genérico.
 * El texto crudo del SDK (p. ej. "Failed to get document because the client is offline.")
 * nunca se muestra al usuario: la UI elige un string en español según este resultado.
 */
fun Throwable.isOfflineError(): Boolean =
    this is IOException || this is FirebaseNetworkException ||
        (this is FirebaseFirestoreException &&
            (code == FirebaseFirestoreException.Code.UNAVAILABLE ||
                code == FirebaseFirestoreException.Code.DEADLINE_EXCEEDED))

/**
 * Contador de publicaciones exitosas en este proceso. UploadFlowScreen lo incrementa al
 * llegar a Success; el Ranking lo compara con el valor de su última carga para recargar al
 * volver (sin polling ni arquitectura nueva).
 */
object PublishSignal {
    private val counter = AtomicInteger(0)
    val version: Int get() = counter.get()
    fun bump() { counter.incrementAndGet() }
}
