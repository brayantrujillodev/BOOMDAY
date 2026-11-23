package com.negociodigital.boomday.upload

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.storage.StorageException
import com.negociodigital.boomday.R
import com.negociodigital.boomday.data.model.UploadException
import com.negociodigital.boomday.ui.upload.uploadErrorMessageRes
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class UploadErrorMapperTest {
    private fun storage(code: Int) = mockk<StorageException>().also {
        every { it.errorCode } returns code
        every { it.cause } returns null
    }

    @Test
    fun mapsDomainAndNetworkErrors() {
        assertEquals(R.string.upload_error_not_logged_in, uploadErrorMessageRes(UploadException.NotLoggedIn()))
        assertEquals(R.string.upload_error_timeout, uploadErrorMessageRes(UploadException.Timeout()))
        assertEquals(R.string.upload_error_timeout, uploadErrorMessageRes(SocketTimeoutException()))
        assertEquals(R.string.upload_error_network, uploadErrorMessageRes(mockk<FirebaseNetworkException>()))
        assertEquals(R.string.upload_error_network, uploadErrorMessageRes(IOException()))
        assertEquals(R.string.upload_error_unknown, uploadErrorMessageRes(RuntimeException("raw")))
    }

    @Test
    fun mapsStorageRejections() {
        assertEquals(R.string.upload_error_permission, uploadErrorMessageRes(storage(StorageException.ERROR_NOT_AUTHORIZED)))
        assertEquals(R.string.upload_error_timeout, uploadErrorMessageRes(storage(StorageException.ERROR_RETRY_LIMIT_EXCEEDED)))
        assertEquals(R.string.upload_error_quota, uploadErrorMessageRes(storage(StorageException.ERROR_QUOTA_EXCEEDED)))
    }

    // Nota: el mapeo de FirebaseFirestoreException.Code no se prueba en JVM: el enum Code
    // falla al inicializarse sin runtime Android (ExceptionInInitializerError).
}
