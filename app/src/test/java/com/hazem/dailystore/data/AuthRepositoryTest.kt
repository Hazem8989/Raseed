package com.hazem.dailystore.data

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AuthRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var auth: AuthRepository

    @Before fun setUp() {
        database = AppDatabase.getInstance(RuntimeEnvironment.getApplication())
        auth = AuthRepository(RuntimeEnvironment.getApplication())
        auth.ensureInitialAdmin()
    }

    @After fun tearDown() { database.close() }

    @Test fun initialManagerCanAuthenticateAndPasswordIsNotStoredPlainText() {
        val user = auth.authenticate("admin", "1234")
        assertNotNull(user)
        assertEquals("ADMIN", user!!.roleKey)
        assertTrue(user.mustChangePassword)
        database.readableDatabase.rawQuery(
            "SELECT password_hash,password_salt FROM users WHERE id=?", arrayOf(user.id)
        ).use {
            assertTrue(it.moveToFirst())
            assertNotEquals("1234", it.getString(0))
            assertTrue(it.getString(0).isNotBlank())
            assertTrue(it.getString(1).isNotBlank())
        }
        assertNull(auth.authenticate("admin", "wrong"))
    }

    @Test fun managerCanCreateCashierButCashierCannotCreateUsers() {
        val admin = auth.authenticate("admin", "1234")!!
        auth.changePassword(admin.id, "1234", "ManagerPass2026")
        val cashierId = auth.createUser(admin.roleKey, admin.id,
            "cashier1", "موظف الصندوق", "Cashier5678", "CASHIER")
        assertEquals("CASHIER", auth.authenticate("cashier1", "Cashier5678")!!.roleKey)
        try {
            auth.createUser("CASHIER", cashierId, "cashier2", "موظف آخر", "Cashier6789", "CASHIER")
            fail("Cashier must not create users")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun temporaryAdminPasswordMustBeChangedBeforeSessionCanBeRestored() {
        val admin = auth.authenticate("admin", "1234")!!
        assertTrue(admin.mustChangePassword)
        try {
            auth.changePassword(admin.id, "wrong", "SecurePass2026")
            fail("Wrong current password must be rejected")
        } catch (_: IllegalArgumentException) { }
        auth.changePassword(admin.id, "1234", "SecurePass2026")
        val updated = auth.authenticate("admin", "SecurePass2026")!!
        assertFalse(updated.mustChangePassword)
        assertNull(auth.authenticate("admin", "1234"))
    }

    @Test fun weakPasswordsAreRejected() {
        val admin = auth.authenticate("admin", "1234")!!
        auth.changePassword(admin.id, "1234", "ManagerPass2026")
        try {
            auth.createUser(admin.roleKey, admin.id, "weakuser", "مستخدم ضعيف", "123456", "CASHIER")
            fail("Weak password must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun initialAdminBootstrapIsIdempotent() {
        auth.ensureInitialAdmin()
        auth.ensureInitialAdmin()
        database.readableDatabase.rawQuery("SELECT COUNT(*) FROM users WHERE username='admin'", null).use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
    }
}
