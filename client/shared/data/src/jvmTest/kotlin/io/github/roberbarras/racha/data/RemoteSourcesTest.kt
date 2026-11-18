package io.github.roberbarras.racha.data

import io.github.roberbarras.racha.application.browse.ManageRoots
import io.github.roberbarras.racha.application.library.EnsureBuiltInCategories
import io.github.roberbarras.racha.application.library.scan.LocalDates
import io.github.roberbarras.racha.application.library.scan.ScanLibrary
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.remote.DownloadState
import io.github.roberbarras.racha.application.remote.NewRemoteSource
import io.github.roberbarras.racha.application.remote.RemoteCredentials
import io.github.roberbarras.racha.application.remote.RemoteDirectory
import io.github.roberbarras.racha.application.remote.RemoteSources
import io.github.roberbarras.racha.application.remote.RemoteStorage
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.application.shared.SecretStore
import io.github.roberbarras.racha.application.sync.RemoteError
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.RootLayout
import io.github.roberbarras.racha.domain.library.scan.CalendarDate
import io.github.roberbarras.racha.domain.remote.DownloadMode
import io.github.roberbarras.racha.domain.remote.RemoteFile
import io.github.roberbarras.racha.domain.remote.RemoteProvider
import io.github.roberbarras.racha.files.NioLibrarySource
import io.github.roberbarras.racha.files.NioLocalCopies
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Remote folders (H19): chosen courses arrive on the device and become courses of the library. */
class RemoteSourcesTest {
    private val now = Instant.parse("2026-11-18T10:00:00Z")
    private val clock = Clock { now }
    private val db = Fixtures.newDatabase()
    private val preferences = Preferences(db.settings, clock)
    private val home: Path = Files.createTempDirectory("racha-remote-home")
    private val destination = home.resolve("Vídeos/Racha/Cursos").toUri().toString()
    private val t = Instant.parse("2026-11-01T10:00:00Z")
    private val secrets = HashMap<String, String>()
    private val scan = ScanLibrary(db.categories, db.library, db.progress, NioLibrarySource(), { _, _ -> null }, { _, _ -> "" }, preferences, clock,
        LocalDates { CalendarDate(2026, 11, 18) })

    /** A remote folder in memory: two courses and a loose talk. */
    private inner class FakeCloud : RemoteStorage {
        override val provider = RemoteProvider.WEBDAV
        var downloaded = mutableListOf<String>()
        var files = listOf(
            "AWS/01 - Intro/01 Bienvenida.mp4" to 3_000, "AWS/01 - Intro/01 Bienvenida.es.srt" to 40, "AWS/02 - IAM/02 Políticas.mp4" to 4_000,
            "Kubernetes/01 - Pods/01 Pods.mp4" to 5_000, "Charla.mp4" to 2_000,
        ).map { (p, size) -> RemoteFile(RelativePath.of(p), size.toLong(), t, "https://cloud.test/$p") }

        override suspend fun folders(credentials: RemoteCredentials, base: String, address: String?) = listOf(RemoteDirectory("Cursos", "$base/Cursos/", "/Cursos"))

        override suspend fun files(credentials: RemoteCredentials, address: String): List<RemoteFile> {
            if (credentials != RemoteCredentials.WebDav("ana", "app-pass")) throw RemoteError.Unauthorized()
            return files
        }

        override suspend fun download(credentials: RemoteCredentials, file: RemoteFile, target: String, progress: (Long) -> Unit) {
            val path = Path.of(URI(target))
            Files.createDirectories(path.parent)
            Files.write(path, ByteArray(file.size.toInt()))
            Files.setLastModifiedTime(path, FileTime.fromMillis(file.modifiedAt.toEpochMilliseconds()))
            progress(file.size)
            downloaded += file.path.value
        }
    }

    private val cloud = FakeCloud()
    private val remote = RemoteSources(
        db.remoteSources, listOf(cloud),
        { id -> object : SecretStore {
            override suspend fun read() = secrets[id]
            override suspend fun write(value: String) { secrets[id] = value }
            override suspend fun clear() { secrets.remove(id) }
        } },
        NioLocalCopies(), db.categories, ManageRoots(db.categories, db.library, scan, clock), scan, clock,
    )
    private val credentials = RemoteCredentials.WebDav("ana", "app-pass")

    @AfterTest
    fun cleanUp() {
        home.toFile().deleteRecursively()
    }

    private suspend fun add(mode: DownloadMode, selected: Set<String>) = remote.add(
        NewRemoteSource(RemoteProvider.WEBDAV, "Cursos", "ana", "https://cloud.test/remote.php/dav/files/ana/Cursos/", "cloud.test · /Cursos",
            CategoryKey.COURSES, RootLayout.LIBRARY, destination, mode, selected, wifiOnly = false),
        credentials,
    )

