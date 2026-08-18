# Building on Android / GitHub

The canonical source is this GitHub repository. GitHub Actions builds the debug APK in the cloud, so an Android phone can drive development without a local desktop Android SDK.

Workflow:
1. Edit/push from ChatGPT/Codex, Codespaces, AI Studio, or an Android Git client/IDE.
2. GitHub Actions generates the official Gradle wrapper, runs unit tests, and assembles the debug APK.
3. Download the `ntpx-truthcore-debug-apk` workflow artifact to the Android device and install it for testing.
