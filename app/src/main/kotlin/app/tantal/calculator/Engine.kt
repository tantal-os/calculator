package app.tantal.calculator

import java.text.DecimalFormatSymbols

internal const val OPERATORS = "+−×÷^"
/** Groups digits, applies the locale separators and puts spaces around binary operators. */
internal fun display(expression: String, symbols: DecimalFormatSymbols = DecimalFormatSymbols.getInstance()): String =
    expression
        .replace(Regex("""\s*=\s*"""), " = ")
        .replace(Regex("""(?<=[\d.)πex%!])[$OPERATORS]"""), " $0 ")
        .replace(Regex("""(\d+\.?\d*|\.\d+)(E[+−]?\d+)?""")) { match ->
            val mantissa = match.groupValues[1]
            val whole = mantissa.substringBefore('.').reversed().chunked(3)
                .joinToString(symbols.groupingSeparator.toString()).reversed()
            val decimal = if ('.' in mantissa) whole + symbols.decimalSeparator + mantissa.substringAfter('.') else whole
            decimal + match.groupValues[2]
        }

/** [evaluated] means the expression is the result of Calculate: a new value replaces it, an operator continues it. */
fun append(expression: String, key: String, evaluated: Boolean = false): String = when {
    evaluated && key[0] !in "$OPERATORS%!" -> append("", key)
    evaluated && expression.startsWith('−') && key[0] in "^!" -> "($expression)$key"
    key == "." && Regex("""\.\d*$""").containsMatchIn(expression) -> expression
    key.length > 1 || key[0] !in OPERATORS -> expression + key
    key == "−" -> expression.removeSuffix("+").removeSuffix("−") + key
    else -> expression.trimEnd { it in OPERATORS }
        .let { if (it.isEmpty() || it.endsWith('(')) expression else it + key }
}

fun backspace(expression: String): String = expression.replace(Regex("""([a-z]+(⁻¹)?\(|.)$"""), "")

fun paren(expression: String): String {
    val open = expression.count { it == '(' } - expression.count { it == ')' }
    return if (open > 0 && expression.last() !in "($OPERATORS√=") ")" else "("
}

internal enum class Failure(val code: String) {
    Incomplete("incomplete"),
    Invalid("invalid"),
    Domain("domain"),
    Unsupported("unsupported"),
    Limit("limit"),
    Cancelled("cancelled"),
    Unavailable("unavailable"),
}

internal enum class SolutionKind { All, Empty, One, Two, Radical }

internal sealed interface Calculation {
    data class Answer(val text: String, val exact: String? = null, val solution: SolutionKind? = null) : Calculation
    data class Error(val failure: Failure) : Calculation
}

internal object NativeEngine {
    private val loadError = try {
        System.loadLibrary("tantal_calculator")
        null
    } catch (error: LinkageError) {
        error
    } catch (error: SecurityException) {
        error
    }

    @JvmStatic private external fun setGeneration(id: Long)
    @JvmStatic private external fun evaluate(expression: String, values: Array<String>, radians: Boolean, id: Long): String?

    fun supersede(id: Long) {
        if (loadError == null) setGeneration(id)
    }

    fun calculate(expression: String, radians: Boolean, values: List<String> = emptyList(), id: Long = 0): Calculation {
        loadError?.let { throw IllegalStateException("Could not load calculator engine", it) }
        val fields = evaluate(expression, values.toTypedArray(), radians, id)?.split('\n', limit = 4)
            ?: return Calculation.Error(Failure.Unavailable)
        fun solution(text: String, kind: SolutionKind) = Calculation.Answer(text, solution = kind)
        return when (fields.first()) {
            "number" -> if (fields.size == 3) Calculation.Answer(fields[1], exact = fields[2]) else malformed()
            "all" -> solution("x ∈ ℝ", SolutionKind.All)
            "empty" -> solution("∅", SolutionKind.Empty)
            "one" -> if (fields.size == 2) solution("x = ${fields[1]}", SolutionKind.One) else malformed()
            "two" -> if (fields.size == 3) solution("x₁ = ${fields[1]}, x₂ = ${fields[2]}", SolutionKind.Two) else malformed()
            "radical" -> if (fields.size == 4) {
                val center = fields[1].takeIf(String::isNotEmpty)?.plus(" ") ?: ""
                val factor = fields[2].let { if ('/' in it) "($it)" else it }
                solution("x = $center±$factor√${fields[3]}", SolutionKind.Radical)
            } else malformed()
            else -> Calculation.Error(Failure.entries.firstOrNull { it.code == fields.first() } ?: Failure.Unavailable)
        }
    }

    private fun malformed() = Calculation.Error(Failure.Unavailable)
}
