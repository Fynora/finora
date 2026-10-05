import { Platform } from 'react-native';
import { requireOptionalNativeModule } from 'expo';

/**
 * Android only -- see android/.../LaunchSystemBarsModule.kt. Optional, so a build without the
 * native side (iOS, an older binary, the test runner) quietly does nothing instead of throwing.
 */
type LaunchSystemBarsNative = {
  enterDarkField(): Promise<void>;
  restore(): Promise<void>;
};

const native =
  Platform.OS === 'android' ? requireOptionalNativeModule<LaunchSystemBarsNative>('LaunchSystemBars') : null;

/** Light navigation buttons with no contrast scrim, for the launch animation's dark field. */
export function enterDarkField(): void {
  void native?.enterDarkField().catch(() => undefined);
}

/** Puts the navigation bar back as it was before enterDarkField. Safe to call more than once. */
export function restoreSystemBars(): void {
  void native?.restore().catch(() => undefined);
}
