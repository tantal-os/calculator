package app.tantal.calculator

import java.math.BigDecimal
import java.math.MathContext
import java.text.DecimalFormatSymbols
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private val precision = MathContext(12)

private const val OPERATORS = "+−×÷^"
private val number = Regex("""\d+\.?\d*(E[+−]?\d+)?|\.\d+""")
private val functions = listOf("sin⁻¹(", "cos⁻¹(", "tan⁻¹(", "sin(", "cos(", "tan(", "ln(", "log(", "abs(")

/** Returns null if the expression is incomplete or has no finite value. */
fun evaluate(expression: String, radians: Boolean): Double? =
    runCatching { Parser(expression, radians).parse() }.getOrNull()?.takeIf { it.isFinite() }

fun format(value: Double): String {
    val rounded = BigDecimal(value).round(precision).stripTrailingZeros()
    val exponent = rounded.precision() - rounded.scale() - 1
    val text = if (exponent in -7..11) rounded.toPlainString() else rounded.toString().replace("E+", "E")
    return text.replace('-', '−')
}

/** Groups digits, applies the locale separators and puts spaces around binary operators. */
fun display(expression: String, symbols: DecimalFormatSymbols = DecimalFormatSymbols.getInstance()): String =
    expression
        .replace(Regex("""(?<=[\d.)πe%!])[$OPERATORS]"""), " $0 ")
        .replace(Regex("""\d+\.?\d*|\.\d+""")) { match ->
            val whole = match.value.substringBefore('.').reversed().chunked(3)
                .joinToString(symbols.groupingSeparator.toString()).reversed()
            if ('.' in match.value) whole + symbols.decimalSeparator + match.value.substringAfter('.') else whole
        }

/** [evaluated] means the expression is the result of "=": a new value replaces it, an operator continues it. */
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
    return if (open > 0 && expression.last() !in "($OPERATORS√") ")" else "("
}

private class Parser(private val s: String, private val radians: Boolean) {
    private var i = 0

    // True while the term just parsed is a bare percentage, which "+" and "−" take from the left operand.
    private var percent = false

    fun parse(): Double = sum().also { require(i == s.length) }

    private fun eat(c: Char) = (i < s.length && s[i] == c).also { if (it) i++ }

    private fun sum(): Double {
        var acc = product()
        while (true) {
            val sign = if (eat('+')) 1 else if (eat('−')) -1 else return acc
            val term = product()
            acc += sign * if (percent) acc * term else term
        }
    }

    private fun product(): Double {
        var acc = unary()
        while (i < s.length && s[i] !in "+−)") {
            acc = if (eat('÷')) {
                acc / unary()
            } else {
                // Multiplication may be implicit, but not before a number: "1.2.3" is a typo, not 1.2 × 0.3.
                require(eat('×') || !(s[i].isDigit() || s[i] == '.'))
                acc * unary()
            }
            percent = false
        }
        return acc
    }

    private fun unary(): Double = when {
        eat('−') -> -unary()
        eat('√') -> sqrt(unary())
        else -> power()
    }

    private fun power(): Double {
        val base = postfix()
        if (!eat('^')) return base
        return base.pow(unary()).also { percent = false }
    }

    private fun postfix(): Double {
        var value = primary()
        percent = false
        while (true) {
            if (eat('!')) {
                value = factorial(value)
                percent = false
            } else if (eat('%')) {
                value /= 100
                percent = true
            } else {
                return value
            }
        }
    }

    // A closing parenthesis is optional, so an expression still being typed has a value.
    private fun primary(): Double {
        if (eat('(')) return sum().also { eat(')') }
        if (eat('π')) return PI
        if (eat('e')) return E
        functions.firstOrNull { s.startsWith(it, i) }?.let { name ->
            i += name.length
            return call(name, sum()).also { eat(')') }
        }
        val match = requireNotNull(number.matchAt(s, i))
        i = match.range.last + 1
        return match.value.replace('−', '-').toDouble()
    }

    private fun call(name: String, x: Double): Double {
        val angle = if (radians) x else Math.toRadians(x)
        fun fromAngle(r: Double) = if (radians) r else Math.toDegrees(r)

        // sin(π) and cos(90°) are not exactly zero in floating point.
        fun snap(v: Double) = if (abs(v) < 1e-15) 0.0 else v
        return when (name) {
            "sin(" -> snap(sin(angle))
            "cos(" -> snap(cos(angle))
            "tan(" -> snap(sin(angle)) / snap(cos(angle))
            "sin⁻¹(" -> fromAngle(asin(x))
            "cos⁻¹(" -> fromAngle(acos(x))
            "tan⁻¹(" -> fromAngle(atan(x))
            "ln(" -> ln(x)
            "log(" -> log10(x)
            else -> abs(x)
        }
    }

    private fun factorial(x: Double): Double {
        require(x >= 0 && x == floor(x) && x <= 170)
        return (2..x.toInt()).fold(1.0) { acc, n -> acc * n }
    }
}
