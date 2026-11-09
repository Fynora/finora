package com.finora.entity;

/**
 * Why an account was suspended. The three grounds section 15 of the Terms of Service gives
 * ("violate these terms, engage in fraudulent activity, or pose a security risk"), so a suspension
 * always names a ground the customer agreed to. Stored by name in {@code users.suspension_reason}.
 *
 * <p>{@link #customerText()} is what the customer reads, on the suspended screen and in the notice
 * email. It describes the ground, never the evidence: any detail goes in the admin's own message.
 */
public enum SuspensionReason {
    TERMS_VIOLATION("Your account was used in a way our Terms of Service don't allow."),
    FRAUD("We found activity on your account that looks like fraud."),
    SECURITY_RISK("Your account poses a risk to the security of Fynora or other people who use it.");

    private final String customerText;

    SuspensionReason(String customerText) {
        this.customerText = customerText;
    }

    public String customerText() {
        return customerText;
    }
}
