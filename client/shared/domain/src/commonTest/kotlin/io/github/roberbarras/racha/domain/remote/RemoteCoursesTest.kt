package io.github.roberbarras.racha.domain.remote

import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.RootKey
import io.github.roberbarras.racha.domain.library.RootLayout
import io.github.roberbarras.racha.domain.library.scan.ExclusionRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class RemoteCoursesTest {
    private val t = Instant.parse("2026-11-01T10:00:00Z")
    private fun file(path: String, size: Long) = RemoteFile(RelativePath.of(path), size, t, "ref:$path")
    private val none = ExclusionRules(emptyList())

    private val library = listOf(
        file("AWS/01 - Intro/01 Welcome.mp4", 1_000), file("AWS/01 - Intro/01 Welcome.es.srt", 10), file("AWS/poster.jpg", 50),
        file("Kubernetes/01 - Pods/01 Pods.mp4", 2_000), file("Kubernetes/.DS_Store", 1), file("Kubernetes/02 Nodes.mp4.racha-part", 5),
        file("Charla suelta.mp4", 300),
    )

    private fun source(mode: DownloadMode, selected: Set<String> = emptySet(), layout: RootLayout = RootLayout.LIBRARY) = RemoteSource(
        "s1", RemoteProvider.WEBDAV, "Cursos", "ana", "https://cloud.example/remote.php/dav/files/ana/Cursos/", "cloud.example · /Cursos",
        CategoryKey.COURSES, RootKey("6d0f8a52-1c1b-4c55-9a51-0e5d3c2b1a10"), layout, "file:///home/ana/Vídeos/Racha/Cursos/", mode, selected, false, null, null,
    )

    @Test
    fun aLibraryIsOneCoursePerFolderAndLooseFilesApart() {
        val local = mapOf(RelativePath.of("AWS/01 - Intro/01 Welcome.mp4") to LocalCopy(1_000, t))
        val courses = RemoteCourses.courses(library, local, RootLayout.LIBRARY, none, "Cursos")
        assertEquals(listOf("AWS", "Kubernetes", ""), courses.map { it.key })
        val aws = courses.first()
        assertEquals(3, aws.files)
        assertEquals(1_060, aws.bytes)
        assertEquals(1_000, aws.downloadedBytes)
        assertEquals(2_000, courses[1].bytes, "hidden files and partial downloads do not count")
    }

    @Test
    fun onlyTheChosenCoursesMissingOrChangedFilesAreDownloadedSmallFirst() {
        val local = mapOf(
            RelativePath.of("AWS/01 - Intro/01 Welcome.mp4") to LocalCopy(1_000, t),
            RelativePath.of("AWS/poster.jpg") to LocalCopy(40, t), // changed remotely
        )
        val chosen = RemoteCourses.toDownload(source(DownloadMode.SELECTED, setOf("AWS")), library, local, none)
        assertEquals(listOf("AWS/01 - Intro/01 Welcome.es.srt", "AWS/poster.jpg"), chosen.map { it.path.value })

        val all = RemoteCourses.toDownload(source(DownloadMode.ALL), library, local, none)
        assertEquals(listOf("AWS/01 - Intro/01 Welcome.es.srt", "AWS/poster.jpg", "Charla suelta.mp4", "Kubernetes/01 - Pods/01 Pods.mp4"), all.map { it.path.value })
        assertEquals(emptyList(), RemoteCourses.toDownload(source(DownloadMode.SELECTED), library, local, none))
    }

    @Test
    fun aSingleCourseIsAllOrNothingAndExclusionsApply() {
        val course = listOf(file("01 - Intro/01 a.mp4", 10), file("02 - Más/02 b.mp4", 20), file("Extras/x.mp4", 5))
        val courses = RemoteCourses.courses(course, emptyMap(), RootLayout.SINGLE_ITEM, ExclusionRules(listOf("Extras/")), "Curso de Go")
        assertEquals(listOf(RemoteCourse("", "Curso de Go", 2, 30, 0)), courses)
        assertEquals(RootLayout.SINGLE_ITEM, RemoteCourses.effectiveLayout(course, RootLayout.AUTO, none), "numbered sections: one course")
        val local = listOf(RelativePath.of("AWS/a.mp4"), RelativePath.of("Kubernetes/b.mp4"))
        assertEquals(listOf("Kubernetes/b.mp4"), RemoteCourses.notChosen(source(DownloadMode.SELECTED, setOf("AWS")), local, RootLayout.LIBRARY).map { it.value })
        assertEquals(true, RemoteCourses.isUpToDate(file("a", 5), LocalCopy(5, Instant.parse("2026-11-01T09:59:59Z"))), "a second of difference is the same time")
    }
}
