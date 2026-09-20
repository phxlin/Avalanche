package com.avalanche.app.util

import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale
import kotlin.math.abs

class MoneyFormatter(val currencyCode: String) {
    private val currency: Currency = runCatching { Currency.getInstance(currencyCode) }.getOrElse { Currency.getInstance("USD") }
    private val full: NumberFormat = NumberFormat.getCurrencyInstance(Locale.getDefault()).apply { this.currency = this@MoneyFormatter.currency }

    fun format(amount: Double): String = full.format(amount)

    /** Short form for chart axes: 1.2M, 12k, 850. */
    fun compact(amount: Double): String {
        val symbol = currency.getSymbol(Locale.getDefault())
        val a = abs(amount)
        val body = when {
            a >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", a / 1_000_000)
            a >= 10_000 -> String.format(Locale.getDefault(), "%.0fk", a / 1_000)
            a >= 1_000 -> String.format(Locale.getDefault(), "%.1fk", a / 1_000)
            else -> String.format(Locale.getDefault(), "%.0f", a)
        }
        return (if (amount < 0) "-" else "") + symbol + body
    }
}

private val monthFormat: DateTimeFormatter get() = DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())
private val dateFormat: DateTimeFormatter get() = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())

fun formatMonth(month: YearMonth): String = month.format(monthFormat)
fun formatDate(date: LocalDate): String = date.format(dateFormat)
fun formatEpochDay(day: Long): String = formatDate(LocalDate.ofEpochDay(day))

/** A percentage to at most two decimals with trailing zeros dropped (32%, 32.5%, 32.25%). Rounds, never truncates. */
fun formatPercentPoints(percent: Double): String =
    String.format(Locale.getDefault(), "%.2f", percent).trimEnd('0').trimEnd('.', ',') + "%"

/** [fraction] is 0..1. */
fun formatPercent(fraction: Float): String = formatPercentPoints(fraction.toDouble() * 100.0)

fun formatApr(apr: Double): String = formatPercentPoints(apr)

fun formatDuration(months: Int): String {
    val y = months / 12
    val m = months % 12
    return when {
        months <= 0 -> "0 months"
        y == 0 -> "$m ${if (m == 1) "month" else "months"}"
        m == 0 -> "$y ${if (y == 1) "year" else "years"}"
        else -> "$y yr $m mo"
    }
}

/**
 * Parses a user-typed decimal. Accepts either '.' or ',' as the decimal separator (input is
 * sanitised to at most one separator by [sanitizeDecimalInput]).
 */
fun parseDecimal(input: String): Double? {
    val s = input.trim().replace(',', '.')
    if (s.isEmpty() || s == ".") return null
    return s.toDoubleOrNull()?.takeIf { it.isFinite() }
}

/** Keeps digits and a single decimal separator, limiting the digits after it. */
fun sanitizeDecimalInput(input: String, maxDecimals: Int = 2): String {
    val out = StringBuilder()
    var sepAt = -1
    for (c in input) {
        when {
            c.isDigit() -> if (sepAt < 0 || out.length - sepAt <= maxDecimals) out.append(c)
            (c == '.' || c == ',') && sepAt < 0 && maxDecimals > 0 -> {
                sepAt = out.length
                out.append(c)
            }
        }
    }
    return out.toString()
}

/**
 * Plain-number rendering for pre-filling text fields, with up to [maxDecimals] decimals and no trailing zeros.
 * Pass the same [maxDecimals] the field accepts, or saving an untouched field would round the stored value.
 */
fun editableNumber(value: Double, maxDecimals: Int = 2): String =
    if (value == 0.0) "" else String.format(Locale.US, "%.${maxDecimals}f", value).trimEnd('0').trimEnd('.')

/** "A", "A and B", "A, B and C". */
fun joinNames(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
}
