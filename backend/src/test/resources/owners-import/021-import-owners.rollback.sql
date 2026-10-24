-- The deletion of what 021-import-owners.sql makes. Test fixture with made-up people.
DELETE FROM user_accounts WHERE account_id IN (SELECT id FROM accounts WHERE number IN ('000000000001', '000000000002', '000000000003'));
DELETE FROM users WHERE login IN ('iiivanov', 'appetrova') AND role = 'OWNER' AND NOT EXISTS (SELECT 1 FROM user_accounts ua WHERE ua.user_id = users.id);
DELETE FROM account_owners WHERE account_id IN (SELECT id FROM accounts WHERE number IN ('000000000001', '000000000002', '000000000003'));
DELETE FROM address_owners WHERE owner_id IN (SELECT id FROM owners WHERE full_name IN ('Иванов Иван Иванович', 'Петрова Анна Петровна') AND NOT is_test);
DELETE FROM accounts WHERE number IN ('000000000001', '000000000002', '000000000003');
DELETE FROM owners WHERE full_name IN ('Иванов Иван Иванович', 'Петрова Анна Петровна') AND NOT is_test AND NOT EXISTS (SELECT 1 FROM account_owners ao WHERE ao.owner_id = owners.id);
