-- Gate 2a spec §6: an admin can mark a USER-scope account as internal (the founder's own account, a
-- tester's), so the activation page leaves it out of every count. ADMIN-scope accounts are left out
-- already and never carry the flag (AdminUserService refuses it). Set from the admin user page and
-- audited as USER_INTERNAL_ACCOUNT_SET.
ALTER TABLE users ADD COLUMN internal_account BOOLEAN NOT NULL DEFAULT FALSE;
