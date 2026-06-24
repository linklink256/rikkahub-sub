package me.rerere.rikkahub.utils

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoField
import java.util.Locale

fun Instant.toLocalDate(): String =
    toLocalFormatted(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

fun Instant.toLocalDateTime(): String =
    toLocalFormatted(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM))

fun Instant.toLocalTime(): String =
    toLocalFormatted(DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM))

// ponytail: shared zone+format skeleton for the 3 Instant.toLocal* variants
private fun Instant.toLocalFormatted(formatter: DateTimeFormatter): String {
    val localDateTime = this.atZone(ZoneId.systemDefault()).toLocalDateTime()
    return formatter.withLocale(Locale.getDefault()).format(localDateTime)
}

fun LocalDateTime.toLocalString(): String {
    val locale = Locale.getDefault()
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
    return formatter.format(this)
}

/**
 * 消息时间显示：当天只显示时间（如 14:30），非当天显示「月日 + 时间」（如 5月20日 14:30）。
 */
fun LocalDateTime.toMessageTimeString(): String {
    val locale = Locale.getDefault()
    return if (this.toLocalDate() == LocalDate.now()) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(this)
    } else {
        this.toLocalString()
    }
}

fun LocalDate.toLocalString(includeYear: Boolean): String {
    val locale = Locale.getDefault()
    val formatter = if (includeYear) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    } else {
        if (isMonthFirstLocale(locale)) {
            // Month-day format (e.g., "Sep 20" for US English)
            DateTimeFormatterBuilder()
                .appendText(ChronoField.MONTH_OF_YEAR, TextStyle.SHORT)
                .appendLiteral(' ')
                .appendValue(ChronoField.DAY_OF_MONTH)
                .toFormatter(locale)
        } else {
            // Day-month format (e.g., "20 sep" for Swedish)
            DateTimeFormatterBuilder()
                .appendValue(ChronoField.DAY_OF_MONTH)
                .appendLiteral(' ')
                .appendText(ChronoField.MONTH_OF_YEAR, TextStyle.SHORT)
                .toFormatter(locale)
        }
    }

    return formatter.format(this)
}

private fun isMonthFirstLocale(locale: Locale): Boolean {
    val monthFirstCountries = setOf(
        "US", // 美国
        "PH", // 菲律宾
        "CA", // 加拿大(虽然魁北克可能使用日-月格式)
        "CN", // 中国
    )
    return monthFirstCountries.contains(locale.country)
}
