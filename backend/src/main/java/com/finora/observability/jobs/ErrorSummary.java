package com.finora.observability.jobs;

/**
 * An error as the Background jobs page and its emails may show it: the exception's type and the
 * first place in Fynora's own code it came from, for example
 * {@code DataIntegrityViolationException at AccountPurgeSweepService:212}.
 *
 * <p>Never the message. Production has real customers, and an exception message can carry an email
 * address, a name or an account id. The full error is in the logs at the same time.
 */
final class ErrorSummary {

    private static final String OWN_CODE = "com.finora.";

    private ErrorSummary() {}

    static String of(Throwable error) {
        if (error == null) return null;
        String type = typeName(error.getClass());
        for (StackTraceElement frame : error.getStackTrace()) {
            if (frame.getClassName().startsWith(OWN_CODE)) {
                return type + " at " + simpleClassName(frame.getClassName()) + ":" + frame.getLineNumber();
            }
        }
        return type;
    }

    private static String typeName(Class<?> type) {
        Class<?> named = type;
        while (named.getSimpleName().isEmpty() && named.getSuperclass() != null) {
            named = named.getSuperclass();
        }
        return named.getSimpleName();
    }

    /** {@code com.finora.service.AccountPurgeSweepService$1} becomes {@code AccountPurgeSweepService}. */
    private static String simpleClassName(String className) {
        String simple = className.substring(className.lastIndexOf('.') + 1);
        int nested = simple.indexOf('$');
        return nested > 0 ? simple.substring(0, nested) : simple;
    }
}
