-- "Personal Care": a default category for salons and beauty parlours. Decided by Sid on 2026-10-02.
-- Before this, a payment to one had no fitting category and stayed "Other" (or, when the shop's
-- name read as a person's, "Personal Transfer"): measured on the real statement corpus, 6 rows.
-- ShopTradeCategory routes those rows here; AuthService.seedDefaultCategories seeds it for every
-- new registration, and this seeds it for everyone who already has an account.
--
-- Same shape as V246 and V123, for the same reasons (see V123):
--   - icon/color match the DEFAULT_CATEGORIES entry exactly, so existing and new users render the
--     category the same way ('scissors' is in CategoryPalette.ICONS and both clients' icon maps);
--   - NOT EXISTS on lower(name), because uq_categories_user_name_ci is case-insensitive and a
--     collision here would abort the migration and with it the backend's boot;
--   - a user who already made a category by this name keeps theirs exactly as it is, non-system,
--     still theirs to rename or delete. CategorizationService.resolveOrCreateCategory finds it by
--     name either way.
--
-- No transactions are moved. Rows already categorised keep their category; only new imports are
-- routed here.
INSERT INTO categories (id, user_id, name, is_system, icon, color)
SELECT gen_random_uuid(), u.id, 'Personal Care', true, 'scissors', 'pink'
FROM users u
WHERE NOT EXISTS (
    SELECT 1 FROM categories c
    WHERE c.user_id = u.id AND lower(c.name) = 'personal care'
);
