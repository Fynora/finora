import { getAuth, signInWithPhoneNumber, signOut } from '@react-native-firebase/auth';
import { reportHandledEvent } from './monitoring';

// @react-native-firebase/auth doesn't re-export ConfirmationResult from its package root (it
// lives in an internal ./types/auth module with no public subpath export), so this derives the
// type from signInWithPhoneNumber's own return type instead of reaching into node_modules
// internals -- it stays correct automatically if the package's shape changes.
export type PhoneConfirmation = Awaited<ReturnType<typeof signInWithPhoneNumber>>;

/**
 * Native counterpart to the web app's frontend/src/lib/phoneAuth.ts. Same two-function contract,
 * same "Firebase is transactional, Finora's own JWT is the real session" model: the ID token is
 * extracted and Firebase is signed out immediately, so nothing lingers in Firebase's client-side
 * auth state.
 *
 * The one real difference from web: no reCAPTCHA. The web SDK requires an invisible-reCAPTCHA
 * verifier anchored to a DOM element (hence that file's getRecaptchaVerifier/resetPhoneVerification
 * lifecycle and VerifyPhone.tsx's <div id=...>); @react-native-firebase/auth performs app
 * verification natively instead -- silent APNs push on iOS, Play Integrity on Android -- so there
 * is no verifier to create, pass, or tear down. That also means no resetPhoneVerification()
 * equivalent is needed here.
 *
 * Note this uses the modular API (getAuth()/signInWithPhoneNumber(auth, ...)), not the deprecated
 * namespaced auth().signInWithPhoneNumber() form.
 */

/** Not a code Firebase ever produces -- ours, in the same `auth/` namespace only because
 *  toUserMessage() looks up Firebase codes by that prefix. */
export const PHONE_SEND_TIMEOUT_CODE = 'auth/phone-send-timeout';

/** A healthy send resolves in a few seconds. A tester's Android phone sat on "Sending…" for over
 *  two minutes with no error and nothing in Sentry: the native call has no timeout of its own, so
 *  the spinner (and the disabled Resend button) waited forever.
 *
 *  Not shorter, because this promise also stays pending while a person completes Firebase's
 *  reCAPTCHA fallback in a browser (Firebase's Android docs: used when Play Integrity cannot be,
 *  e.g. no Play services or an app not installed from Play). Solving a challenge and switching
 *  back can take a while, and timing out mid-challenge would report a failure for a check that is
 *  still working. Chosen as a bound, not measured -- Sentry will now show real timings. */
export const PHONE_SEND_TIMEOUT_MS = 90_000;

type SendRecord = {
  startedAt: number;
  /** When our own promise settled (answer, Firebase error, or our timeout). */
  endedAt: number | null;
  outcome: 'pending' | 'answered' | 'failed' | 'timed-out';
  /** Set only when Firebase's native call answers AFTER we already gave up on it. */
  lateAnswerAt: number | null;
  /** Whether Firebase already held a signed-in user the moment the send answered. A user there,
   *  before the person typed anything, would mean the code was verified automatically -- or that an
   *  earlier confirm's signOut failed (it is swallowed below), so read it alongside the counts. */
  userPresentOnAnswer: boolean | null;
  confirmAttempts: number;
  /** The number this send went to. Held in memory only, never put in diagnostics or reports. */
  phoneNumber: string;
  /** True when Firebase held no signed-in user when this send began (or we signed it out first).
   *  Only then can a user that appears later be attributed to THIS send's automatic verification. */
  startedWithNoFirebaseUser: boolean;
  /** Set when a confirm failed but Firebase had already signed the person in by reading the SMS. */
  recoveredViaAutoVerification: boolean;
};

// Module state, so it spans every screen that sends a code and resets with the app process. Holds
// numbers and booleans only: never a phone number, a code, or an error message.
let sendsThisSession = 0;
let nativeSendsOutstanding = 0;
let lastSend: SendRecord | null = null;

