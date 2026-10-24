/**
 * Request limits shared across features: what the money columns can hold
 * ({@link com.finora.validation.MoneyLimits}) and the whole-paisa rule
 * ({@link com.finora.validation.WholePaise}). Enforced at the HTTP boundary by
 * {@link com.finora.config.RequestInputBoundsConfig}, on request DTOs, and on
 * {@code Account.setBalance}.
 */
package com.finora.validation;
