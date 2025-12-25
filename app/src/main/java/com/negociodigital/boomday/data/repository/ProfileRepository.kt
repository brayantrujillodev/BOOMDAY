package com.negociodigital.boomday.data.repository

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.negociodigital.boomday.data.auth.GoogleAuthConfig

class ProfileRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {

    fun getCurrentUser(): FirebaseUser? {
        return auth.currentUser
    }

    fun signOut(context: Context) {
        // Firebase
        auth.signOut()

        // Google
        GoogleAuthConfig.signOut(context)
    }
}