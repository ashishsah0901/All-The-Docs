package com.ashish.allthedocs.server

import java.io.File

object DotEnv {
    private val keyPattern = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun parse(text: String): Map<String, String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val assignment = line.removePrefix("export ").trim()
            val separator = assignment.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val key = assignment.substring(0, separator).trim()
            if (!keyPattern.matches(key)) return@mapNotNull null
            key to unquote(assignment.substring(separator + 1).trim())
        }
        .toMap()

    fun locate(env: (String) -> String?, workingDirectory: File = File(".")): File? {
        env("ENV_FILE")?.takeIf { it.isNotBlank() }?.let { return File(it).takeIf(File::isFile) }
        return listOf(File(workingDirectory, ".env"), File(workingDirectory, "server/.env")).firstOrNull(File::isFile)
    }

    fun lookup(env: (String) -> String? = System::getenv, workingDirectory: File = File(".")): (String) -> String? {
        val fileValues = locate(env, workingDirectory)?.let { parse(it.readText()) }.orEmpty()
        return { key -> env(key) ?: fileValues[key] }
    }

    private fun unquote(value: String): String {
        if (value.length >= 2 && (value.first() == '"' || value.first() == '\'') && value.last() == value.first()) {
            return value.substring(1, value.length - 1)
        }
        val comment = value.indexOf(" #")
        return if (comment >= 0) value.substring(0, comment).trimEnd() else value
    }
}
