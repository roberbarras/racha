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
import androidx.core.net.toUri
import io.github.roberbarras.racha.files.NioLibrarySource
import io.github.roberbarras.racha.ui.app.PlatformHooks
import io.github.roberbarras.racha.ui.player.PlayerHooks
import io.github.roberbarras.racha.ui.resource.ResourceHooks
import kotlinx.coroutines.CompletableDeferred
import io.github.roberbarras.racha.android.notifications.AndroidNotifier
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Android capabilities for the shared UI. Must be created in the activity's onCreate (activity-result registration).
 * Folders are picked with the system tree picker and turned into file paths, read with "All files access" (ADR-0005).
 */
class AndroidPlatform(
    private val activity: ComponentActivity,
    override val player: PlayerHooks,
    override val resources: ResourceHooks,
    override val appVersion: String,
) : PlatformHooks {
    private val notifications = MutableStateFlow(AndroidNotifier.allowed(activity))
    private val notificationPermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override val notificationsAllowed: StateFlow<Boolean> get() = notifications

    override fun requestNotifications() {
        // The system asks once per launch at most; after a refusal (or with notifications off) the settings screen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !asked) {
            asked = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Denied before (or turned off in the system): the app's notification settings.
            activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
        }
    }

    private var asked = false

    private var pendingCreate: CompletableDeferred<Uri?>? = null
    private val creator = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        pendingCreate?.complete(uri)
    }
    private var pendingOpen: CompletableDeferred<Uri?>? = null
    private val opener = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pendingOpen?.complete(uri) }

    override suspend fun saveTextFile(suggestedName: String, text: String): Boolean {
        val deferred = CompletableDeferred<Uri?>().also { pendingCreate = it }
        creator.launch(suggestedName)
        val uri = deferred.await() ?: return false
        return withContext(Dispatchers.IO) {
            runCatching { activity.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.encodeToByteArray()) } }.isSuccess
        }
    }

    override suspend fun openTextFile(): String? {
        val deferred = CompletableDeferred<Uri?>().also { pendingOpen = it }
        opener.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        val uri = deferred.await() ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                activity.contentResolver.openInputStream(uri)!!.use { input ->
                    // Bounded read (readNBytes needs API 33): a configuration file is small.
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    while (out.size() <= MAX_IMPORT_BYTES) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                    if (out.size() > MAX_IMPORT_BYTES) null else out.toByteArray().decodeToString()
                }
            }.getOrNull()
        }
    }

    private var pending: CompletableDeferred<Uri?>? = null
    private val picker = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> pending?.complete(uri) }
    private val legacyPermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private val access = MutableStateFlow(hasAccess())

    override val storageAccess: StateFlow<Boolean> get() = access

    /** Re-read after returning from the system settings screen. */
    fun refresh() {
        access.value = hasAccess()
        notifications.value = AndroidNotifier.allowed(activity)
    }

    override suspend fun pickFolder(): String? {
        val deferred = CompletableDeferred<Uri?>().also { pending = it }
        picker.launch(null)
        val uri = deferred.await() ?: return null
        return treeToFile(uri)?.takeIf { it.isDirectory }?.let { NioLibrarySource.locationOf(it.toPath()) }
    }

    override fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val forApp = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${activity.packageName}".toUri())
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

    private companion object {
        const val MAX_IMPORT_BYTES = 1 shl 20
    }
}
