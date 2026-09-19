import { getAuth, signInWithPhoneNumber, signOut } from '@react-native-firebase/auth';

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
  };
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
    let native: Promise<PhoneConfirmation>;
    try {
      native = signInWithPhoneNumber(getAuth(), phoneNumber);
    } catch (err) {
      // getAuth() or the native module threw before any promise existed. Without this the executor
      // exits early: the timer below would run on for the full timeout and the record would flip
      // to "timed-out" for a send that failed at once.
      clearTimeout(timer);
      nativeSendsOutstanding -= 1;
      record.outcome = 'failed';
      record.endedAt = Date.now();
      reject(err);
      return;
    }
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

/** Confirms the code against the handle from sendPhoneVerificationCode() and returns the resulting
 *  Firebase ID token -- this is what gets sent to the backend (see PhoneVerificationProvider),
 *  never the code itself. Throws (via Firebase's own error) for a wrong/expired code. */
export async function confirmPhoneVerificationCode(
  confirmation: PhoneConfirmation,
  code: string
): Promise<string> {
  if (lastSend) lastSend.confirmAttempts += 1;
  const credential = await confirmation.confirm(code);
  if (!credential?.user) {
    throw new Error('Phone verification did not return a user credential.');
  }
  const idToken = await credential.user.getIdToken();
  await signOut(getAuth()).catch(() => {});
  return idToken;
}
