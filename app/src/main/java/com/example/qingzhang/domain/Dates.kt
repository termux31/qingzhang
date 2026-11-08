package com.example.qingzhang.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日")
private val shortDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")
private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

fun LocalDate.startMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
    atStartOfDay(zone).toInstant().toEpochMilli()

fun LocalDate.endExclusiveMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
    plusDays(1).startMillis(zone)

fun YearMonth.startMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
    atDay(1).startMillis(zone)

fun YearMonth.endExclusiveMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
    plusMonths(1).startMillis(zone)

fun Long.toLocalDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

fun Long.toLocalDateTime(zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDateTime()

fun LocalDateTime.toMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
    atZone(zone).toInstant().toEpochMilli()

fun LocalDate.withCurrentTime(): LocalDateTime = atTime(LocalTime.now().withSecond(0).withNano(0))

fun Long.formatDate(): String = toLocalDate().format(dateFormatter)

fun Long.formatShortDate(): String = toLocalDate().format(shortDateFormatter)

fun Long.formatDateTime(): String = toLocalDateTime().format(dateTimeFormatter)
