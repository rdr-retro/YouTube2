package com.rdr.youtube2

import android.content.pm.PackageInfo
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.PackageInfoCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VersionInfoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_version_info)

        findViewById<Button>(R.id.version_info_back_button).setOnClickListener {
            finish()
        }

        val infoText = findViewById<TextView>(R.id.version_info_content)
        val packageInfo = resolvePackageInfo()
        val targetSdk = applicationInfo.targetSdkVersion
        val versionName = packageInfo?.versionName?.takeIf { it.isNotBlank() } ?: "N/A"
        val versionCode = packageInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L
        val buildTypeLabel = if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            "debuggable"
        } else {
            "release"
        }

        val details = buildString {
            appendLine("Aplicacion: ${getString(R.string.app_name)}")
            appendLine("Paquete: $packageName")
            appendLine("Version: $versionName")
            appendLine("Version code: $versionCode")
            appendLine("Build type: $buildTypeLabel")
            appendLine("Target SDK: $targetSdk")
            appendLine("Android dispositivo: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Instalada: ${formatTimestamp(packageInfo?.firstInstallTime ?: 0L)}")
            appendLine("Actualizada: ${formatTimestamp(packageInfo?.lastUpdateTime ?: 0L)}")
        }
        infoText.text = details.trim()
    }

    private fun resolvePackageInfo(): PackageInfo? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
        }.getOrNull()
    }

    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp <= 0L) return "N/A"
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return formatter.format(Date(timestamp))
    }
}
