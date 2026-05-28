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

## Release signing — production key

The production signing key lives at `signing/echo-air-release.jks` (gitignored, plus the whole `signing/` directory is in `.gitignore`). The credentials live in `keystore.properties` at the repo root (also gitignored). Both files MUST exist for the release build to produce a signed artefact; without them, the release type still configures cleanly but the resulting APK/AAB is unsigned.

`keystore.properties` format:

```
storeFile=signing/echo-air-release.jks
storePassword=...
keyAlias=echoair-release
keyPassword=...
```

The same key is used for both distribution channels (sideload APK + Play Store AAB) so a user can install or update across channels without an uninstall step. Don't paste passwords here, don't commit the keystore.

Cert metadata (safe to publish):

- Alias: `echoair-release`
- Owner: `CN=Echo Air by Suply, O=Suply AI, C=US`
- Algorithm: SHA384withRSA, 2048-bit
- Validity: ~25 years (until May 2051)

## Build the signed release APK (sideload / China direct download)

```
./gradlew :app:assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk` (≈16 MB, signed with the production key via APK Signature Scheme v2). This is the artefact to host at suply.ai for direct download in regions without Play Store access.

Verify the signature before publishing:

```
$ANDROID_HOME/build-tools/<version>/apksigner verify --print-certs \
    app/build/outputs/apk/release/app-release.apk
```

Expect `Verifies` and `Signer #1 certificate DN: CN=Echo Air by Suply, O=Suply AI, C=US`.

## Build the signed release AAB (Play Store)

```
./gradlew :app:bundleRelease
```

Output: `app/build/outputs/bundle/release/app-release.aab` — upload to Play Console. Same signing key as the sideload APK, so installs survive channel migration both directions.

## Update-without-uninstall (sideload upgrades)

Side-loaded users won't get Play Store auto-updates. The in-app `UpdateAvailableGate` polls `https://api.suply.ai/echo/android-latest-version` once per cold launch and surfaces a dismissible "Update available" dialog when the remote `versionCode` exceeds the running `BuildConfig.VERSION_CODE`.

To verify a sideload upgrade works in place (no uninstall required), the new APK must satisfy three conditions — all guaranteed by the standard build pipeline:

1. Same `applicationId` (`app.suply.echoair`).
2. Same signing certificate (the production key above).
3. `versionCode` strictly greater than the installed version.

Smoke test on a connected device after bumping `versionCode`:

```
adb install -r app/build/outputs/apk/release/app-release.apk
```

`-r` is replace-existing-install; Android's package manager accepts the upgrade silently because the signing cert matches.

## Known constraints on the current build

- **kbeaconlib2 is vendored** at `libs/kbeaconlib2/` rather than pulled from JitPack; JitPack was returning 503 when the app was last built. Once JitPack recovers and the library gets a release tag, the dependency in `app/build.gradle.kts` can flip back to `implementation("com.github.kkmhogen:android_kbeaconlib2:<tag>")`.
- **Debug build is unminified.** Debug APK is ≈80 MB per ABI. Release builds will be much smaller once R8 keep-rules are exercised end-to-end against a real device.
