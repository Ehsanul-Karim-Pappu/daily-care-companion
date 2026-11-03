# Changelog

## Unreleased

### Added
- First version of the Android app (Android 10+, with setup guidance for Vivo and Xiaomi/Poco background limits):
  - Recognises home, work and the therapy place from Wi-Fi and location, without treating a Wi-Fi drop (load-shedding) as leaving.
  - Walking-break reminders while at work. Any walk with the phone that is long enough counts, prompted or not; brief phone carries don't.
  - Therapy visits inferred as "likely" (or "possible" when uncertain). A morning visit (session starting by 06:30) stops the evening reminder. An optional yes/no follows each visit.
  - Exercise rounds confirmed with one tap (notification, Today screen or quick-settings tile).
  - Medicine reminders from 1+0+1 style patterns. A dose only counts when you tap Taken. Reminders stop at the end of an entered course, and the app asks whether it was extended.
  - Today and History screens, where every entry is labelled "You confirmed", "You reported", "Phone inferred" or "App". Gaps in monitoring are shown as such. The full log can be exported as CSV.
  - Setup screen with permissions, phone-specific background settings, place saving, and a home-vs-therapy separation check.
  - Every threshold, time window, routine and medicine is configurable. An "observe week" mode (on by default) logs without sending break or therapy reminders.
- CI builds a debug APK and runs the core logic tests on every push.
