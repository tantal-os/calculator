package app.tantal.calculator

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "TantalCalculator"
private val nextId = AtomicLong()
private val operandToken = Regex("""@(\d+);""")

internal const val CALCULATE = "↵"
internal const val BACKSPACE = "⌫"

internal class CalculatorState(application: Application) : AndroidViewModel(application) {
    private val settings = application.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val history = History(File(application.filesDir, "history.tsv"))
    private val writes = Channel<Write>(Channel.UNLIMITED)
    private val worker = Dispatchers.Default.limitedParallelism(1)
    private var input by mutableStateOf("")
    private var operands by mutableStateOf(emptyList<Calculation.Answer>())
    private var result by mutableStateOf<Calculation?>(null)
    private var request: Job? = null
    private var generation = 0L
    private var commitPending = false
    private var showError by mutableStateOf(false)

    val entries = mutableStateListOf<Entry>()
    private val pendingEntries = mutableStateListOf<Entry>()
    var committedEntryCount by mutableStateOf(0L)
        private set
    var storageError by mutableStateOf<StorageError?>(null)
        private set
    var radians by mutableStateOf(settings.getBoolean("radians", false))
        private set
    var committed by mutableStateOf<Calculation.Answer?>(null)
        private set

    val expression: String
        get() = operandToken.replace(input) { match ->
            val operand = match.groupValues[1].toIntOrNull()?.let(operands::getOrNull)?.text
            when {
                operand == null -> match.value
                match.value.length != input.length && operand.startsWith('−') -> "($operand)"
                else -> operand
            }
        }
    val preview: Calculation?
        get() = when (val answer = result) {
            is Calculation.Answer -> answer.takeIf { committed == null && it.text != expression }
            is Calculation.Error -> answer.takeIf { showError }
            null -> null
        }

    fun recentEntries(limit: Int): List<Entry> {
        val pending = pendingEntries.takeLast(limit)
        return entries.takeLast(limit - pending.size) + pending
    }

    init {
        viewModelScope.launch {
            try {
                entries.addAll(withContext(Dispatchers.IO) {
                    history.read { Log.w(TAG, "Skipped a malformed history record") }
                })
            } catch (error: IOException) {
                storageFailed(R.string.history_error, error)
            }
            for (operation in writes) {
                try {
                    when (operation) {
                        is Write.Add -> {
                            try {
                                withContext(Dispatchers.IO) { history.add(operation.entry) }
                                entries += operation.entry
                            } finally {
                                pendingEntries.remove(operation.entry)
                            }
                        }
                        Write.Clear -> {
                            withContext(Dispatchers.IO) { history.clear() }
                            entries.clear()
                        }
                        is Write.SetRadians -> withContext(Dispatchers.IO) {
                            if (!settings.edit().putBoolean("radians", operation.radians).commit()) {
                                throw IOException("Could not save angle mode")
                            }
                        }
                    }
                } catch (error: IOException) {
                    storageFailed(operation.error, error)
                }
            }
        }
    }

    fun toggleRadians() {
        radians = !radians
        write(Write.SetRadians(radians))
        refresh()
    }

    fun clearHistory() = write(Write.Clear)

    fun recall(entry: Entry) {
        val answer = entry.answer
        when {
            answer.solution != null -> restore(answer, entry.expression, emptyList())
            committed != null || input.isEmpty() -> {
                if (answer.exact == null) restore(answer, answer.text, emptyList())
                else restore(answer, "@0;", listOf(answer))
            }
            answer.exact == null -> edit(input + (if (input.endsWith(';')) "×" else "") + answer.text)
            operands.size < 16 -> {
                val separator = if (input.last() in "0123456789.;") "×" else ""
                edit("$input$separator@${operands.size};", operands + answer)
            }
            else -> showFailure(Failure.Limit)
        }
    }

    private fun restore(answer: Calculation.Answer, text: String, values: List<Calculation.Answer>) {
        input = text
        operands = values
        committed = answer
        refresh()
    }

