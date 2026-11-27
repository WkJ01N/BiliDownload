package io.github.bilidownload

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

internal enum class LegacyComponent(val id: String, val removalLabel: String) {
    Youtube("io.github.bilidownload.youtube", "移除旧版 YouTube 扩展"),
    Audio("io.github.bilidownload.audio", "移除旧版音频扩展");

    fun installed(context: Context): Boolean =
        context.packageManager.checkSignatures(context.packageName, id) == PackageManager.SIGNATURE_MATCH

    fun uninstallIntent() = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", id, null))
}
