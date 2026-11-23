package io.github.bilidownload.audio

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder

/** User-initiated activation on devices that restrict cross-app background starts. */
class ConnectActivity : Activity() {
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, engine: IBinder) {
            setResult(RESULT_OK, Intent().putExtras(Bundle().apply { putBinder("engine", engine) }))
            finish()
        }
        override fun onServiceDisconnected(name: ComponentName) { finish() }
        override fun onNullBinding(name: ComponentName) { finish() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (callingPackage != "io.github.bilidownload" ||
            packageManager.checkSignatures(packageName, callingPackage!!) != android.content.pm.PackageManager.SIGNATURE_MATCH) {
            finish(); return
        }
        val intent = Intent(this, AudioService::class.java)
        startService(intent)
        bound = bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) finish()
    }
    override fun onDestroy() { if (bound) unbindService(connection); super.onDestroy() }
}
