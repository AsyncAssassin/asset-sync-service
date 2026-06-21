package com.example.assetsync.unit

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class GeneratedJooqSourceControlTests {

    @Test
    fun `generated jooq sources stay under ignored build directory`() {
        val gitignore = Files.readString(Path.of(".gitignore"))
        val generatedJooqPath = Path.of("build/generated/sources/jooq/main/kotlin")

        assertTrue(
            gitignore.lineSequence().map { it.trim() }.any { it == "build/" },
            "The build directory must stay ignored because generated jOOQ sources are recreated by Gradle.",
        )
        assertTrue(
            generatedJooqPath.startsWith(Path.of("build")),
            "Generated jOOQ sources must remain under the ignored build directory.",
        )
    }
}
