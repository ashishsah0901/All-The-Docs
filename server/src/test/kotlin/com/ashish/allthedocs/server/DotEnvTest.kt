package com.ashish.allthedocs.server

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DotEnvTest {
    @Test
    fun parsesAssignmentsCommentsAndQuotes() {
        val values = DotEnv.parse(
            """
            # comment
            GROQ_API_KEY=gsk_abc

            export PORT = 9090
            QUOTED="hello world"
            SINGLE='a#b'
            INLINE=value # trailing comment
            EMPTY=
            not a line
            1BAD=x
            """.trimIndent(),
        )
        assertEquals(
            mapOf(
                "GROQ_API_KEY" to "gsk_abc",
                "PORT" to "9090",
                "QUOTED" to "hello world",
                "SINGLE" to "a#b",
                "INLINE" to "value",
                "EMPTY" to "",
            ),
            values,
        )
    }

    @Test
    fun realEnvironmentWinsAndServerSubdirectoryIsFound() {
        val root = Files.createTempDirectory("dotenv").toFile()
        try {
            File(root, "server").mkdirs()
            File(root, "server/.env").writeText("GROQ_API_KEY=from-file\nPORT=9000\n")
            val lookup = DotEnv.lookup(env = { if (it == "PORT") "8081" else null }, workingDirectory = root)
            assertEquals("from-file", lookup("GROQ_API_KEY"))
            assertEquals("8081", lookup("PORT"))
            assertNull(lookup("MISSING"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun envFileVariableTakesPrecedence() {
        val root = Files.createTempDirectory("dotenv").toFile()
        try {
            File(root, ".env").writeText("KEY=working-dir\n")
            val custom = File(root, "custom.env").apply { writeText("KEY=custom\n") }
            val lookup = DotEnv.lookup(env = { if (it == "ENV_FILE") custom.absolutePath else null }, workingDirectory = root)
            assertEquals("custom", lookup("KEY"))
        } finally {
            root.deleteRecursively()
        }
    }
}
