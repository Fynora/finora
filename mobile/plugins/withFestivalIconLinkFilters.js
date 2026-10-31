const { withAndroidManifest } = require('expo/config-plugins');

// Festival app icon (expo-alternate-app-icons) follow-up for Android links.
//
// The icon plugin adds one <activity-alias> per icon (MainActivityDefault, MainActivityDiwali, ...)
// and copies every android.intentFilters entry from app.config.ts onto each alias, while
// MainActivity keeps the same filters. So an app.fynora.net link matched two components of this one
// app, and Android answered a tapped email link with an "Open with: Fynora / Fynora" picker instead
// of opening the app. Seen on the emulator (Android 17, 2026-10-31) before this plugin existed.
//
// MainActivity is never disabled by the icon library (11.x keeps it enabled and moves only the
// launcher entry onto the aliases), so it can own the links alone. This strips every intent filter
// from the aliases except the launcher one (MAIN + LAUNCHER), which is what switching the icon needs.
//
// Must be listed BEFORE 'expo-alternate-app-icons' in app.config.ts: manifest mods run in reverse
// order of the plugins list, so listing it first makes it run after the aliases exist.
//
// Plain JavaScript for the reason withRNFirebaseDisableSPM.js gives (EAS resolves plugins without a
// TypeScript loader).

/** @param {any} filter */
function isLauncherFilter(filter) {
  const actions = (filter.action ?? []).map((a) => a.$?.['android:name']);
  const categories = (filter.category ?? []).map((c) => c.$?.['android:name']);
  return actions.includes('android.intent.action.MAIN') && categories.includes('android.intent.category.LAUNCHER');
}

/** @param {any} application */
function keepOnlyLauncherFiltersOnAliases(application) {
  for (const alias of application['activity-alias'] ?? []) {
    if (alias.$?.['android:targetActivity'] !== '.MainActivity') continue;
    alias['intent-filter'] = (alias['intent-filter'] ?? []).filter(isLauncherFilter);
  }
  return application;
}

/** @type {import('expo/config-plugins').ConfigPlugin} */
const withFestivalIconLinkFilters = (config) =>
  withAndroidManifest(config, (manifestConfig) => {
    for (const application of manifestConfig.modResults.manifest.application ?? []) {
      keepOnlyLauncherFiltersOnAliases(application);
    }
    return manifestConfig;
  });

module.exports = withFestivalIconLinkFilters;
module.exports.keepOnlyLauncherFiltersOnAliases = keepOnlyLauncherFiltersOnAliases;
