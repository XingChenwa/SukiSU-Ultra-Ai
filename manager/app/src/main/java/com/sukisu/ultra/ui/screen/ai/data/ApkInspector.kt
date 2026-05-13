package com.sukisu.ultra.ui.screen.ai.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.GET_ACTIVITIES
import android.content.pm.PackageManager.GET_PERMISSIONS
import android.content.pm.PackageManager.GET_RECEIVERS
import android.content.pm.PackageManager.GET_SERVICES
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Immutable
import java.io.File
import java.util.zip.ZipFile

/**
 * Structured summary of an APK / installed app used to feed the LLM. We keep it bounded
 * (list sizes are truncated in [ApkAttachmentSummary.toPromptBlock]) because most LLM
 * context windows cannot ingest full manifests of large apps.
 */
@Immutable
data class ApkAttachmentSummary(
    val label: String,
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val permissions: List<String>,
    val activities: List<String>,
    val services: List<String>,
    val receivers: List<String>,
    val nativeLibs: List<String>,
    val source: Source,
) {
    enum class Source { Installed, ApkFile }

    fun toPromptBlock(): String = buildString {
        append("### Target application\n")
        append("- Source: ").append(source).append('\n')
        append("- Package: ").append(packageName ?: "(unknown)").append('\n')
        append("- Version: ").append(versionName ?: "(unknown)")
            .append(" (code=").append(versionCode ?: 0).append(")\n")
        if (minSdk != null) append("- minSdk: ").append(minSdk).append('\n')
        if (targetSdk != null) append("- targetSdk: ").append(targetSdk).append('\n')
        if (nativeLibs.isNotEmpty()) {
            append("- Native libs: ").append(nativeLibs.joinToString()).append('\n')
        }
        appendList("Permissions", permissions, 50)
        appendList("Activities", activities, 40)
        appendList("Services", services, 25)
        appendList("Receivers", receivers, 25)
    }

    private fun StringBuilder.appendList(title: String, list: List<String>, maxItems: Int) {
        if (list.isEmpty()) return
        append("- ").append(title).append(" (").append(list.size).append("):\n")
        list.take(maxItems).forEach { append("  - ").append(it).append('\n') }
        if (list.size > maxItems) {
            append("  - … (+").append(list.size - maxItems).append(" more)\n")
        }
    }
}

object ApkInspector {

    fun fromInstalled(context: Context, packageName: String): ApkAttachmentSummary? {
        val pm = context.packageManager
        val flags = GET_PERMISSIONS or GET_ACTIVITIES or GET_SERVICES or GET_RECEIVERS
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, flags)
            }
        }.getOrNull() ?: return null
        val label = runCatching { info.applicationInfo?.loadLabel(pm)?.toString() }.getOrNull() ?: packageName
        val appInfo = info.applicationInfo
        return ApkAttachmentSummary(
            label = label,
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong(),
            minSdk = appInfo?.minSdkVersion,
            targetSdk = appInfo?.targetSdkVersion,
            permissions = info.requestedPermissions?.toList().orEmpty(),
            activities = info.activities?.map { it.name }.orEmpty(),
            services = info.services?.map { it.name }.orEmpty(),
            receivers = info.receivers?.map { it.name }.orEmpty(),
            nativeLibs = appInfo?.let { collectNativeLibsFromApp(it) }.orEmpty(),
            source = ApkAttachmentSummary.Source.Installed,
        )
    }

    /**
     * Parse an APK file selected through SAF. We copy to a temp file first because
     * [PackageManager.getPackageArchiveInfo] requires an on-disk path.
     */
    fun fromApkUri(context: Context, uri: Uri): ApkAttachmentSummary? {
        val temp = File.createTempFile("apk_inspect_", ".apk", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            val pm = context.packageManager
            val flags = GET_PERMISSIONS or GET_ACTIVITIES or GET_SERVICES or GET_RECEIVERS
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(temp.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(temp.absolutePath, flags)
            } ?: return null
            info.applicationInfo?.apply {
                sourceDir = temp.absolutePath
                publicSourceDir = temp.absolutePath
            }
            val label = runCatching { info.applicationInfo?.loadLabel(pm)?.toString() }.getOrNull()
                ?: info.packageName
                ?: temp.name
            return ApkAttachmentSummary(
                label = label,
                packageName = info.packageName,
                versionName = info.versionName,
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong(),
                minSdk = info.applicationInfo?.minSdkVersion,
                targetSdk = info.applicationInfo?.targetSdkVersion,
                permissions = info.requestedPermissions?.toList().orEmpty(),
                activities = info.activities?.map { it.name }.orEmpty(),
                services = info.services?.map { it.name }.orEmpty(),
                receivers = info.receivers?.map { it.name }.orEmpty(),
                nativeLibs = collectNativeLibsFromApk(temp),
                source = ApkAttachmentSummary.Source.ApkFile,
            )
        } finally {
            temp.delete()
        }
    }

    private fun collectNativeLibsFromApp(info: ApplicationInfo): List<String> {
        val sourceDir = info.sourceDir ?: return emptyList()
        return collectNativeLibsFromApk(File(sourceDir))
    }

    private fun collectNativeLibsFromApk(apk: File): List<String> {
        if (!apk.exists()) return emptyList()
        return runCatching {
            ZipFile(apk).use { zip ->
                zip.entries().toList()
                    .asSequence()
                    .map { it.name }
                    .filter { it.startsWith("lib/") && it.endsWith(".so") }
                    .map { it.removePrefix("lib/") }
                    .toMutableSet()
                    .toList()
                    .sorted()
            }
        }.getOrDefault(emptyList())
    }
}
