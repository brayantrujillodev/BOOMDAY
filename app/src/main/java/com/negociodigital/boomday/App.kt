package com.negociodigital.boomday

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // Firebase y otras inicializaciones irán aquí
    }
}