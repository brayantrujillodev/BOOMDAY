package com.negociodigital.boomday.di

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.ktx.storage
import com.negociodigital.boomday.data.repository.AuthRepository
import com.negociodigital.boomday.data.repository.GoogleAuthRepository
import com.negociodigital.boomday.data.repository.ProfileRepository
import com.negociodigital.boomday.data.repository.StorageRepository
import com.negociodigital.boomday.data.repository.UserRepository
import com.negociodigital.boomday.data.repository.VideoRepository
import com.negociodigital.boomday.domain.usecase.GoogleSignInUseCase
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

    // ✅ CORREGIDO: AuthRepository solo necesita FirebaseAuth
    @Provides
    @Singleton
    fun provideAuthRepository(
        auth: FirebaseAuth
    ): AuthRepository {
        return AuthRepository(auth)
    }

    @Provides
    @Singleton
    fun provideGoogleAuthRepository(
        auth: FirebaseAuth
    ): GoogleAuthRepository {
        return GoogleAuthRepository(auth)
    }

    @Provides
    @Singleton
    fun provideUserRepository(
        firestore: FirebaseFirestore
    ): UserRepository {
        return UserRepository(firestore)
    }

    @Provides
    @Singleton
    fun provideProfileRepository(
        auth: FirebaseAuth
    ): ProfileRepository {
        return ProfileRepository(auth)
    }

    @Provides
    @Singleton
    fun provideVideoRepository(
        firestore: FirebaseFirestore,
        auth: FirebaseAuth
    ): VideoRepository {
        return VideoRepository(firestore, auth)
    }

    @Provides
    @Singleton
    fun provideStorageRepository(
        storage: FirebaseStorage,
        auth: FirebaseAuth
    ): StorageRepository {
        return StorageRepository(storage, auth)
    }

    // ==================== USE CASES ====================

    @Provides
    @Singleton
    fun provideGoogleSignInUseCase(
        googleAuthRepository: GoogleAuthRepository,
        userRepository: UserRepository
    ): GoogleSignInUseCase {
        return GoogleSignInUseCase(googleAuthRepository, userRepository)
    }
}