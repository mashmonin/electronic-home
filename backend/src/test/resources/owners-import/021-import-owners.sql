-- Owners, personal accounts and logins of the owners from the sheet of charges. Test fixture with made-up people, made by tools/owners-import.
-- 3 accounts, 2 owners. Safe to run again: what exists is skipped.
CREATE TEMP TABLE tmp_owner_import (account_number varchar(32), apartment varchar(32), full_name varchar(255), login varchar(64), password_hash varchar(100)) ON COMMIT DROP;
INSERT INTO tmp_owner_import VALUES
  ('000000000001', '1', 'Иванов Иван Иванович', 'iiivanov', '$2a$10$mYHhPhV0Dv5QUPRDXuVPWetut5nkP/Jnnwc9eC741XKgVFv9diMt2'),
  ('000000000002', '2', 'Иванов Иван Иванович', 'iiivanov', '$2a$10$mYHhPhV0Dv5QUPRDXuVPWetut5nkP/Jnnwc9eC741XKgVFv9diMt2'),
  ('000000000003', '3', 'Петрова Анна Петровна', 'appetrova', '$2a$10$mYHhPhV0Dv5QUPRDXuVPWetut5nkP/Jnnwc9eC741XKgVFv9diMt2');

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM tmp_owner_import t WHERE NOT EXISTS (
      SELECT 1 FROM addresses a WHERE a.street = 'ул. Тестовая' AND a.house = 'Дом' AND a.apartment = t.apartment)) THEN
    RAISE EXCEPTION 'owners import: an apartment of the sheet is missing in addresses';
  END IF;
END $$;

INSERT INTO owners (full_name, is_test)
SELECT DISTINCT t.full_name, FALSE FROM tmp_owner_import t
WHERE NOT EXISTS (SELECT 1 FROM owners o WHERE o.full_name = t.full_name AND NOT o.is_test);

INSERT INTO accounts (number, address_id, is_test)
SELECT t.account_number, a.id, FALSE FROM tmp_owner_import t
JOIN addresses a ON a.street = 'ул. Тестовая' AND a.house = 'Дом' AND a.apartment = t.apartment
ON CONFLICT (number) DO NOTHING;

INSERT INTO address_owners (address_id, owner_id, is_test)
SELECT a.id, o.id, FALSE FROM tmp_owner_import t
JOIN addresses a ON a.street = 'ул. Тестовая' AND a.house = 'Дом' AND a.apartment = t.apartment
JOIN owners o ON o.full_name = t.full_name AND NOT o.is_test
ON CONFLICT DO NOTHING;

INSERT INTO account_owners (account_id, owner_id, is_test)
SELECT ac.id, o.id, FALSE FROM tmp_owner_import t
JOIN accounts ac ON ac.number = t.account_number
JOIN owners o ON o.full_name = t.full_name AND NOT o.is_test
ON CONFLICT DO NOTHING;

INSERT INTO users (login, password_hash, role)
SELECT DISTINCT t.login, t.password_hash, 'OWNER' FROM tmp_owner_import t
ON CONFLICT DO NOTHING;

-- only the users made by this import (the hash is ours) are linked; a login that was taken before is left as it is
INSERT INTO user_accounts (user_id, account_id)
SELECT u.id, ac.id FROM tmp_owner_import t
JOIN users u ON lower(u.login) = lower(t.login) AND u.password_hash = t.password_hash
JOIN accounts ac ON ac.number = t.account_number
ON CONFLICT DO NOTHING;
