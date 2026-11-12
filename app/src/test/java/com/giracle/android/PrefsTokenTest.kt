package com.giracle.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.giracle.android.data.Prefs
import com.giracle.android.data.TokenCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** トークンの暗号化保存 (#27)。端末の鍵の代わりに、中身の分かる偽の暗号で保存の手順を確かめる */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrefsTokenTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun sp(name: String) = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)

    private class FakeCipher(var works: Boolean = true) : TokenCipher {
        override fun encrypt(plain: String) = if (works) "v1:" + plain.reversed() else null
        override fun decrypt(stored: String) = if (works) stored.removePrefix("v1:").reversed() else null
    }

    @Test fun storesEncrypted() {
        val p = Prefs(ctx, "t1", FakeCipher())
        p.token = "secret"
        assertEquals("v1:terces", sp("t1").getString("tokenEnc", null))
        assertFalse(sp("t1").contains("token"))
        // 作り直しても読める (復号)
        val p2 = Prefs(ctx, "t1", FakeCipher())
        assertTrue(p2.hasToken)
        assertEquals("secret", p2.token)
    }

    @Test fun migratesPlainToken() {
        sp("t2").edit().putString("token", "old").commit()
        val p = Prefs(ctx, "t2", FakeCipher())
        assertTrue(p.hasToken)
        assertEquals("old", p.token)
        assertEquals("v1:dlo", sp("t2").getString("tokenEnc", null))
        assertFalse(sp("t2").contains("token"))
    }

    @Test fun lostKeyMeansSignedOut() {
        Prefs(ctx, "t3", FakeCipher()).token = "secret"
        val p = Prefs(ctx, "t3", FakeCipher(works = false))
        assertNull(p.token)
        assertFalse(p.hasToken)
    }

    @Test fun fallsBackToPlainWhenKeystoreUnavailable() {
        val p = Prefs(ctx, "t4", FakeCipher(works = false))
        p.token = "x"
        assertEquals("x", sp("t4").getString("token", null))
        assertEquals("x", Prefs(ctx, "t4", FakeCipher(works = false)).token)
    }

    @Test fun clearSessionRemovesBoth() {
        val p = Prefs(ctx, "t5", FakeCipher())
        p.token = "secret"
        p.clearSession()
        assertFalse(p.hasToken)
        assertFalse(Prefs(ctx, "t5", FakeCipher()).hasToken)
    }
}
