package io.github.roberbarras.racha.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.github.roberbarras.racha.files.NioLibrarySource
import io.github.roberbarras.racha.ui.app.PlatformHooks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Android capabilities for the shared UI. Must be created in the activity's onCreate (activity-result registration).
 * Folders are picked with the system tree picker and turned into file paths, read with "All files access" (ADR-0005).
 */
class AndroidPlatform(private val activity: ComponentActivity) : PlatformHooks {
    private var pending: CompletableDeferred<Uri?>? = null
    private val picker = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> pending?.complete(uri) }
    private val legacyPermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private val access = MutableStateFlow(hasAccess())

    override val storageAccess: StateFlow<Boolean> get() = access

    /** Re-read after returning from the system settings screen. */
    fun refresh() {
        access.value = hasAccess()
    }

    override suspend fun pickFolder(): String? {
        val deferred = CompletableDeferred<Uri?>().also { pending = it }
        picker.launch(null)
        val uri = deferred.await() ?: return null
        return treeToFile(uri)?.takeIf { it.isDirectory }?.let { NioLibrarySource.locationOf(it.toPath()) }
    }

    override fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val forApp = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${activity.packageName}"))
            runCatching { activity.startActivity(forApp) }.onFailure { activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else {
            legacyPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun hasAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        activity.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    /** `primary:Movies/Cursos` → /storage/emulated/0/Movies/Cursos; `ABCD-1234:x` → /storage/ABCD-1234/x (SD cards). */
    private fun treeToFile(uri: Uri): File? {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val volume = documentId.substringBefore(':')
        val relative = documentId.substringAfter(':', "")
        val base = if (volume.equals("primary", ignoreCase = true)) Environment.getExternalStorageDirectory() else File("/storage/$volume")
        return if (relative.isEmpty()) base else File(base, relative)
    }
}
