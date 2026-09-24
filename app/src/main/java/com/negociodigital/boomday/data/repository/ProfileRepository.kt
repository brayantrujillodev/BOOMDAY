package com.negociodigital.boomday.data.repository

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.negociodigital.boomday.data.auth.GoogleAuthConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(
    private val auth: FirebaseAuth
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