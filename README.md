# sus (shield-under-scanner)

Android TV app for NVIDIA Shield: live underscan slider, saved profiles, and a headless intent API for other tools.
Needs Shizuku running (wireless debugging start).

Intents (VIEW):
- `sus://profile/<name>`
- `sus://set/<tenths>` (25 = 2.5%)
- `sus://next`

Example: `adb shell am start -a android.intent.action.VIEW -d "sus://profile/Gaming"`

Build: GitHub Actions workflow produces `app-debug.apk` as an artifact.
