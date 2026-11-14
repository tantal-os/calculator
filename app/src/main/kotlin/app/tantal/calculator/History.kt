package app.tantal.calculator

import androidx.compose.runtime.mutableStateListOf
import java.io.File

class Entry(val time: Long, val expression: String, val result: String)

class History(private val file: File) {
    val entries = mutableStateListOf<Entry>()

    init {
        if (file.exists()) {
            file.forEachLine { line ->
                val fields = line.split('\t')
                val time = fields[0].toLongOrNull()
                if (fields.size == 3 && time != null) entries += Entry(time, fields[1], fields[2])
            }
        }
    }

    fun add(expression: String, result: String) {
        val entry = Entry(System.currentTimeMillis(), expression, result)
        entries += entry
        file.appendText("${entry.time}\t$expression\t$result\n")
    }

    fun clear() {
        entries.clear()
        file.delete()
    }
}
