package com.example.assetsync.unit

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards against the documentation drifting away from the code. Each check derives its
 * expectations from the source of truth under `src/` and asserts that the owning document still
 * mentions every item, so adding a changeset, a meter, or a ProblemDetail type without documenting
 * it fails the build. Paths are relative to the project directory, which is Gradle's working
 * directory for tests (the same convention as GeneratedJooqSourceControlTests).
 */
class DocsConsistencyTests {

    private val masterChangelog = Path.of("src/main/resources/db/changelog/db.changelog-master.yaml")
    private val changesDirectory = Path.of("src/main/resources/db/changelog/changes")
    private val metricsSource =
        Path.of("src/main/kotlin/com/example/assetsync/application/observability/AssetSyncMetrics.kt")
    private val apiErrorSources = Path.of("src/main/kotlin/com/example/assetsync/api/error")

    @Test
    fun `every changeset file is included by the master changelog exactly once`() {
        val included = includedChangesets()
        val onDisk = changesDirectory.listDirectoryEntries("*.yaml").map { it.name }.toSortedSet()

        assertEquals(onDisk, included.toSortedSet(), "master changelog includes must match the files under changes/")
        assertEquals(included.toSet().size, included.size, "master changelog must not include a changeset twice")
    }

    @Test
    fun `every changeset is documented in the database specification`() {
        val databaseDoc = Files.readString(Path.of("docs/database.md"))

        val undocumented = includedChangesets().filterNot { databaseDoc.contains(it) }
        assertTrue(undocumented.isEmpty(), "docs/database.md must list every changeset; missing: $undocumented")
    }

    @Test
    fun `every registered meter is documented in the architecture specification`() {
        val architectureDoc = Files.readString(Path.of("docs/architecture.md"))
        val meters = Regex("\"(asset\\.sync\\.[a-z.]+)\"")
            .findAll(Files.readString(metricsSource))
            .map { it.groupValues[1] }
            .toSortedSet()
        assertTrue(meters.isNotEmpty(), "expected meter names in AssetSyncMetrics")

        val undocumented = meters.filterNot { architectureDoc.contains("`$it") }
        assertTrue(undocumented.isEmpty(), "docs/architecture.md must describe every meter; missing: $undocumented")
    }

    @Test
    fun `every problem detail type is documented in the api specification`() {
        val apiDoc = Files.readString(Path.of("docs/api.md"))
        val apiErrorSource = apiErrorSources.listDirectoryEntries("*.kt").joinToString("\n") { Files.readString(it) }
        val types = Regex("type = \"([a-z-]+)\"")
            .findAll(apiErrorSource)
            .map { it.groupValues[1] }
            .toSortedSet()
        assertTrue(types.isNotEmpty(), "expected literal ProblemDetail types in the api/error package")

        val undocumented = types.filterNot { apiDoc.contains("errors/$it`") }
        assertTrue(undocumented.isEmpty(), "docs/api.md must map every ProblemDetail type; missing: $undocumented")
    }

    private fun includedChangesets(): List<String> =
        Regex("file: db/changelog/changes/([0-9A-Za-z-]+\\.yaml)")
            .findAll(Files.readString(masterChangelog))
            .map { it.groupValues[1] }
            .toList()
}
