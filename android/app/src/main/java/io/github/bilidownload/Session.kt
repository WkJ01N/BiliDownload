package io.github.bilidownload

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Session(context: Context) {
    private val revision = java.util.concurrent.atomic.AtomicLong()
    val generation: Long get() = revision.get()
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val mutableAccount = MutableStateFlow(AccountInfo(if(prefs.contains("value")) SessionState.Saved else SessionState.Guest, prefs.getString("username", null)))
    val account = mutableAccount.asStateFlow()
    var state: SessionState
        get() = mutableAccount.value.state
        set(value) { mutableAccount.value = mutableAccount.value.copy(state = value, username = if(value == SessionState.Guest || value == SessionState.Expired) null else mutableAccount.value.username) }
    @Synchronized fun updateProfile(username: String) {
        if(username.isNotBlank()) { prefs.edit().putString("username", username).apply(); mutableAccount.value = mutableAccount.value.copy(username = username) }
    }
    @Synchronized fun acceptNavigation(expected: Long, loggedIn: Boolean, username: String) {
        if(expected != generation) return
        state = if(loggedIn) SessionState.Authenticated else if(state == SessionState.Guest) SessionState.Guest else SessionState.Expired
        if(loggedIn) updateProfile(username)
    }
    @Synchronized fun expire(expected: Long) { if(expected == generation) state = SessionState.Expired }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("BiliDownloadSession", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("BiliDownloadSession", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun get(): String = runCatching {
        val raw = prefs.getString("value", null) ?: return ""
        val bytes = Base64.decode(raw, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }.getOrDefault("")
    @Synchronized fun save(cookie: String, username: String = "") {
        revision.incrementAndGet()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        prefs.edit().putString("value", Base64.encodeToString(cipher.iv + cipher.doFinal(cookie.toByteArray()), Base64.NO_WRAP)).putString("username", username.takeIf { it.isNotBlank() }).commit()
        mutableAccount.value = AccountInfo(SessionState.Saved, username.takeIf { it.isNotBlank() })
    }
    @Synchronized fun clear() { revision.incrementAndGet(); prefs.edit().clear().commit(); state = SessionState.Guest }
}
