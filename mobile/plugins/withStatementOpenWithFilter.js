const { withAndroidManifest } = require('expo/config-plugins');

// Keeps the statement "Open with" filter (app.config.ts, android.intentFilters: ACTION_VIEW on
// content:// for PDF/CSV) to the content scheme alone.
//
// expo-dev-client adds its launch scheme (exp+<slug>) to every ACTION_VIEW filter on MainActivity,
// and only takes it back off the autoVerify ones. Inside one intent filter Android pairs every
// scheme with every mime type, so the file filter would also claim "exp+finora-mobile:" for PDF/CSV.
// Seen in the merged release manifest on 2026-11-02 before this plugin existed. The dev-client
// scheme still lives on the finora:// filter, which is where it is needed.
//
// Listed FIRST in app.config.ts's plugins: manifest mods run in reverse order of registration, so
// this runs after the dev-client scheme has been added. Checked against a fresh prebuild.
//
// Plain JavaScript for the reason withRNFirebaseDisableSPM.js gives (EAS resolves plugins without a
// TypeScript loader).

/** @param {any} filter */
function isContentFileFilter(filter) {
  return (filter.data ?? []).some((d) => d.$?.['android:scheme'] === 'content' && d.$?.['android:mimeType']);
}

/** @param {any} application */
function keepOnlyContentSchemeOnFileFilters(application) {
  for (const activity of application.activity ?? []) {
    for (const filter of activity['intent-filter'] ?? []) {
      if (!isContentFileFilter(filter)) continue;
      filter.data = filter.data.filter((d) => !d.$?.['android:scheme'] || d.$['android:scheme'] === 'content');
    }
  }
  return application;
}

/** @type {import('expo/config-plugins').ConfigPlugin} */
const withStatementOpenWithFilter = (config) =>
  withAndroidManifest(config, (manifestConfig) => {
    for (const application of manifestConfig.modResults.manifest.application ?? []) {
      keepOnlyContentSchemeOnFileFilters(application);
    }
    return manifestConfig;
  });

module.exports = withStatementOpenWithFilter;
module.exports.keepOnlyContentSchemeOnFileFilters = keepOnlyContentSchemeOnFileFilters;
