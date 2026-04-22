# Building Echo Air

## One-time setup

1. Install JDK 17+ (Android Studio bundles one, or `brew install openjdk@21`).
2. Install Android SDK — either via Android Studio (SDK Manager → Android 15, Build-Tools 35.0.0, Platform-Tools) or the command-line tools:
   ```
   curl -sSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o /tmp/cmdline.zip
   mkdir -p $HOME/android-sdk/cmdline-tools
   unzip /tmp/cmdline.zip -d $HOME/android-sdk/cmdline-tools
   mv $HOME/android-sdk/cmdline-tools/cmdline-tools $HOME/android-sdk/cmdline-tools/latest
   export ANDROID_HOME=$HOME/android-sdk
   export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH
   yes | sdkmanager --licenses >/dev/null
   sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
   ```
3. From the repo root, create `local.properties` (gitignored):
   ```
   sdk.dir=/absolute/path/to/your/android-sdk
   ```

## Build the debug APK

```
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` (≈80 MB, signed with the standard Android Debug keystore).

Or, skipping the wrapper if you have a system Gradle 8.9+:
```
gradle :app:assembleDebug --no-configuration-cache
```

## Install on a connected phone

```
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

Or pick the matching APK for your test device's ABI (`armeabi-v7a` for older 32-bit phones).

## Debug-only spike harness

The kbeaconlib2 symbol spike is in the debug build. Install, then open the app → **Settings → Run kbeaconlib2 spike (debug)**, or:

```
adb shell am start -n app.suply.echoair.debug/app.suply.echoair.spike.SpikeActivity
```

Save the output to [`docs/spike-output/`](./spike-output/README.md) when you have a clean run against real hardware.

## Known constraints on the current build

- **kbeaconlib2 is vendored** at `libs/kbeaconlib2/` rather than pulled from JitPack; JitPack was returning 503 when the app was last built. Once JitPack recovers and the library gets a release tag, the dependency in `app/build.gradle.kts` can flip back to `implementation("com.github.kkmhogen:android_kbeaconlib2:<tag>")`.
- **Debug build is unminified.** Debug APK is ≈80 MB per ABI. Release builds will be much smaller once R8 keep-rules are exercised end-to-end against a real device.
- **Google Play Store requires an AAB with a proper release keystore.** For internal testing tracks, sign with a real keystore (not the debug keystore) and build `:app:bundleRelease` instead.
