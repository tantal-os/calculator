package app.tantal.calculator

import java.io.File
import java.io.RandomAccessFile

internal data class Entry(
    val time: Long,
    val expression: String,
    val answer: Calculation.Answer,
) {
    fun encode() = listOf(time, expression, answer.text, answer.exact.orEmpty(), answer.solution?.name.orEmpty())
        .joinToString("\t")

    companion object {
        fun decode(line: String): Entry? {
            val fields = line.split('\t', limit = 6)
            val time = fields.first().toLongOrNull() ?: return null
            val answer = when (fields.size) {
                3 -> Calculation.Answer(fields[2])
                5 -> {
                    val exact = fields[3].ifEmpty { null }
                    val kind = SolutionKind.entries.firstOrNull { it.name == fields[4] }
                    if ((exact == null) == (kind == null)) return null
                    Calculation.Answer(fields[2], exact, kind)
                }
                else -> return null
            }
            return Entry(time, fields[1], answer)
        }
    }
}

internal class History(private val file: File) {
    fun read(onMalformed: () -> Unit): List<Entry> {
        if (!file.exists()) return emptyList()
        return file.useLines { lines ->
            lines.mapNotNull { line -> Entry.decode(line) ?: run { onMalformed(); null } }.toList()
        }
    }

    fun add(entry: Entry) {
        RandomAccessFile(file, "rw").use { output ->
            val end = output.length()
            if (end > 0) {
                output.seek(end - 1)
                if (output.read() != '\n'.code) output.write('\n'.code)
            }
            output.write((entry.encode() + "\n").toByteArray(Charsets.UTF_8))
        }
    }

    fun clear() {
        java.nio.file.Files.deleteIfExists(file.toPath())
    }
}