    private suspend fun titles() = db.library.itemsInRoot(CategoryKey.COURSES, db.remoteSources.all().single().rootKey).filter { !it.archived }.map { it.title }.sorted()

    @Test
    fun onlyTheChosenCoursesArriveAndBecomeCoursesThenMoreAreChosenAndSpaceFreed() = runBlocking<Unit> {
        EnsureBuiltInCategories(db.categories, clock)()
        val preview = remote.preview(RemoteProvider.WEBDAV, credentials, "https://cloud.test/remote.php/dav/files/ana/Cursos/", RootLayout.AUTO, CategoryKey.COURSES, "Cursos")
        assertEquals(RootLayout.LIBRARY, preview.layout)
        assertEquals(listOf("AWS", "Kubernetes", ""), preview.courses.map { it.key })

        val source = add(DownloadMode.SELECTED, setOf("AWS"))
        assertEquals("webdav\nana\napp-pass", secrets[source.id], "credentials in the keyring, not in the database")
        assertEquals(destination, db.categories.roots(CategoryKey.COURSES).single().location)

        assertEquals(3, remote.download(source.id))
        assertEquals(listOf("AWS/01 - Intro/01 Bienvenida.es.srt", "AWS/01 - Intro/01 Bienvenida.mp4", "AWS/02 - IAM/02 Políticas.mp4"), cloud.downloaded)
        assertEquals(listOf("AWS"), titles(), "scanned into the library")
        assertEquals(DownloadState.Idle, remote.downloads.value[source.id])
        assertEquals(now, db.remoteSources.get(source.id)!!.lastSyncAt)
        val aws = remote.courses(source.id).first { it.key == "AWS" }
        assertTrue(aws.complete)

        // Again: nothing new to download.
        assertEquals(0, remote.download(source.id))

        // Kubernetes too, and AWS later dropped: its files go only when space is freed.
        remote.choose(source.id, DownloadMode.SELECTED, setOf("AWS", "Kubernetes"))
        assertEquals(1, remote.download(source.id))
        assertEquals(listOf("AWS", "Kubernetes"), titles())
        remote.choose(source.id, DownloadMode.SELECTED, setOf("Kubernetes"))
        assertEquals(0, remote.download(source.id))
        assertTrue(Files.exists(Path.of(URI(destination)).resolve("AWS/02 - IAM/02 Políticas.mp4")))
        assertEquals(3, remote.freeSpace(source.id))
        assertFalse(Files.exists(Path.of(URI(destination)).resolve("AWS")), "empty folders go too")
        assertEquals(listOf("Kubernetes"), titles())

        // Everything, including new courses as they appear.
        remote.choose(source.id, DownloadMode.ALL, emptySet())
        cloud.files = cloud.files + RemoteFile(RelativePath.of("Go/01 - Básico/01 Hola.mp4"), 1_000, t, "x")
        assertEquals(5, remote.download(source.id))
        assertEquals(listOf("", "AWS", "Go", "Kubernetes"), titles())

        remote.remove(source.id, deleteFiles = true)
        assertTrue(db.remoteSources.all().isEmpty())
        assertTrue(db.categories.roots(CategoryKey.COURSES).isEmpty(), "its courses leave the library")
        assertFalse(Files.exists(Path.of(URI(destination))))
        assertTrue(secrets.isEmpty())
    }

    @Test
    fun failuresAreKeptAndShown() = runBlocking<Unit> {
        EnsureBuiltInCategories(db.categories, clock)()
        val source = add(DownloadMode.ALL, emptySet())
        secrets[source.id] = RemoteCredentials.encode(RemoteCredentials.WebDav("ana", "revoked"))
        assertEquals(0, remote.download(source.id))
        assertEquals(DownloadState.Failed("credentials"), remote.downloads.value[source.id])
        assertEquals("credentials", db.remoteSources.get(source.id)!!.lastError)
        assertEquals(null, db.remoteSources.get(source.id)!!.lastSyncAt)

        secrets[source.id] = RemoteCredentials.encode(credentials)
        cloud.files = listOf(RemoteFile(RelativePath.of("Enorme/01 - Uno/video.mp4"), Long.MAX_VALUE / 4, t, "x"))
        remote.download(source.id)
        assertIs<DownloadState.Failed>(remote.downloads.value[source.id])
        assertEquals("space", db.remoteSources.get(source.id)!!.lastError, "nothing starts if it does not fit")
        assertTrue(cloud.downloaded.isEmpty())
    }
}
