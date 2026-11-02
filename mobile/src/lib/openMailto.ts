import { Linking } from 'react-native';
import * as Clipboard from 'expo-clipboard';
import { AppAlert } from './appAlert';
import { reportHandledEvent } from './monitoring';

/**
 * Opens a `mailto:` link without letting a refusal escape as an unhandled promise rejection.
 *
 * Linking.openURL() rejects when the device has nothing to hand a mailto link to -- no mail app, or
 * no mail account set up in it, which is common on an iPad. ConsentNotice's address link called it
 * as `void Linking.openURL(...)`, so that refusal reached Sentry as an Error with mechanism
 * onunhandledrejection (FYNORA-MOBILE-V: an iPad Air on iOS 27.0.1, build 1.2.0 (36)) and left the
 * person with a dead tap.
 *
 * Unlike openWebUrl there is no in-app browser to fall back to, so the person is told, and given
 * the address to copy into whatever they do use. A refusal is not a fault in the app, so it is
 * recorded as an info event (still counted, so the rate stays visible) rather than as an error.
 * `address` is passed in rather than parsed back out of the link so this file never repeats it.
 */
export function openMailto(mailtoUrl: string, address: string): void {
  Linking.openURL(mailtoUrl).catch(() => {
    reportHandledEvent('mailto rejected; showing the address instead', 'open-mailto-failed');
    AppAlert.alert('Could not open your email app', `You can write to ${address} from any email app.`, [
      { text: 'Copy Address', onPress: () => void Clipboard.setStringAsync(address) },
      { text: 'OK', style: 'cancel' },
    ]);
  });
}
