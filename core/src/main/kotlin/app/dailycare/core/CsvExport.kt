package app.dailycare.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A plain CSV of the log, for taking to appointments or keeping a copy. */
object CsvExport {
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun render(events: List<StoredEvent>, settings: CareSettings, zone: ZoneId): String {
        val cal = CareCalendar(zone, settings.dayBoundary)
        val sb = StringBuilder("local_time,care_day,event,source,details\n")
        for (se in events.sortedBy { it.event.at }) {
            val e = se.event
            val details = Summarizer.describe(e, cal, settings)?.first ?: detailsFallback(e)
            sb.append(Instant.ofEpochMilli(e.at).atZone(zone).format(TIME)).append(',')
                .append(cal.dayOf(e.at)).append(',')
                .append(e.typeName()).append(',')
                .append(escape(e.provenance.label)).append(',')
                .append(escape(details)).append('\n')
        }
        return sb.toString()
    }

    private fun detailsFallback(e: CareEvent): String = when (e) {
        is WalkDetected -> "Short movement, not counted (${e.activeMinutes} min${e.steps?.let { ", $it steps" } ?: ""})"
        is TherapyVisitAsked -> "Asked whether the visit was therapy"
        is DoseReminderSent -> "Medicine reminder" + if (e.followUp) " (follow-up)" else ""
        else -> e.typeName()
    }

    private fun escape(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
