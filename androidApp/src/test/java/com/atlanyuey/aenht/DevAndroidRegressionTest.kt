package com.atlanyuey.aenht

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.atlanyuey.aenht.core.SecureStore
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DevAndroidRegressionTest {
    private fun failedWrite(failEveryCommit: Boolean): Pair<Boolean, String?> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("audit_fault_$failEveryCommit", Context.MODE_PRIVATE)
        preferences.edit().putString("old", "synthetic-encrypted-value").commit()
        // Model SharedPreferences' memory-first commit: mutate memory, report a disk failure.
        // No device storage failure or real credentials are used.
        var commits = 0
        val wrapped = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            if (method.name != "edit") method.invoke(preferences, *(args ?: emptyArray())) else {
                val editor = preferences.edit()
                lateinit var proxyEditor: SharedPreferences.Editor
                proxyEditor = Proxy.newProxyInstance(
                    SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java),
                ) { _, operation, parameters ->
                    if (operation.name == "commit") {
                        val written = editor.commit(); commits++
                        if (failEveryCommit || commits == 1) false else written
                    }
                    else {
                        val result = operation.invoke(editor, *(parameters ?: emptyArray()))
                        if (result is SharedPreferences.Editor) proxyEditor else result
                    }
                } as SharedPreferences.Editor
                proxyEditor
            }
        } as SharedPreferences
        val store = SecureStore("audit-unused-key")
        SecureStore::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(store, wrapped)
        val saved = store.putAll(mapOf("old" to null))
        val old = preferences.getString("old", null)
        println("persistent_failure=$failEveryCommit commits=$commits saved=$saved old=$old")
        return saved to old
    }
    @Test fun aRecoverableCommitFailureRestoresPreviousMemory() {
        val (saved, old) = failedWrite(false)
        assertFalse(saved)
        assertEquals("synthetic-encrypted-value", old)
    }

    @Test fun aPersistentCommitFailureClearsAffectedKeysAsDocumented() {
        val (saved, old) = failedWrite(true)
        assertFalse(saved)
        org.junit.Assert.assertNull(old) // fail-closed policy, not evidence about physical disk.
    }
}
