const { describe, expect, it } = require('@jest/globals');
const { keepOnlyContentSchemeOnFileFilters } = require('./withStatementOpenWithFilter');

const view = [{ $: { 'android:name': 'android.intent.action.VIEW' } }];
const devScheme = { $: { 'android:scheme': 'exp+finora-mobile' } };

const fileData = [
  { $: { 'android:scheme': 'content', 'android:mimeType': 'application/pdf' } },
  { $: { 'android:scheme': 'content', 'android:mimeType': 'text/csv' } },
];

describe('keepOnlyContentSchemeOnFileFilters', () => {
  it('drops the dev-client scheme from the "Open with" file filter', () => {
    const fileFilter = { action: view, data: [...fileData, devScheme] };
    const application = { activity: [{ $: { 'android:name': '.MainActivity' }, 'intent-filter': [fileFilter] }] };

    keepOnlyContentSchemeOnFileFilters(application);

    expect(fileFilter.data).toEqual(fileData);
  });

  it('leaves the finora:// filter, where the dev-client scheme belongs, untouched', () => {
    const schemeFilter = { action: view, data: [{ $: { 'android:scheme': 'finora' } }, devScheme] };
    const before = JSON.parse(JSON.stringify(schemeFilter));
    const application = { activity: [{ 'intent-filter': [schemeFilter] }] };

    keepOnlyContentSchemeOnFileFilters(application);

    expect(schemeFilter).toEqual(before);
  });

  it('leaves a share filter with mime types but no scheme untouched', () => {
    const share = {
      action: [{ $: { 'android:name': 'android.intent.action.SEND' } }],
      data: [{ $: { 'android:mimeType': 'application/pdf' } }],
    };
    const before = JSON.parse(JSON.stringify(share));
    const application = { activity: [{ 'intent-filter': [share] }] };

    keepOnlyContentSchemeOnFileFilters(application);

    expect(share).toEqual(before);
  });

  it('copes with an activity that has no intent filters', () => {
    expect(() => keepOnlyContentSchemeOnFileFilters({ activity: [{}] })).not.toThrow();
    expect(() => keepOnlyContentSchemeOnFileFilters({})).not.toThrow();
  });
});
