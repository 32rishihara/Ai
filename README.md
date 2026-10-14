# GuardAI

Android app (Java, no third-party libraries) with:

- **Chat / coding** - talks to Claude through the Anthropic API using your own key.
- **Website checker** - loads a link, extracts phishing signals (look-alike domains, cross-domain forms, raw IPs, shorteners...), asks the AI for a verdict. Works from the Share menu in your browser.
- **Call screening** - becomes your phone's call-screening app. Every incoming call is scored on-device; optional auto-reject for high scores; notifications for suspicious calls.
- **Scam check for calls/SMS** - paste text, or use speakerphone + "Start listening" for live speech-to-text that is re-analyzed every ~30 s.
- **Call-log check** - AI looks for spam / callback-fraud patterns (numbers are masked before sending).

## Get the APK (no Android Studio needed)

1. Create a new GitHub repository and upload this whole folder (including the hidden `.github` folder).
2. Open the repo's **Actions** tab, run **Build APK** (it also runs on every push).
3. When it finishes, open the run and download the **GuardAI-apk** artifact (a zip containing `app-debug.apk`).
4. Copy the APK to your phone, open it, allow "install unknown apps" when asked.

## Or build locally

Install Android Studio, open this folder, press Run - or `gradle assembleDebug` with JDK 17 and the Android SDK (API 34).

## First run

1. Settings -> paste your Anthropic API key (console.anthropic.com) -> Save.
2. Calls -> "Turn on call screening" -> pick GuardAI. Then "Grant permissions".
3. Leave auto-reject OFF until you've watched the scores for a few days.

## Limits

- Android does not let third-party apps record or hear call audio. Live analysis therefore uses the microphone while the call is on speakerphone.
- Offline scores are heuristics and can be wrong; keep the reject threshold high.
- The API key is stored in the app's private storage (not hardware-encrypted).
