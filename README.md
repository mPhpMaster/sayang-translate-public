# Sayang Translate

An Android app for couples and friends who chat in different languages.

- **Notifications:** new WhatsApp and Telegram messages arrive translated into your two languages.
- **Any screen:** a small round button translates the text on screen in place, so you still see your own messages. Each tap switches between your language, your second language and Off.
- **Slang:** casual spelling, baby talk and stretched letters are understood, helped by a shared slang dictionary.

## Setup

1. Download `sayang-translate.apk` from the [latest release](../../releases/latest) and install it.
2. Pick your language and a second language.
3. Paste a free Gemini key from [Google AI Studio](https://aistudio.google.com/apikey). Without one, translation runs on the phone and is weaker.
4. Turn on notification access and the chat button when the app asks.

New versions install themselves from this repository's releases.

## Privacy

- Text you translate goes to Google's Gemini API with your own key. Google's terms for the free tier apply.
- Messages already in your languages are checked on the phone and never sent.
- Notifications are only read from WhatsApp and Telegram.
- The round button reads the screen only while it's switched on, and goes back to Off when you change apps.
- The app has no server and collects nothing.

## Slang dictionary

[`dictionary.json`](dictionary.json) holds shared slang, read by every copy of the app. Only the repository owner can change it. Repeated letters don't matter: `amicu` also matches `amicuuuuu`.

## Building

Needs JDK 17 and the Android SDK. Point `local.properties` at your SDK and, for signed builds, at a properties file with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`:

```
sdk.dir=/path/to/Android/Sdk
signing.file=/path/to/signing.properties
```

Then run `./gradlew assembleRelease`. `release.sh` builds and publishes a GitHub release.

## License

Apache License 2.0. See [LICENSE](LICENSE).
