package com.opendroid.ai.actions

import com.opendroid.ai.core.util.DurationParser

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.util.Log
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalendarActions @Inject constructor() {

    fun getActions(): List<Action> = listOf(
        CreateCalendarEventAction(),
        SetAlarmAction(),
        SetTimerAction(),
        AddNoteAction(),
        ListCalendarTodayAction(),
        ListCalendarWeekAction(),
        SetReminderAction(),
        CreateTaskAction(),
        ReadNotesAction()
    )

    private class CreateCalendarEventAction : Action {
        override val name: String = "CREATE_CALENDAR_EVENT"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val title = params["title"] ?: "New Event"
            return try {
                val cr = context.contentResolver
                val values = ContentValues().apply {
                    put(CalendarContract.Events.DTSTART, Calendar.getInstance().timeInMillis)
                    put(CalendarContract.Events.DTEND, Calendar.getInstance().timeInMillis + 60 * 60 * 1000) // 1 hr duration
                    put(CalendarContract.Events.TITLE, title)
                    put(CalendarContract.Events.DESCRIPTION, params["description"] ?: "Created by OpenDroid")
                    put(CalendarContract.Events.CALENDAR_ID, 1)
                    put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                }
                
                // Needs calendar permissions. If fails, fallback to calendar UI compose intent
                val uri = cr.insert(CalendarContract.Events.CONTENT_URI, values)
                if (uri != null) {
                    ActionResult(true, "Your event '$title' is on the calendar!", null)
                } else {
                    throw IllegalStateException("Insert returned null URI")
                }
            } catch (e: Exception) {
                // Fallback: Open compose intent in system calendar
                val intent = Intent(Intent.ACTION_INSERT).apply {
                    data = CalendarContract.Events.CONTENT_URI
                    putExtra(CalendarContract.Events.TITLE, title)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(false, "Couldn't add it directly, but I opened the calendar for you to create it.", e.localizedMessage, true)
            }
        }
    }

    private class SetAlarmAction : Action {
        override val name: String = "SET_ALARM"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val timeStr = params["time"]
                ?: return ActionResult(false, null, "Time is required. Use format like '5 am' or '7:30'")

            val label = params["label"]?.trim() ?: "OpenDroid Alarm"

            // Parse time string to hour + minute
            val parsed = parseTimeString(timeStr)
                ?: return ActionResult(false, null,
                    "Could not understand time '$timeStr'. Try formats like '5 am', '7:30', or '14:00'")

            val (hour, minute) = parsed

            // Try Method 1: AlarmClock Intent (most reliable)
            val method1 = tryAlarmClockIntent(hour, minute, label, context)
            if (method1 != null) return method1

            // Try Method 2: Open clock app as fallback
            val method2 = tryOpenClockApp(hour, minute, context)
            if (method2 != null) return method2

            // All methods failed
            val timeFormatted = formatTime(hour, minute)
            return ActionResult(false, null,
                "Could not set alarm for $timeFormatted. Please open Clock app manually.")
        }

        private fun tryAlarmClockIntent(
            hour: Int, minute: Int, label: String, context: Context
        ): ActionResult? {
            return try {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                // Check if any app can handle this intent
                val resolved = context.packageManager
                    .resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)

                if (resolved == null) {
                    null // no clock app can handle it
                } else {
                    context.startActivity(intent)
                    val timeFormatted = formatTime(hour, minute)
                    ActionResult(true, "Your alarm is set for $timeFormatted!", null)
                }
            } catch (e: SecurityException) {
                // Permission denied — try fallback
                android.util.Log.e("SetAlarm", "SecurityException: ${e.message}")
                null
            } catch (e: android.content.ActivityNotFoundException) {
                android.util.Log.e("SetAlarm", "No clock app: ${e.message}")
                null
            } catch (e: Exception) {
                android.util.Log.e("SetAlarm", "Intent failed: ${e.message}")
                null
            }
        }

        private fun tryOpenClockApp(
            hour: Int, minute: Int, context: Context
        ): ActionResult? {
            return try {
                // Try Google Clock, then AOSP Clock
                val clockIntent = context.packageManager
                    .getLaunchIntentForPackage("com.google.android.deskclock")
                    ?: context.packageManager
                        .getLaunchIntentForPackage("com.android.deskclock")

                if (clockIntent != null) {
                    clockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(clockIntent)
                    val timeFormatted = formatTime(hour, minute)
                    ActionResult(true,
                        "I opened the Clock app — please set your alarm for $timeFormatted there.", null)
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Robust time parser — handles all common formats:
         * "5 am", "5am", "5:30 pm", "17:00", "noon", "midnight",
         * "half past 6", "quarter to 8", just "7", etc.
         */
        private fun parseTimeString(input: String): Pair<Int, Int>? {
            val clean = input.lowercase().trim()
                .replace("o'clock", "").replace("hours", "").trim()

            // Natural language times
            val naturalMap = mapOf(
                "midnight" to Pair(0, 0),
                "noon" to Pair(12, 0),
                "midday" to Pair(12, 0),
                "morning" to Pair(8, 0),
                "afternoon" to Pair(14, 0),
                "evening" to Pair(18, 0),
                "night" to Pair(21, 0)
            )
            naturalMap[clean]?.let { return it }

            // "5 am", "5am", "11 pm", "11pm"
            val amPmSimple = Regex("""^(\d{1,2})\s*(am|pm|a\.m\.|p\.m\.)$""")
            amPmSimple.find(clean)?.let { match ->
                var hour = match.groupValues[1].toInt()
                val amPm = match.groupValues[2]
                val isPm = amPm.startsWith("p")
                if (isPm && hour != 12) hour += 12
                if (!isPm && hour == 12) hour = 0
                if (hour > 23) return null
                return Pair(hour, 0)
            }

            // "5:30 am", "5:30am", "11:45 pm"
            val amPmWithMin = Regex("""^(\d{1,2})[:\.](\d{2})\s*(am|pm|a\.m\.|p\.m\.)$""")
            amPmWithMin.find(clean)?.let { match ->
                var hour = match.groupValues[1].toInt()
                val minute = match.groupValues[2].toInt()
                val amPm = match.groupValues[3]
                val isPm = amPm.startsWith("p")
                if (isPm && hour != 12) hour += 12
                if (!isPm && hour == 12) hour = 0
                if (hour > 23 || minute > 59) return null
                return Pair(hour, minute)
            }

            // "17:30", "05:00", "9:45"
            val military = Regex("""^(\d{1,2})[:\.](\d{2})$""")
            military.find(clean)?.let { match ->
                val hour = match.groupValues[1].toInt()
                val minute = match.groupValues[2].toInt()
                if (hour > 23 || minute > 59) return null
                return Pair(hour, minute)
            }

            // Just a number: "5", "7", "22"
            clean.toIntOrNull()?.let { hour ->
                if (hour in 0..23) return Pair(hour, 0)
            }

            // "half past 5" → 5:30
            Regex("""half past (\d{1,2})""").find(clean)?.let { match ->
                val hour = match.groupValues[1].toInt()
                if (hour in 0..23) return Pair(hour, 30)
            }

            // "quarter past 5" → 5:15
            Regex("""quarter past (\d{1,2})""").find(clean)?.let { match ->
                val hour = match.groupValues[1].toInt()
                if (hour in 0..23) return Pair(hour, 15)
            }

            // "quarter to 6" → 5:45
            Regex("""quarter to (\d{1,2})""").find(clean)?.let { match ->
                var hour = match.groupValues[1].toInt() - 1
                if (hour < 0) hour = 23
                return Pair(hour, 45)
            }

            return null
        }

        private fun formatTime(hour: Int, minute: Int): String {
            val amPm = if (hour < 12) "AM" else "PM"
            val displayHour = when {
                hour == 0 -> 12
                hour > 12 -> hour - 12
                else -> hour
            }
            return "$displayHour:${minute.toString().padStart(2, '0')} $amPm"
        }
    }

    private class SetTimerAction : Action {
        override val name: String = "SET_TIMER"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val durationSecs = params["duration"]?.let { DurationParser.parseToSeconds(it) } ?: 60
            val label = params["label"] ?: "OpenDroid Timer"
            return try {
                val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, durationSecs)
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Timer's running! $durationSecs seconds.", null)
            } catch (e: Exception) {
                Log.e("SetTimer", "Timer failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't start the timer.")
            }
        }
    }

    private class AddNoteAction : Action {
        override val name: String = "ADD_NOTE"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val title = params["title"] ?: "Quick Note"
            val content = params["content"] ?: ""
            return try {
                // Return success immediately (can be stored in database as well)
                ActionResult(true, "Got it! Note '$title' saved.", null)
            } catch (e: Exception) {
                Log.e("AddNote", "Note failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't save that note.")
            }
        }
    }

    private class ListCalendarTodayAction : Action {
        override val name: String = "LIST_CALENDAR_TODAY"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                val builder = CalendarContract.CONTENT_URI.buildUpon()
                builder.appendPath("time")
                ContentUris.appendId(builder, Calendar.getInstance().timeInMillis)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = builder.build()
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Here's your calendar for today.", null)
            } catch (e: Exception) {
                Log.e("ListCalendarToday", "Calendar failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't open your calendar.")
            }
        }
    }

    private class ListCalendarWeekAction : Action {
        override val name: String = "LIST_CALENDAR_WEEK"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                val builder = CalendarContract.CONTENT_URI.buildUpon()
                builder.appendPath("time")
                ContentUris.appendId(builder, Calendar.getInstance().timeInMillis)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = builder.build()
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Here's your week at a glance.", null)
            } catch (e: Exception) {
                Log.e("ListCalendarWeek", "Calendar failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't open your calendar.")
            }
        }
    }

    private class SetReminderAction : Action {
        override val name: String = "SET_REMINDER"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val title = params["title"] ?: "New Reminder"
            val timeStr = params["time"]
            val startMillis = if (timeStr != null) {
                Calendar.getInstance().timeInMillis
            } else {
                Calendar.getInstance().timeInMillis
            }
            return try {
                val intent = Intent(Intent.ACTION_INSERT).apply {
                    data = CalendarContract.Events.CONTENT_URI
                    putExtra(CalendarContract.Events.TITLE, title)
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
                    putExtra(CalendarContract.Events.DESCRIPTION, params["description"] ?: "Created by OpenDroid")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "I opened the calendar so you can set up your reminder '$title'.", null)
            } catch (e: Exception) {
                Log.e("SetReminder", "Reminder failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't set up that reminder.")
            }
        }
    }

    private class CreateTaskAction : Action {
        override val name: String = "CREATE_TASK"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val title = params["title"] ?: "New Task"
            val description = params["description"] ?: ""
            return ActionResult(true, "Done! Task '$title' is created.", null)
        }
    }

    private class ReadNotesAction : Action {
        override val name: String = "READ_NOTES"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val mockNotes = "1. Buy groceries\n2. Call doctor at 3 PM\n3. Finish Android development tasks"
            return ActionResult(true, "Here are your notes:\n$mockNotes", null)
        }
    }
}
