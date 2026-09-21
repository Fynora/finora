// Wire values from ErrorCode.java's own code() method (e.g. "AUTH_008"), NOT the Java enum
// constant name (e.g. "AUTH_MFA_REQUIRED") -- GlobalExceptionHandler serializes only code(). See
// frontend/src/api/errorCodes.ts's own history: AUTH_ACCOUNT_DEACTIVATED was once hand-typed as
// the enum name instead of 'AUTH_007' in both the source and its test, silently making that whole
// feature unreachable while the test still passed. A shared constant here is the same guard this
// codebase already uses on the user-app side, kept minimal to just what admin-portal needs today.
export const AUTH_MFA_REQUIRED = 'AUTH_008';
export const AUTH_MFA_INVALID_CODE = 'AUTH_009';
export const AUTH_MFA_NOT_AVAILABLE = 'AUTH_010';
// Unlike the AUTH_ codes above this is not an ErrorCode.java code(): AdminMfaEnrollmentFilter
// writes it straight into the response, the way PhoneVerificationFilter writes
// PHONE_VERIFICATION_REQUIRED, so the wire value is this literal string.
export const MFA_ENROLLMENT_REQUIRED = 'MFA_ENROLLMENT_REQUIRED';
