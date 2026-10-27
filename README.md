# sus (shield-under-scanner)

Android TV app for NVIDIA Shield: live overscan slider, saved profiles, and a headless intent API for other tools.
Needs Shizuku running (wireless debugging start).

Intents (VIEW):
- `overscan://profile/<name>`
- `overscan://set/<tenths>` (25 = 2.5%)
- `overscan://next`

Example: `adb shell am start -a android.intent.action.VIEW -d "overscan://profile/Gaming"`

Build: GitHub Actions workflow produces `app-debug.apk` as an artifact.
