const fs = require('fs');
const path = require('path');
const { withAppBuildGradle } = require('expo/config-plugins');

// `expo prebuild` regenerates android/app/build.gradle from scratch every time (the whole
// android/ directory is gitignored -- see mobile/.gitignore), so it always ships with the
// template's default: release builds signed with the debug keystore. That is fine for sideloaded
// testing but not for a real Play Store upload. This plugin re-points the release build type at a
// real upload keystore on every prebuild, so the fix doesn't have to be re-applied by hand like
// android/local.properties and android/gradle.properties currently are.
//
// The keystore (release-upload.jks) and its passwords (release-signing.properties) live in
// mobile/, NOT in android/, specifically so they survive `expo prebuild --clean` wiping that
// directory. Both are gitignored -- see mobile/.gitignore's "Local release-signing keystore
// config" entry -- so every developer/machine building a real release generates or is handed
// their own.
//
// Absent on a machine that doesn't have the keystore (a fresh checkout, CI without secrets
// injected): falls back to the debug keystore exactly as the unmodified template does, rather
// than failing the build. Same "unconfigured is a supported state" posture as
// EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID elsewhere in this app -- see GoogleSignInButton.tsx.

const DEBUG_SIGNING_CONFIG = `signingConfigs {
        debug {
            storeFile file('debug.keystore')
            storePassword 'android'
            keyAlias 'androiddebugkey'
            keyPassword 'android'
        }
    }`;

const RELEASE_BUILD_TYPE_MARKER = `release {
            // Caution! In production, you need to generate your own keystore file.
            // see https://reactnative.dev/docs/signed-apk-android.
            signingConfig signingConfigs.debug`;

/** @type {import('expo/config-plugins').ConfigPlugin} */
const withAndroidReleaseSigning = (config) =>
  withAppBuildGradle(config, (buildGradleConfig) => {
    const propsPath = path.join(__dirname, '..', 'release-signing.properties');
    if (!fs.existsSync(propsPath)) {
      return buildGradleConfig;
    }

    let contents = buildGradleConfig.modResults.contents;

    // Built as a whole literal, not via a substring .replace() on DEBUG_SIGNING_CONFIG -- a
    // narrow anchor like '    }' matches ambiguously (it's also a suffix of the more-indented
    // '        }' closing `debug {}`), which previously inserted `release {}` nested inside
    // `debug {}` instead of as its sibling. Spelling out the full target avoids that class of bug.
    const withReleaseSigningConfig = `signingConfigs {
        debug {
            storeFile file('debug.keystore')
            storePassword 'android'
            keyAlias 'androiddebugkey'
            keyPassword 'android'
        }
        release {
            // Loaded from release-signing.properties (gitignored, mobile/ root) at prebuild
            // time -- see withAndroidReleaseSigning.js for why it lives outside android/.
            def releaseSigningProps = new Properties()
            releaseSigningProps.load(new FileInputStream(file('${propsPath}')))
            storeFile file(releaseSigningProps['RELEASE_STORE_FILE'])
            storePassword releaseSigningProps['RELEASE_STORE_PASSWORD']
            keyAlias releaseSigningProps['RELEASE_KEY_ALIAS']
            keyPassword releaseSigningProps['RELEASE_KEY_PASSWORD']
        }
    }`;

    if (!contents.includes(DEBUG_SIGNING_CONFIG)) {
      throw new Error(
        'withAndroidReleaseSigning: android/app/build.gradle did not match the expected ' +
          'default signingConfigs block -- the Expo/RN template likely changed. Update the ' +
          'DEBUG_SIGNING_CONFIG anchor in plugins/withAndroidReleaseSigning.js.'
      );
    }
    contents = contents.replace(DEBUG_SIGNING_CONFIG, withReleaseSigningConfig);

    if (!contents.includes(RELEASE_BUILD_TYPE_MARKER)) {
      throw new Error(
        'withAndroidReleaseSigning: android/app/build.gradle did not match the expected ' +
          'release buildType block -- update the RELEASE_BUILD_TYPE_MARKER anchor.'
      );
    }
    contents = contents.replace(
      RELEASE_BUILD_TYPE_MARKER,
      RELEASE_BUILD_TYPE_MARKER.replace('signingConfigs.debug', 'signingConfigs.release')
    );

    buildGradleConfig.modResults.contents = contents;
    return buildGradleConfig;
  });

module.exports = withAndroidReleaseSigning;
