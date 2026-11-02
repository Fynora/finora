-- Privacy workstream 6: two gaps between what the Privacy Policy will say and what the code does.
--
-- 1. A staged upload (import_sessions.file_content) was stored as the plain file until the user
--    pressed Import, while confirmed statements have been encrypted since V107. Staging now
--    encrypts with the same EncryptionService, and this column records the key, exactly as V107
--    did for statement_imports and import_jobs. Null means the bytes are the plain file: a row
--    staged before this change. Such rows are swept by the 48-hour expiry like any other.
ALTER TABLE import_sessions ADD COLUMN IF NOT EXISTS encryption_key_id VARCHAR(50);

-- 2. Account deletion anonymises the user and clears each account's holder name, masked number,
--    branch and IFSC (AccountPurgeSweepService.purgeOne), but kept the name the user typed for the
--    account (for example "Salary HDFC") and product_identity_hash, a hash of the bank and the
--    account's full number. The purge now clears both; this does the same for accounts of users
--    deleted before it. accounts.name is NOT NULL and not unique, so every row gets one placeholder.
UPDATE accounts a
   SET name = 'Deleted account',
       product_identity_hash = NULL
  FROM users u
 WHERE a.user_id = u.id
   AND u.status = 'DELETED'
   AND (a.name <> 'Deleted account' OR a.product_identity_hash IS NOT NULL);
