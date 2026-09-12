import { light, dark } from '../theme/palette';
import { healthBarColor, healthColor, healthImprovementSuggestion, healthToneBg, scoreLabel } from './health';

describe('healthColor', () => {
  it('maps every label to its token, in both themes', () => {
    expect(healthColor('Excellent', light)).toBe(light.success);
    expect(healthColor('Good', light)).toBe(light.primary);
    expect(healthColor('Fair', light)).toBe(light.warningInk);
    expect(healthColor('Needs Attention', light)).toBe(light.danger);
    expect(healthColor('Excellent', dark)).toBe(dark.success);
  });
});

describe('healthBarColor', () => {
  it('uses the 80/60/40 cutoffs', () => {
    expect(healthBarColor(80, light)).toBe(light.success);
    expect(healthBarColor(79, light)).toBe(light.primary);
    expect(healthBarColor(60, light)).toBe(light.primary);
    expect(healthBarColor(59, light)).toBe(light.warning);
    expect(healthBarColor(40, light)).toBe(light.warning);
    expect(healthBarColor(39, light)).toBe(light.danger);
  });
});

describe('healthToneBg', () => {
  it('pairs each cutoff with its background token', () => {
    expect(healthToneBg(85, light)).toBe(light.successBg);
    expect(healthToneBg(70, light)).toBe(light.primaryLight);
    expect(healthToneBg(50, light)).toBe(light.warningBg);
    expect(healthToneBg(20, light)).toBe(light.dangerBg);
  });
});

describe('scoreLabel', () => {
  it('uses the same cutoffs as healthBarColor', () => {
    expect(scoreLabel(80)).toBe('Excellent');
    expect(scoreLabel(60)).toBe('Good');
    expect(scoreLabel(40)).toBe('Fair');
    expect(scoreLabel(0)).toBe('Needs Attention');
  });
});

describe('healthImprovementSuggestion', () => {
  it('gives a positive suggestion at or above 80, actionable below it', () => {
    expect(healthImprovementSuggestion('Savings Rate', 85)).toBe("You're saving well — keep it up.");
    expect(healthImprovementSuggestion('Savings Rate', 50)).toBe('Aim to save at least 24% of your income each month.');
    expect(healthImprovementSuggestion('Emergency Fund', 30)).toBe('Build your emergency fund toward 4-5 months of expenses.');
  });

  it('returns an empty string for an unrecognized factor name', () => {
    expect(healthImprovementSuggestion('Some New Factor', 50)).toBe('');
  });
});
