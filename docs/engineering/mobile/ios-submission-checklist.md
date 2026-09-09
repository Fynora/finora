# iOS submission checklist

Manual, one-time verification to run before the **first** real App Store submission, and again
after any submission where the native iOS config has changed meaningfully since the last pass
(a new Expo config plugin, a Podfile-affecting change, a new entitlement).

This exists because iOS currently has **zero automated native-build validation** — every CI
workflow in this repository runs on `ubuntu-latest`, and `xcodebuild`/`pod install` require a
macOS host. That's a deliberate, documented decision (see the CI-audit discussion this checklist
came out of): not worth macOS CI infrastructure before there's a real submission to protect, but
it does mean this manual pass is the *only* verification this pipeline gets. Skipping it isn't
skipping a redundant check — it's skipping the only one.

## 1. Native project generation

```bash
cd mobile
npx expo prebuild -p ios --clean
```

Watch for failures here specifically — this is where a broken `Info.plist`/entitlement merge or
a missing `GoogleService-Info.plist` shows up first.

## 2. CocoaPods resolution

```bash
cd ios
pod install
```

This is the step `plugins/withRNFirebaseDisableSPM.js` exists to keep working — it forces
CocoaPods resolution instead of Swift Package Manager, required for Firebase's static linking
(`expo-build-properties`'s `ios.useFrameworks: 'static'`). If this plugin's Podfile-marker
insertion has silently broken (it fails *silently*, unlike the equivalent Android plugin — see
its own header comment), this step fails with:

```
[!] [react-native-firebase] SPM + static linkage is not supported (target(s): Pods-Finora).
```

If you see that error, check the Podfile for the `$RNFirebaseDisableSPM = true` marker before
assuming it's a Firebase-side problem.

## 3. Clean Xcode build

Open `ios/Finora.xcworkspace` (not `.xcodeproj`) in Xcode, select a Release configuration, and
build clean (`Product > Clean Build Folder` first). Confirm zero errors, and read through
warnings — a native module version bump is a common source of new ones.

## 4. Simulator smoke test

Run on a simulator. At minimum:

- App launches and reaches the dashboard past authentication.
- No crash on the screens the Android Maestro flows already cover (login, dashboard, import).

## 5. Real device smoke test

Repeat on a physical device, not just the simulator. Face ID (app-lock), push notification
permission prompts, and camera/document-picker flows (statement import) can behave differently
on-device than in the simulator.

## 6. Feature-specific verification

- **Google Sign-In** — completes successfully end to end.
- **Sign In with Apple** — completes successfully end to end (this is the platform-native one,
  distinct from Google Sign-In).
- **Deep links** — the `finora://` scheme (or `finora-dev://` for a dev build) opens the app and
  routes correctly.
- **Push notifications** — a test notification is received and tapping it opens the app.

## 7. Archive and sign

Product → Archive in Xcode. Confirm the archive completes and the resulting build is signed with
the correct distribution certificate/provisioning profile for the target (TestFlight/App Store),
not a development one.

## 8. TestFlight upload

Upload the archive via Xcode Organizer or `eas submit`. Confirm it appears in App Store Connect
and processes without a rejection at the automated-checks stage before treating this pass as
complete.

## 9. Re-run before every subsequent submission with native changes

This checklist isn't a one-time gate — repeat it whenever a change could plausibly affect the
native iOS build (a new native dependency, a Podfile-touching plugin, a new entitlement, an Expo
SDK bump). A green checklist from three submissions ago says nothing about today's build.
