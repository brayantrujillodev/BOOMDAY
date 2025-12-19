package com.negociodigital.boomday.debug

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage

object FirebaseTest {

    fun testConnection() {
        // Auth
        val auth = FirebaseAuth.getInstance()
        println("✅ Firebase Auth inicializado: ${auth.app.name}")

        // Firestore
        val firestore = FirebaseFirestore.getInstance()
        println("✅ Firestore inicializado: ${firestore.app.name}")

        // Storage
        val storage = FirebaseStorage.getInstance()
        println("✅ Storage inicializado: ${storage.app.name}")

        println("🔥 Firebase conectado correctamente!")
    }
}