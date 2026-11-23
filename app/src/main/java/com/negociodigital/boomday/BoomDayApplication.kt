package com.negociodigital.boomday

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class BoomDayApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Gancho de entorno local: NO-OP en debug/release; en el build type "local"
        // apunta Firebase a los emuladores (fail-closed si el proyecto no es demo-boomday).
        LocalEnvironment.init(this)

        // Inicializar Timber (siempre activo)
        Timber.plant(Timber.DebugTree())

        Timber.d("BoomDayApplication: Aplicación iniciada")
    }
}