function firebaseUserPresent(): boolean | null {
  try {
    return getAuth().currentUser != null;
  } catch {
    return null;
  }
}

function secondsBetween(fromMs: number, toMs: number): number {
  return Math.round((toMs - fromMs) / 100) / 10;
}

/** A snapshot of how the phone-verification sends and confirms have gone since the app started,
 *  for attaching to a Sentry report. Added because a tester's confirm failed with
 *  auth/session-expired ("the sms code has expired") and nothing recorded how long the send took,
 *  how long the person then waited, or whether they had resent -- so the failure could not be
 *  explained from Sentry alone. Only numbers, booleans and fixed labels: nothing here can carry
 *  the phone number or the code. */
export function phoneAuthDiagnostics(): Record<string, string | number | boolean | null> {
  const now = Date.now();
  const send = lastSend;
  return {
    sendsThisSession,
    nativeSendsOutstanding,
    lastSendOutcome: send ? send.outcome : 'none',
    // How long the last send took, or how long it has been running if it never finished.
    lastSendSeconds: send ? secondsBetween(send.startedAt, send.endedAt ?? now) : null,
    secondsSinceLastSendEnded: send?.endedAt != null ? secondsBetween(send.endedAt, now) : null,
    // Set only for a send that timed out and was answered later: how long Firebase really took.
    lastSendLateAnswerSeconds: send?.lateAnswerAt != null
      ? secondsBetween(send.startedAt, send.lateAnswerAt)
      : null,
    confirmAttemptsSinceLastSend: send ? send.confirmAttempts : 0,
    firebaseUserPresentNow: firebaseUserPresent(),
    firebaseUserPresentWhenSendAnswered: send ? send.userPresentOnAnswer : null,
    startedWithNoFirebaseUser: send ? send.startedWithNoFirebaseUser : null,
    recoveredViaAutoVerification: send ? send.recoveredViaAutoVerification : false,
  };
}

/** Signs out any Firebase user left over from before this send, so that a user present LATER can
 *  only have come from this send's own automatic verification. Firebase is transactional here (the
 *  ID token is taken and Firebase signed out at once), so a lingering user is never wanted.
 *  Returns false if a lingering user could not be cleared, in which case no later user may be
 *  trusted as auto-verified. */
async function clearLeftoverFirebaseUser(): Promise<boolean> {
  try {
    const auth = getAuth();
    if (auth.currentUser == null) return true;
    await signOut(auth);
    return true;
  } catch {
    return false;
  }
}

/** Sends a verification code to phoneNumber (must be E.164, e.g. "+919876543210"). Returns
 *  Firebase's confirmation handle -- hold onto it and pass it to confirmPhoneVerificationCode()
 *  once the user types the code back in.
 *
 *  Rejects with PHONE_SEND_TIMEOUT_CODE if Firebase has not answered within timeoutMs. The native
 *  call cannot be cancelled, so it may still finish afterwards and text a code the user was told
 *  did not send; its result is dropped, and a retry issues a fresh send. */
