package com.hazem.dailystore.data

/**
 * Test-only compatibility fixture. Production code deliberately has no default
 * administrator bootstrap; tests create an explicit, strong local admin instead.
 */
internal fun AuthRepository.ensureInitialAdmin(): String =
    createInitialAdmin("admin", "مدير الاختبار", "AdminTest12345")
