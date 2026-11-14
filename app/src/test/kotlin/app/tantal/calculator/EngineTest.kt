package app.tantal.calculator

import java.text.DecimalFormatSymbols
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineTest {
    private fun calc(expression: String, radians: Boolean = false) = evaluate(expression, radians)?.let(::format)

    @Test
    fun evaluates() {
        assertEquals("192.6", calc("1284×0.15"))
        assertEquals("3600", calc("86400÷24"))
        assertEquals("310", calc("2480×12.5%"))
        assertEquals("65536", calc("2^16"))
        assertEquals("1.91421356237", calc("sin(π÷6)+√2", radians = true))
        assertEquals("0.3", calc("0.1+0.2"))
        assertEquals("14", calc("2+3×4"))
        assertEquals("−4", calc("−2^2"))
        assertEquals("512", calc("2^3^2"))
        assertEquals("0.125", calc("2^−3"))
        assertEquals("110", calc("100+10%"))
        assertEquals("90", calc("100−10%"))
        assertEquals("100.2", calc("100+10%×2"))
        assertEquals("20", calc("2(3+7"))
        assertEquals("6.28318530718", calc("2π"))
        assertEquals("120", calc("5!"))
        assertEquals("0.5", calc("sin(30)"))
        assertEquals("0", calc("sin(π)", radians = true))
        assertEquals("0", calc("cos(90)"))
        assertEquals("30", calc("sin⁻¹(0.5)"))
        assertEquals("3", calc("log(1000)"))
        assertEquals("1", calc("ln(e)"))
        assertEquals("5", calc("abs(2−7)"))
        assertEquals("0.2", calc("5^(−1)"))
        assertEquals("1E20", calc("10^20"))
        assertEquals("2.5E−9", calc("2.5E−7÷100"))
        assertEquals("999999999999", calc("999999999999"))
        for (broken in listOf("", "2+", "1÷0", "tan(90)", "√−1", "2.5!", "(−1)!", "ln(0)", "2)", "%", "1..2")) {
            assertNull(broken, calc(broken))
        }
    }

    @Test
    fun edits() {
        assertEquals("2×", append("2+", "×"))
        assertEquals("2×−", append("2×", "−"))
        assertEquals("2+", append("2×−", "+"))
        assertEquals("2−", append("2+", "−"))
        assertEquals("", append("", "×"))
        assertEquals("−", append("−", "+"))
        assertEquals("(", append("(", "÷"))
        assertEquals("1.5", append("1.5", "."))
        assertEquals("1.5+.", append("1.5+", "."))
        assertEquals("7", append("192.6", "7", evaluated = true))
        assertEquals("192.6+", append("192.6", "+", evaluated = true))
        assertEquals("(−5)^2", append("−5", "^2", evaluated = true))

        assertEquals("2+", backspace("2+sin("))
        assertEquals("2+", backspace("2+sin⁻¹("))
        assertEquals("e^", backspace("e^("))
        assertEquals("", backspace(""))

        assertEquals("(", paren(""))
        assertEquals("(", paren("2×"))
        assertEquals(")", paren("(2+3"))
        assertEquals("(", paren("(2+"))
        assertEquals("(", paren("(2+3)"))
    }

    @Test
    fun displays() {
        val us = DecimalFormatSymbols.getInstance(Locale.US)
        assertEquals("1,284 × 0.15", display("1284×0.15", us))
        assertEquals("sin(π ÷ 6) + √2", display("sin(π÷6)+√2", us))
        assertEquals("2 × −3", display("2×−3", us))
        assertEquals("1,234,567.8901", display("1234567.8901", us))
        assertEquals("2.5E−9", display("2.5E−9", us))
        assertEquals("1.234,5", display("1234.5", DecimalFormatSymbols.getInstance(Locale.GERMANY)))
    }
}
