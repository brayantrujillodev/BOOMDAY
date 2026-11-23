package com.negociodigital.boomday.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.util.CustomClassMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Date

class UserMapperTest {

    private val ts = Timestamp(1_700_000_000L, 0)

    // Documento tal como lo escribe createOrUpdateUser (historico y actual).
    private fun historicDoc(blocked: Any? = listOf("a", "b")): Map<String, Any?> = mapOf(
        "uid" to "u1", "email" to "e@x.com", "name" to "N", "avatar" to null,
        "type" to "free", "createdAt" to ts, "updatedAt" to ts, "blockedUsers" to blocked
    )

    @Test
    fun oldPath_toObjectStyleMapping_failsOnTimestampCreatedAt() {
        // Caracterizacion del codigo VIEJO: toObject() usa CustomClassMapper.
        try {
            CustomClassMapper.convertToCustomClass(historicDoc(), User::class.java, null)
            fail("Se esperaba que Timestamp -> Long fallara en CustomClassMapper")
        } catch (e: RuntimeException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("Timestamp"))
        }
    }

    @Test
    fun newPath_historicTimestampDoc_isMappedWithBlockedUsers() {
        val u = UserMapper.fromMap(historicDoc())
        assertEquals(1_700_000_000_000L, u.createdAt)
        assertEquals(listOf("a", "b"), u.blockedUsers)
        assertEquals("u1", u.uid)
        assertEquals("free", u.type)
        assertEquals("", u.name.takeIf { it.isEmpty() } ?: "")
    }

    @Test
    fun currentLongCreatedAt() {
        val u = UserMapper.fromMap(mapOf("createdAt" to 123L, "uid" to "u"))
        assertEquals(123L, u.createdAt)
    }

    @Test
    fun numberAndDateCreatedAt() {
        assertEquals(5L, UserMapper.fromMap(mapOf("createdAt" to 5)).createdAt)
        assertEquals(7L, UserMapper.fromMap(mapOf("createdAt" to 7.9)).createdAt)
        assertEquals(9L, UserMapper.fromMap(mapOf("createdAt" to Date(9L))).createdAt)
    }

    @Test
    fun absentNullOrWeirdCreatedAt_doesNotThrow() {
        assertEquals(0L, UserMapper.fromMap(emptyMap()).createdAt)
        assertEquals(0L, UserMapper.fromMap(mapOf("createdAt" to null)).createdAt)
        assertEquals(0L, UserMapper.fromMap(mapOf("createdAt" to "ayer")).createdAt)
        assertEquals(0L, UserMapper.fromMap(mapOf("createdAt" to listOf(1))).createdAt)
    }

    @Test
    fun nullNameAndWeirdTypes_useDefaults() {
        val u = UserMapper.fromMap(mapOf("name" to null, "email" to 5, "type" to 3), "fallback")
        assertEquals("", u.name)
        assertEquals("", u.email)
        assertEquals("user", u.type)
        assertEquals("fallback", u.uid)
    }

    @Test
    fun blockedUsers_variants() {
        assertEquals(emptyList<String>(), UserMapper.blockedUsersFrom(null))
        assertEquals(emptyList<String>(), UserMapper.blockedUsersFrom(emptyList<String>()))
        assertEquals(listOf("x", "y"), UserMapper.blockedUsersFrom(listOf("x", "y")))
        assertEquals(listOf("x"), UserMapper.blockedUsersFrom(listOf("x", 3, null, "", mapOf("a" to 1))))
        assertEquals(emptyList<String>(), UserMapper.blockedUsersFrom("x"))
        assertEquals(emptyList<String>(), UserMapper.blockedUsersFrom(mapOf("x" to true)))
        assertEquals(emptyList<String>(), UserMapper.fromMap(mapOf("blockedUsers" to 7)).blockedUsers)
        assertEquals(emptyList<String>(), UserMapper.fromMap(emptyMap()).blockedUsers)
    }

    @Test
    fun blockSurvivesSimulatedWriteReadCycle() {
        // Doc historico (Timestamp, sin blockedUsers) -> arrayUnion simulado -> lectura.
        val stored = historicDoc(blocked = null).toMutableMap().also { it.remove("blockedUsers") }
        fun arrayUnion(field: String, v: String) {
            val cur = UserMapper.blockedUsersFrom(stored[field])
            stored[field] = if (v in cur) cur else cur + v
        }
        arrayUnion("blockedUsers", "villain")
        arrayUnion("blockedUsers", "villain") // idempotente
        val read = UserMapper.fromMap(stored)
        assertNotNull(read)
        assertEquals(listOf("villain"), read.blockedUsers)
        assertEquals(setOf("villain"), UserMapper.blockedUsersFrom(stored["blockedUsers"]).toSet())
        // Otros campos intactos (merge no pisa).
        assertEquals("e@x.com", read.email)
        assertEquals(ts, stored["createdAt"])
    }
}
