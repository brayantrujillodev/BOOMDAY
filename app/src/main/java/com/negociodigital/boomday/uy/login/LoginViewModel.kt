package com.negociodigital.boomday.uy.login

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    // Aquí irán las dependencias inyectadas
) : ViewModel() {

    fun testHilt() {
        println("✅ Hilt está funcionando correctamente")
    }
}