package com.negociodigital.boomday.di

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.ktx.storage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ==================== FIREBASE INSTANCES ====================

    @Provides
    @Singleton
    fun provideFirebaseAuth(): FirebaseAuth {
        return FirebaseAuth.getInstance()
    }

    @Provides
    @Singleton
    fun provideFirestore(): FirebaseFirestore {
        return Firebase.firestore
    }

    @Provides
    @Singleton
    fun provideFirebaseStorage(): FirebaseStorage {
        return Firebase.storage
    }

    // ==================== REPOSITORIES ====================

    // Todos los repositorios (AuthRepository, GoogleAuthRepository, UserRepository,
    // VideoRepository, StorageRepository, UploadRepository, ProfileRepository) y
    // GoogleSignInUseCase declaran "@Singleton class X @Inject constructor(...)" en su
    // propio archivo — Hilt los resuelve solo con eso. Un @Provides manual acá para el
    // mismo tipo duplica el binding y falla en compilación ([Dagger/DuplicateBindings]);
    // no agregar ninguno salvo que la clase no tenga @Inject constructor propio.
}