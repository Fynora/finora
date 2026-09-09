# Android release checklist

Manual verification to run before any real Android release upload — a local `gradlew
assembleRelease` build, or a build pulled from EAS. Not enforced by CI: see
`mobile-setup.md`'s "Signing fingerprints" section for why release-build coverage in CI is
deliberately narrow (the nightly Maestro job proves `expo prebuild` + `gradlew assembleRelease`
succeed in general, but never exercises the actual keystore-substitution path, since CI
correctly has no access to `release-signing.properties`).

## 1. Know which keystore you're using

This project currently has **two independent Android signing identities** that produce
different, incompatible APKs — see `mobile-setup.md`'s "Signing fingerprints" section:

- The **EAS-managed keystore** (`eas build` / `eas build --local`).
- The **local upload keystore** (`mobile/release-upload.jks`, alias `fynora-upload`, used by a
  bare `./gradlew assembleRelease` via `plugins/withAndroidReleaseSigning.js`).

Before building, be certain which one you intend to use for this release, and confirm it's the
same one used for every prior real Play upload. Mixing them for an app that's already live on
Play breaks Play's upload-key check — Play locks in whichever key first uploaded successfully.

## 2. Build

```bash
cd mobile
npx expo prebuild -p android --clean
cd android
./gradlew assembleRelease
```

Output: `mobile/android/app/build/outputs/apk/release/app-release.apk`

## 3. Verify the signing certificate

```bash
# Find apksigner under your Android SDK's build-tools if it's not on PATH:
#   $ANDROID_HOME/build-tools/<version>/apksigner
apksigner verify --print-certs mobile/android/app/build/outputs/apk/release/app-release.apk
```

Confirm the printed SHA-1 and SHA-256 match what's registered in Firebase Console → Project
Settings → the `com.fynora.android` Android app → SHA certificate fingerprints, for whichever
keystore you built with (see step 1). A mismatch here is exactly what caused "Sign in with
Google is unavailable right now" the last time this wasn't checked before a release build.

## 4. Verify Google Sign-In on the actual built APK

Install the APK on a real device or emulator and confirm Google Sign-In completes successfully.
A signing-certificate mismatch with Firebase fails at this exact step, not at build time — the
build always succeeds even when the cert is wrong.

## 5. Verify package name and version

```bash
cd mobile
npx expo config --type public --json | python3 -c "import json,sys; d=json.load(sys.stdin); print('package:', d['android']['package'])"
```

Confirm it's `com.fynora.android` for a production release (not `com.fynora.android.dev`).

```bash
grep -n "versionCode\|versionName" mobile/android/app/build.gradle
```

Confirm both were bumped from the last real release — Play rejects a re-upload with an
unchanged or lower `versionCode`.

## 6. Then, and only then, upload

Play Console → the app → Production (or the appropriate track) → Create new release → upload
the verified APK/AAB.
