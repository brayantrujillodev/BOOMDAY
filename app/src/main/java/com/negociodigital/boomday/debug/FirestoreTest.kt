package com.negociodigital.boomday.debug

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

object FirestoreTest {

    private val firestore = FirebaseFirestore.getInstance()

    fun runTest() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Test 1: Escribir documento
                val testData = hashMapOf(
                    "message" to "BoomDay MVP - Test Connection",
                    "timestamp" to FieldValue.serverTimestamp(),
                    "version" to "1.0.0"
                )

                firestore.collection("test")
                    .document("connectionCheck")
                    .set(testData)
                    .await()

                println("✅ Firestore WRITE exitoso")

                // Test 2: Leer documento
                val document = firestore.collection("test")
                    .document("connectionCheck")
                    .get()
                    .await()

                if (document.exists()) {
                    println("✅ Firestore READ exitoso")
                    println("📄 Datos: ${document.data}")
                } else {
                    println("❌ Documento no encontrado")
                }

                // Test 3: Eliminar documento de prueba
                firestore.collection("test")
                    .document("connectionCheck")
                    .delete()
                    .await()

                println("✅ Firestore DELETE exitoso")
                println("🔥 Todas las operaciones Firestore funcionando correctamente!")

            } catch (e: Exception) {
                println("❌ Error en Firestore: ${e.message}")
                e.printStackTrace()
            }
        }
    }
}