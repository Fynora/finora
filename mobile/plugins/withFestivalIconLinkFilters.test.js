const { describe, expect, it } = require('@jest/globals');
const { keepOnlyLauncherFiltersOnAliases } = require('./withFestivalIconLinkFilters');

const launcher = {
  action: [{ $: { 'android:name': 'android.intent.action.MAIN' } }],
  category: [{ $: { 'android:name': 'android.intent.category.LAUNCHER' } }],
};
const appLink = {
  $: { 'android:autoVerify': 'true' },
  action: [{ $: { 'android:name': 'android.intent.action.VIEW' } }],
  data: [{ $: { 'android:scheme': 'https', 'android:host': 'app.fynora.net', 'android:path': '/app/settings' } }],
  category: [
    { $: { 'android:name': 'android.intent.category.BROWSABLE' } },
    { $: { 'android:name': 'android.intent.category.DEFAULT' } },
  ],
};

function alias(name, target = '.MainActivity') {
  return { $: { 'android:name': name, 'android:targetActivity': target }, 'intent-filter': [launcher, appLink] };
}

describe('keepOnlyLauncherFiltersOnAliases', () => {
  it('leaves only the launcher filter on every icon alias, so links resolve to MainActivity alone', () => {
    const application = {
      activity: [{ $: { 'android:name': '.MainActivity' }, 'intent-filter': [appLink] }],
      'activity-alias': [alias('.MainActivityDefault'), alias('.MainActivityDiwali')],
    };

    keepOnlyLauncherFiltersOnAliases(application);

    for (const a of application['activity-alias']) {
      expect(a['intent-filter']).toEqual([launcher]);
    }
    // MainActivity keeps its link filter untouched.
    expect(application.activity[0]['intent-filter']).toEqual([appLink]);
  });

  it('does not touch an alias that targets another activity', () => {
    const other = alias('.ShareTarget', '.ShareActivity');
    const application = { 'activity-alias': [other] };

    keepOnlyLauncherFiltersOnAliases(application);

    expect(other['intent-filter']).toEqual([launcher, appLink]);
  });

  it('copes with an application that has no aliases', () => {
    expect(() => keepOnlyLauncherFiltersOnAliases({})).not.toThrow();
  });
});
