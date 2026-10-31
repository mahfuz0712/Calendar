# Calendar

Offline calendar for Android (Kotlin, Jetpack Compose, Room, Hilt, WorkManager, java.time). Package: `com.mux.calendar`

## Build
Push to GitHub -> Actions -> "Build APK" -> download artifact `Calendar-debug-apk`.

## Architecture
- `Data.kt`       Room entities (events, categories, recurrence rules, reminders), DAOs, database, Hilt module, repository
- `Domain.kt`     java.time helpers, RRULE parser and expander, occurrence engine with time zone handling
- `Reminders.kt`  AlarmManager alarms, notifications (snooze), WorkManager worker, boot/time zone receivers
- `State.kt`      settings store and the Hilt ViewModel
- `Views.kt`      month, week, day and agenda views
- `Editor.kt`     event details, event editor, time zone picker
- `Screens.kt`    search and settings, `MainActivity.kt` navigation

## Release (in-app updates)
1. Bump `appVersionName` in `app/build.gradle.kts` (and `version` in `metadata.json`).
2. Build, then create a GitHub Release tagged with the same number (e.g. `1.1.0`) and upload the APK.
3. Installed apps check `releases/latest` on open and offer the update.

