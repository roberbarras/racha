package io.github.roberbarras.racha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.github.roberbarras.racha.application.browse.ManageRoots
import io.github.roberbarras.racha.application.library.scan.LocalDates
import io.github.roberbarras.racha.application.library.scan.ScanLibrary
import io.github.roberbarras.racha.application.remote.GoogleSignIn
import io.github.roberbarras.racha.application.remote.NextcloudPending
import io.github.roberbarras.racha.application.remote.NextcloudSignIn
import io.github.roberbarras.racha.application.remote.RemoteAccounts
import io.github.roberbarras.racha.application.remote.RemoteCredentials
import io.github.roberbarras.racha.application.remote.RemoteDirectory
import io.github.roberbarras.racha.application.remote.RemoteSources
import io.github.roberbarras.racha.application.remote.RemoteStorage
import io.github.roberbarras.racha.application.remote.WebDavAccount
import io.github.roberbarras.racha.application.shared.SecretStore
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.scan.CalendarDate
import io.github.roberbarras.racha.domain.remote.DownloadMode
import io.github.roberbarras.racha.domain.remote.RemoteFile
import io.github.roberbarras.racha.domain.remote.RemoteProvider
import io.github.roberbarras.racha.files.NioLibrarySource
import io.github.roberbarras.racha.files.NioLocalCopies
import io.github.roberbarras.racha.ui.app.RemoteFolders
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaTheme
import io.github.roberbarras.racha.ui.folders.RemoteFoldersSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.Collections
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Folders → "Remote folders" (H19): sign in to a Nextcloud, pick a folder, choose a course, and it arrives. */
@OptIn(ExperimentalTestApi::class)
class RemoteFoldersUiTest {
    private val originalLocale = Locale.getDefault()
    private val home: Path = Files.createTempDirectory("racha-remote-ui")
    private val out = java.io.File(System.getProperty("racha.screenshots") ?: "build/screenshots").apply { mkdirs() }

    @AfterTest
    fun cleanUp() {
        Locale.setDefault(originalLocale)
        home.toFile().deleteRecursively()
    }

    private val t = Instant.parse("2026-11-01T10:00:00Z")

    private val cloud = object : RemoteStorage {
        override val provider = RemoteProvider.WEBDAV
        val downloaded: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun folders(credentials: RemoteCredentials, base: String, address: String?) =
            if (address == null) listOf(RemoteDirectory("Cursos", "$base/Cursos/", "/Cursos")) else emptyList()
        override suspend fun files(credentials: RemoteCredentials, address: String) = listOf(
            "AWS/01 - Intro/01 Bienvenida.mp4" to 3_000_000, "AWS/01 - Intro/02 Cuenta.mp4" to 4_000_000, "Kubernetes/01 - Pods/01 Pods.mp4" to 5_000_000,
        ).map { (p, size) -> RemoteFile(RelativePath.of(p), size.toLong(), t, p) }
        override suspend fun download(credentials: RemoteCredentials, file: RemoteFile, target: String, progress: (Long) -> Unit) {
            val path = Path.of(URI(target))
            Files.createDirectories(path.parent)
            Files.write(path, ByteArray(16))
            Files.setLastModifiedTime(path, FileTime.fromMillis(file.modifiedAt.toEpochMilliseconds()))
            downloaded += file.path.value
        }
    }

    @Test
    fun aNextcloudFolderIsAddedWithOneCourseAndItArrives() = runDesktopComposeUiTest(1280, 2000) {
        Locale.setDefault(Locale.ENGLISH)
        val deps0 = DemoLibrary.dependencies()
        val db = DemoLibrary.database
        val opened = mutableListOf<String>()
        val secrets = HashMap<String, String>()
        val scan = ScanLibrary(db.categories, db.library, db.progress, NioLibrarySource(), { _, _ -> null }, { _, _ -> "" }, deps0.preferences, deps0.clock,
            LocalDates { CalendarDate(2026, 11, 18) })
        val sources = RemoteSources(
            db.remoteSources, listOf(cloud),
            { id -> object : SecretStore {
                override suspend fun read() = secrets[id]
                override suspend fun write(value: String) { secrets[id] = value }
                override suspend fun clear() { secrets.remove(id) }
            } },
            NioLocalCopies(), db.categories, ManageRoots(db.categories, db.library, scan, deps0.clock), scan, deps0.clock,
        )
        val nextcloud = object : NextcloudSignIn {
            var polls = 0
            override suspend fun start(server: String) = NextcloudPending("https://$server/login/v2/flow/x", "e", "t")
            override suspend fun poll(pending: NextcloudPending) =
                if (++polls < 2) null else WebDavAccount("https://cloud.test", "ana", "app-pass", "https://cloud.test/remote.php/dav/files/ana/")
            override fun davBase(server: String, user: String) = "$server/remote.php/dav/files/$user/"
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val remote = RemoteFolders(
            sources,
            RemoteAccounts(nextcloud, GoogleSignIn { error("not used") }, object : SecretStore {
                override suspend fun read(): String? = null
                override suspend fun write(value: String) {}
                override suspend fun clear() {}
            }),
            download = { id -> scope.launch { if (id == null) sources.downloadAll() else sources.download(id) } },
            defaultDestination = { name -> home.resolve("Videos/Racha/$name").toUri().toString() },
        )
        val deps = DemoLibrary.dependencies(remote = remote, openedUrls = opened)
        setContent {
            val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                RachaTheme(dark = false) { Box(Modifier.fillMaxSize().background(Racha.colors.background)) { RemoteFoldersSection(deps) } }
            }
        }
        onNodeWithText("Add a remote folder").performClick()
        onNodeWithText("Nextcloud or WebDAV").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Sign in in the browser").fetchSemanticsNodes().isNotEmpty() }
        onNode(hasContentDescription("Server address (cloud.example.com)")).performTextInput("cloud.test")
        onNodeWithText("Sign in in the browser").performClick()
        // The browser opens on the server; the app waits for the sign-in there.
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("Cursos").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("https://cloud.test/login/v2/flow/x"), opened)
        onNodeWithText("Cursos").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Use “Cursos”").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Use “Cursos”").performClick()

        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Kubernetes").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("2 courses or series · 11 MB", substring = true).assertExists()
        onNodeWithText("Courses I choose").performClick()
        onNodeWithText("AWS").performClick()
        javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", java.io.File(out, "desktop-remote-add-en.png"))
        onNodeWithText("Add and download").performClick()

        waitUntil(timeoutMillis = 15_000) { cloud.downloaded.size == 2 && onAllNodesWithText("Up to date").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(setOf("AWS/01 - Intro/01 Bienvenida.mp4", "AWS/01 - Intro/02 Cuenta.mp4"), cloud.downloaded.toSet())
        val source = runBlocking { db.remoteSources.all().single() }
        assertEquals(DownloadMode.SELECTED, source.mode)
        assertEquals(setOf("AWS"), source.selected)
        assertEquals("ana · cloud.test · /Cursos", source.location)
        assertTrue(runBlocking { db.library.itemsInRoot(CategoryKey.COURSES, source.rootKey) }.any { it.title == "AWS" }, "the course is in the library")
        javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", java.io.File(out, "desktop-remote-card-en.png"))
    }
}
