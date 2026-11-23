package com.negociodigital.boomday

import android.app.Application

/** Version NO-OP: el entorno de emuladores solo existe en el build type "local". */
object LocalEnvironment {
    @Suppress("UNUSED_PARAMETER")
    fun init(app: Application) = Unit
}
