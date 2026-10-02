package io.github.nku100.webui.ui.screen.logs

internal fun toggleExpandedLog(currentExpandedIndex: Int?, selectedIndex: Int): Int? =
    if (currentExpandedIndex == selectedIndex) null else selectedIndex

internal fun LogLine.displayText(): String = message

internal fun LogLine.processMetadata(): String = listOfNotNull(
    pid?.let { "PID $it" },
    tid?.let { "TID $it" },
).joinToString(" · ")
