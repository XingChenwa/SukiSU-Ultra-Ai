package com.sukisu.ultra.ui.screen.ai.data

import android.content.Context
import android.net.Uri
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Parses a KPM project out of the LLM's latest reply and writes it to a user-chosen
 * Uri (via SAF) as a zip. We intentionally extract code only from fenced blocks
 * whose info string contains `name=<filename>`; anything else is ignored so
 * accidental snippets inside explanations are not exported.
 */
object KpmProjectExporter {

    data class ProjectFile(val name: String, val content: String)

    fun extract(reply: String): List<ProjectFile> {
        val out = mutableListOf<ProjectFile>()
        val lines = reply.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```")) {
                val info = trimmed.removePrefix("```")
                val fileName = parseFileName(info)
                if (fileName != null) {
                    val body = StringBuilder()
                    i++
                    while (i < lines.size) {
                        val inner = lines[i]
                        if (inner.trimStart().startsWith("```")) break
                        if (body.isNotEmpty()) body.append('\n')
                        body.append(inner)
                        i++
                    }
                    out.add(ProjectFile(fileName, body.toString()))
                }
            }
            i++
        }
        return out.distinctBy { it.name }
    }

    private fun parseFileName(info: String): String? {
        // Accepts forms:  c name=main.c    /   name=Makefile   /   "makefile name=Makefile"
        val match = Regex("name\\s*=\\s*([A-Za-z0-9_./\\-]+)").find(info) ?: return null
        val raw = match.groupValues[1]
        // Prevent path traversal; we never want .. or absolute paths in the archive.
        if (raw.contains("..") || raw.startsWith("/")) return null
        return raw
    }

    fun writeZip(context: Context, target: Uri, files: List<ProjectFile>): Boolean {
        val resolver = context.contentResolver
        val stream: OutputStream = resolver.openOutputStream(target) ?: return false
        return runCatching {
            stream.use { os ->
                ZipOutputStream(os).use { zip ->
                    files.forEach { file ->
                        zip.putNextEntry(ZipEntry(file.name))
                        zip.write(file.content.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                }
            }
            true
        }.getOrElse { false }
    }
}
