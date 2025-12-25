package com.negociodigital.boomday

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class BoomDayApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Inicializar Timber (siempre activo)
        Timber.plant(Timber.DebugTree())

        Timber.d("BoomDayApplication: Aplicación iniciada")
    }
}