export function sendPhoneVerificationCode(
  phoneNumber: string,
  timeoutMs: number = PHONE_SEND_TIMEOUT_MS
): Promise<PhoneConfirmation> {
  return new Promise<PhoneConfirmation>((resolve, reject) => {
    const record: SendRecord = {
      startedAt: Date.now(),
      endedAt: null,
      outcome: 'pending',
      lateAnswerAt: null,
      userPresentOnAnswer: null,
      confirmAttempts: 0,
      phoneNumber,
      startedWithNoFirebaseUser: false,
      recoveredViaAutoVerification: false,
    };
    sendsThisSession += 1;
    nativeSendsOutstanding += 1;
    lastSend = record;

    const timer = setTimeout(() => {
      record.outcome = 'timed-out';
      record.endedAt = Date.now();
      reject(Object.assign(
        new Error(`Firebase did not answer the phone verification send within ${timeoutMs}ms.`),
        { code: PHONE_SEND_TIMEOUT_CODE }
      ));
    }, timeoutMs);
    // Async, so a synchronous throw from getAuth() or the native module becomes an ordinary
    // rejection handled below (timer cleared, counters settled) instead of escaping this executor.
    // With no leftover Firebase user there is no await before the native call, so it still starts
    // synchronously, exactly as before.
    const native: Promise<PhoneConfirmation> = (async () => {
      record.startedWithNoFirebaseUser = await clearLeftoverFirebaseUser();
      return signInWithPhoneNumber(getAuth(), phoneNumber);
    })();
    native.then(
      (confirmation) => {
        clearTimeout(timer);
        nativeSendsOutstanding -= 1;
        record.userPresentOnAnswer = firebaseUserPresent();
        if (record.outcome === 'pending') {
          record.outcome = 'answered';
          record.endedAt = Date.now();
        } else {
          record.lateAnswerAt = Date.now();
        }
        resolve(confirmation);
      },
      (err) => {
        clearTimeout(timer);
        nativeSendsOutstanding -= 1;
        if (record.outcome === 'pending') {
          record.outcome = 'failed';
          record.endedAt = Date.now();
        }
        reject(err);
      }
    );
  });
}

/** On some Android phones Firebase reads the SMS itself and signs the person in before they type
 *  anything -- react-native-firebase's own phone-auth guide says a code typed afterwards then fails
 *  "because the code was already used in the background". That is what a tester hit
 *  (auth/session-expired on confirm, with Firebase already holding a signed-in user). When confirm()
 *  fails but Firebase holds a user whose verified number is exactly the number we sent to, that user
 *  IS the proof of possession the code would have given, so use its ID token. The backend still
 *  verifies that token and that it attests the account's own number, exactly as for a typed code.
 *
 *  Refuses (returns null) unless the leftover-user clearing at send time succeeded, so a user from
 *  an earlier session can never be mistaken for this send's automatic verification. */
async function idTokenFromAutoVerifiedUser(send: SendRecord | null): Promise<string | null> {
  if (!send || !send.startedWithNoFirebaseUser) return null;
  try {
    const user = getAuth().currentUser;
    if (!user || !user.phoneNumber || user.phoneNumber !== send.phoneNumber) return null;
    const idToken = await user.getIdToken();
    await signOut(getAuth()).catch(() => {});
    send.recoveredViaAutoVerification = true;
    return idToken;
  } catch {
    return null;
  }
}

/** Confirms the code against the handle from sendPhoneVerificationCode() and returns the resulting
 *  Firebase ID token -- this is what gets sent to the backend (see PhoneVerificationProvider),
 *  never the code itself. Throws (via Firebase's own error) for a wrong/expired code, unless
 *  Firebase already verified this number automatically (see idTokenFromAutoVerifiedUser). */
export async function confirmPhoneVerificationCode(
  confirmation: PhoneConfirmation,
  code: string
): Promise<string> {
  const send = lastSend;
  if (send) send.confirmAttempts += 1;
  let user: NonNullable<Awaited<ReturnType<PhoneConfirmation['confirm']>>>['user'];
  try {
    const credential = await confirmation.confirm(code);
    if (!credential?.user) {
      throw new Error('Phone verification did not return a user credential.');
    }
    user = credential.user;
  } catch (err) {
    const recovered = await idTokenFromAutoVerifiedUser(send);
    if (recovered !== null) {
      // Not an error, so nothing else would ever record that this path ran.
      reportHandledEvent(
        'Phone code confirm recovered through automatic verification',
        'phone-auto-verification-recovered',
        phoneAuthDiagnostics()
      );
      return recovered;
    }
    throw err;
  }
  const idToken = await user.getIdToken();
  await signOut(getAuth()).catch(() => {});
  return idToken;
}
