package com.negociodigital.boomday

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import timber.log.Timber

/**
 * Version REAL (solo build type "local"): apunta Auth/Firestore/Storage a los emuladores.
 * FAIL-CLOSED: si el projectId de la app no es "demo-boomday" lanza antes de tocar servicios.
 */
object LocalEnvironment {
    const val EXPECTED_PROJECT_ID = "demo-boomday"
    const val EMULATOR_HOST = "10.0.2.2"
    const val AUTH_PORT = 9099
    const val FIRESTORE_PORT = 8080
    const val STORAGE_PORT = 9199
    private const val TAG = "LocalEnvironment"

    @Volatile
    var emulatorActive: Boolean = false
        private set

    fun init(app: Application) {
        val projectId = FirebaseApp.getInstance().options.projectId
        check(projectId == EXPECTED_PROJECT_ID) {
            "LocalEnvironment: projectId '$projectId' != '$EXPECTED_PROJECT_ID'. Abortando (fail-closed)."
        }

        FirebaseAuth.getInstance().useEmulator(EMULATOR_HOST, AUTH_PORT)
        FirebaseFirestore.getInstance().useEmulator(EMULATOR_HOST, FIRESTORE_PORT)
        FirebaseStorage.getInstance().useEmulator(EMULATOR_HOST, STORAGE_PORT)
        emulatorActive = true

        // Timber aun puede no estar plantado (BoomDayApplication lo planta despues): log doble.
        val lines = listOf(
            "LOCAL EMULATOR: auth/firestore/storage -> $EMULATOR_HOST",
            "LOCAL EMULATOR projectId=$projectId",
            "LOCAL EMULATOR auth host=$EMULATOR_HOST:$AUTH_PORT",
            "LOCAL EMULATOR firestore host(effective)=${FirebaseFirestore.getInstance().firestoreSettings.host}",
            "LOCAL EMULATOR storage host=$EMULATOR_HOST:$STORAGE_PORT"
        )
        lines.forEach {
            Log.i(TAG, it)
            Timber.i(it)
        }
    }
}
