package io.github.roberbarras.racha.domain.library.scan

import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.ElementKind
import io.github.roberbarras.racha.domain.library.LanguageCode
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.ResourceType
import io.github.roberbarras.racha.domain.library.RootKey
import io.github.roberbarras.racha.domain.library.SectionKind
import io.github.roberbarras.racha.domain.library.StructureKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class LibraryLayoutTest {
    private val root = RootKey("0b4f1b5e-6d1f-4a35-9a51-6a3a1b0c9d10")
    private val t0 = Instant.parse("2026-11-01T10:00:00Z")
    private val utcDate: (Instant) -> CalendarDate = { i ->
        val days = i.epochSeconds.floorDiv(86_400)
        // Civil-from-days (Howard Hinnant), enough for tests in UTC.
        val z = days + 719_468
        val era = z.floorDiv(146_097)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
        CalendarDate(y, m, d)
    }

    private fun tree(vararg files: Pair<String, Instant>): List<FileEntry> =
        files.map { (path, at) -> FileEntry(RelativePath.of(path), isDirectory = false, size = 1_000, modifiedAt = at) }

    private fun files(vararg paths: String) = tree(*paths.map { it to t0 }.toTypedArray())

    private fun detect(entries: List<FileEntry>, category: CategoryKey = CategoryKey.COURSES, excludes: List<String> = listOf("temp/")) =
        LibraryLayout.detect(category, root, "AWS Certified Solutions Architect", entries, ExclusionRules(excludes), utcDate)

    /** The reference layout of spec §3.1 (acceptance criterion 1). */
    private val specExample = files(
        "01 - Introduction/001 Course Introduction.mp4",
        "01 - Introduction/001 Course Introduction_es.srt",
        "01 - Introduction/002 Creating an AWS Account.mp4",
        "01 - Introduction/002 Creating an AWS Account_es.srt",
        "01 - Introduction/003 AWS Account Activation Troubleshooting.html",
        "02 - Code & Slides Download/001 Code & Slides Download.html",
        "02 - Code & Slides Download/001 Course-Material-Download.url",
        "02 - Code & Slides Download/external-links.txt",
        "04 - IAM - Identity and Access Management/001 IAM Introduction Users, Groups, Policies.mp4",
        "04 - IAM - Identity and Access Management/001 IAM Introduction Users, Groups, Policies_es.srt",
        "04 - IAM - Identity and Access Management/021 Identity and Access Management - Quiz.html",
        "temp/index_XXXXX_1920x1080.m3u8",
        "temp/index_XXXXX_1920x1080_00001.ts",
        "temp/index_XXXXX_1920x1080_00002.ts",
    )

    @Test
    fun specExampleIsOneCourseWithOrderedSectionsSubtitlesAndNoTemp() {
        val library = detect(specExample.shuffled())

        val course = library.items.single()
        assertEquals(StructureKind.COURSE, course.kind)
        assertEquals("AWS Certified Solutions Architect", course.title)
        assertEquals(RelativePath.ROOT, course.path)
        assertEquals(
            listOf("Introduction", "Code & Slides Download", "IAM - Identity and Access Management"),
            course.sections.map { it.title },
        )
        assertTrue(course.sections.all { it.kind == SectionKind.MODULE })

        val (intro, code, iam) = course.sections
        assertEquals(
            listOf(
                Triple("Course Introduction", ElementKind.VIDEO, null),
                Triple("Creating an AWS Account", ElementKind.VIDEO, null),
                Triple("AWS Account Activation Troubleshooting", ElementKind.RESOURCE, ResourceType.HTML),
            ),
            intro.elements.map { Triple(it.title, it.kind, it.resourceType) },
        )
        assertEquals(listOf(1, 2, 3), intro.elements.map { it.order })
        assertEquals(
            listOf(listOf(LanguageCode("es")), listOf(LanguageCode("es")), emptyList()),
            intro.elements.map { e -> e.subtitles.map { it.language } },
        )
        assertEquals(
            listOf(ResourceType.HTML, ResourceType.URL, ResourceType.TEXT),
            code.elements.map { it.resourceType },
        )
        assertEquals(listOf("Code & Slides Download", "Course-Material-Download", "external-links"), code.elements.map { it.title })
        assertEquals(listOf("IAM Introduction Users, Groups, Policies", "Identity and Access Management - Quiz"), iam.elements.map { it.title })
        assertEquals(LanguageCode("es"), iam.elements.first().subtitles.single().language)

        assertTrue(library.items.flatMap { it.elements }.none { it.path.value.startsWith("temp/") }, "temp/ must be hidden")
    }

    @Test
    fun idsFollowAdr0004AndAreStableAcrossScans() {
        val first = detect(specExample)
        val second = detect(specExample.reversed())

        assertEquals(first, second)
        val intro = first.items.single().sections.first()
        val video = intro.elements.first()
        assertEquals(ContentId.forVideo(CategoryKey.COURSES, root, RelativePath.of("01 - Introduction/001 Course Introduction.mp4")), video.id)
        assertEquals(ContentId.forFolder(CategoryKey.COURSES, root, RelativePath.of("01 - Introduction")), intro.id)
        assertEquals(
            ContentId.forSubtitle(CategoryKey.COURSES, root, RelativePath.of("01 - Introduction/001 Course Introduction_es.srt")),
            video.subtitles.single().id,
        )
    }

    @Test
    fun tempIsIndexedWhenTheExclusionIsRemoved() {
        val library = detect(specExample, excludes = emptyList())

        assertTrue(library.items.flatMap { it.elements }.any { it.path.value == "temp/index_XXXXX_1920x1080.m3u8" })
        assertTrue(library.items.flatMap { it.elements }.none { it.path.extension == "ts" }, "HLS segments are not episodes")
    }

    @Test
    fun rootWithSeveralCoursesAndLooseFiles() {
        val library = detect(
            files(
                "Kubernetes/01 - Basics/001 Pods.mp4",
                "AWS/01 - Intro/001 Welcome.mp4",
                "AWS/poster.jpg",
                "random clip.mp4",
            ),
        )

        assertEquals(listOf("AWS", "Kubernetes", ""), library.items.map { it.title })
        assertEquals(listOf(StructureKind.COURSE, StructureKind.COURSE, StructureKind.LOOSE), library.items.map { it.kind })
        assertEquals(RelativePath.of("AWS/poster.jpg"), library.items.first().poster)
        assertEquals(listOf("random clip"), library.items.last().elements.map { it.title })
    }

    @Test
    fun seriesFromSeasonFoldersAndFromSxxEyyNames() {
        val library = detect(
            files(
                "Dark/Season 2/S02E01 Beginnings.mkv",
                "Dark/Season 1/S01E02 Lies.mkv",
                "Dark/Season 1/S01E01 Secrets.mkv",
                "Flat/Flat.S02E01.mkv",
                "Flat/Flat.S01E02.mkv",
                "Flat/Flat.S01E01.mkv",
            ),
            category = CategoryKey.SERIES,
        )

        val (dark, flat) = library.items
        assertEquals(StructureKind.SERIES, dark.kind)
        assertEquals(listOf(SectionKind.SEASON, SectionKind.SEASON), dark.sections.map { it.kind })
        assertEquals(listOf(listOf("Secrets", "Lies"), listOf("Beginnings")), dark.sections.map { s -> s.elements.map { it.title } })
        assertEquals(StructureKind.SERIES, flat.kind)
        assertEquals(listOf(2, 1), flat.sections.map { it.elements.size })
        assertEquals(listOf("S01E01", "S01E02", "S02E01"), flat.elements.map { it.path.name.substringAfter("Flat.").substringBefore(".") })
    }

    @Test
    fun liveChannelsGroupByNameDateThenModificationDate() {
        val library = detect(
            tree(
                "Channel/13-11-2026 Charla.mp4" to t0,
                "Channel/2026-11-01 Stream.mp4" to t0,
                "Channel/stream_20261101_part2.mp4" to t0 + 1.minutes,
                "Channel/sin fecha.mp4" to Instant.parse("2026-11-05T18:00:00Z"),
            ),
            category = CategoryKey.LIVE,
        )

        val channel = library.items.single()
        assertEquals(StructureKind.LIVE, channel.kind)
        assertEquals(listOf("2026-11-01", "2026-11-05", "2026-11-13"), channel.sections.map { it.title })
        assertEquals(2, channel.sections.first().elements.size)
    }

    @Test
    fun withoutNumbersElementsAndSectionsFollowModificationTime() {
        val library = detect(
            tree(
                "Talks/Advanced/b.mp4" to t0 + 2.days,
                "Talks/Advanced/a.mp4" to t0 + 3.days,
                "Talks/Basics/z.mp4" to t0,
                "Talks/Basics/y.mp4" to t0 + 1.days,
            ),
        )

        val talks = library.items.single()
        assertEquals(listOf("Basics", "Advanced"), talks.sections.map { it.title })
        assertEquals(listOf("z", "y", "b", "a"), talks.elements.map { it.title })
    }

    @Test
    fun sameStemVideosGetDistinctIdsAndFilesInTheCourseFolderComeFirst() {
        val library = detect(files("C/01 - A/001 X.mp4", "C/01 - A/001 X.mkv", "C/README.html"))

        val course = library.items.single()
        assertEquals(SectionKind.LOOSE, course.sections.first().kind)
        val (mkv, mp4) = course.sections[1].elements
        assertNotEquals(mkv.id, mp4.id)
    }
}