    fun press(key: String) {
        val previous = committed
        when (key) {
            "AC" -> edit("")
            CALCULATE -> if (input.isNotEmpty() && previous == null) {
                if (result == null) commitPending = true else finishCommit()
            }
            BACKSPACE -> {
                val match = operandToken.findAll(input).lastOrNull()?.takeIf { it.range.last == input.lastIndex }
                if (previous?.solution != null) {
                    edit(backspace(input))
                } else if (match != null) {
                    val operand = match.groupValues[1].toIntOrNull()?.let(operands::getOrNull)
                    edit(input.take(match.range.first) + backspace(operand?.text.orEmpty()))
                } else {
                    edit(backspace(input))
                }
            }
            else -> {
                val continuing = previous != null && previous.solution == null && key.first() in "$OPERATORS%!"
                val base = if (previous == null || continuing) input else ""
                val actual = if (key == "( )") paren(base) else key
                val wrapped = if (continuing && previous.text.startsWith('−') && actual.first() in "^!") {
                    "($base)"
                } else base
                if (actual == "=" && (base.isEmpty() || '=' in base)) return
                val edited = append(wrapped, actual)
                if (edited != input || previous != null) edit(edited)
            }
        }
    }

    private fun edit(text: String, values: List<Calculation.Answer> = operands) {
        if (text.length > 4096) {
            showFailure(Failure.Limit)
            return
        }
        val retained = mutableListOf<Calculation.Answer>()
        input = operandToken.replace(text) { match ->
            val operand = match.groupValues[1].toIntOrNull()?.let(values::getOrNull)
            if (operand == null) match.value else {
                retained += operand
                "@${retained.lastIndex};"
            }
        }
        operands = retained
        committed = null
        refresh()
    }

    private fun refresh() {
        val id = nextId.incrementAndGet()
        supersede(id)
        request?.cancel()
        result = null
        commitPending = false
        showError = false
        if (input.isEmpty() || committed != null) return
        val text = input
        val saved = operands.mapNotNull(Calculation.Answer::exact)
        val mode = radians
        val start = SystemClock.elapsedRealtimeNanos()
        request = viewModelScope.launch {
            val calculated = withContext(worker) {
                try {
                    NativeEngine.calculate(text, mode, saved, id)
                } catch (error: IllegalStateException) {
                    Log.e(TAG, "Calculation failed", error)
                    Calculation.Error(Failure.Unavailable)
                } catch (error: LinkageError) {
                    Log.e(TAG, "Calculation failed", error)
                    Calculation.Error(Failure.Unavailable)
                }
            }
            if (generation != id) return@launch
            result = calculated
            if (BuildConfig.DEBUG && (text == "18999!" || text == "100000!")) {
                val millis = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                Log.d(TAG, "Preview ready for $text in $millis ms")
            }
            if (commitPending) finishCommit()
        }
    }

    private fun finishCommit() {
        commitPending = false
        when (val answer = result) {
            is Calculation.Answer -> {
                val original = expression
                if (answer.text != original) {
                    val entry = Entry(System.currentTimeMillis(), original, answer)
                    pendingEntries += entry
                    committedEntryCount++
                    write(Write.Add(entry))
                }
                if (answer.exact != null) {
                    input = "@0;"
                    operands = listOf(answer)
                }
                committed = answer
            }
            is Calculation.Error -> showError = true
            null -> Unit
        }
    }

    private fun showFailure(failure: Failure) {
        supersede(nextId.incrementAndGet())
        request?.cancel()
        commitPending = false
        result = Calculation.Error(failure)
        showError = true
    }

    private fun supersede(id: Long) {
        generation = id
        try {
            NativeEngine.supersede(id)
        } catch (error: LinkageError) {
            Log.e(TAG, "Could not cancel the previous calculation", error)
        }
    }

    private fun write(operation: Write) {
        if (writes.trySend(operation).isFailure) {
            if (operation is Write.Add) pendingEntries.remove(operation.entry)
            storageFailed(operation.error, IOException("Storage queue unavailable"))
        }
    }

    private fun storageFailed(message: Int, error: IOException) {
        Log.e(TAG, "Could not save or access calculator data", error)
        storageError = StorageError(message, (storageError?.count ?: 0) + 1)
    }

    override fun onCleared() {
        writes.close()
        super.onCleared()
    }

    data class StorageError(val message: Int, val count: Int)

    private sealed class Write(val error: Int) {
        data class Add(val entry: Entry) : Write(R.string.history_error)
        data object Clear : Write(R.string.history_error)
        data class SetRadians(val radians: Boolean) : Write(R.string.settings_error)
    }
}
