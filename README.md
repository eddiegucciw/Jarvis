# J.A.R.V.I.S. for Android

A free voice assistant for Android.

- **Ears:** Android's built-in speech recognition
- **Brain:** Google Gemini API, free tier (swappable, see `Brain.kt`)
- **Voice:** Android's built-in text-to-speech (British voice if available)
- **Hands:** torch, battery, clipboard, vibrate, open apps, alarms, timers, web search, dial, SMS

## Get the APK
Every push to `main` builds the app automatically.
Open the **Releases** section of this repo and download the newest `Jarvis.apk`.
You can also find it under **Actions → latest run → Artifacts**.

## First run
1. Install the APK. Android will ask you to allow installs from your browser or file manager.
2. Open Jarvis and paste your free Gemini API key (from aistudio.google.com) in **Settings**.
3. Tap the ring and talk. Say "goodbye" to end the conversation.

Your API key is stored only on your phone. It is never put in this repo.

## Try saying
- "Turn on the torch"
- "How much battery do I have?"
- "Open WhatsApp"
- "Wake me up at 6:30"
- "Set a timer for 5 minutes"
- "Search for the weather in Nairobi"
- "Tell me a joke"
