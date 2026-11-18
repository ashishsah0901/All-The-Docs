package com.ashish.allthedocs.ui

import com.ashish.allthedocs.api.RequirementOrigin
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
fun today(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())

fun RequirementOrigin.label(): String = when (this) {
    RequirementOrigin.CURATED -> "Curated rules"
    RequirementOrigin.LLM_WEB_SEARCH -> "AI + web search"
    RequirementOrigin.LLM_KNOWLEDGE -> "AI (no live search)"
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${(bytes * 10 / (1024 * 1024)) / 10.0} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
