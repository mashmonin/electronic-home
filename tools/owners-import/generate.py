#!/usr/bin/env python3
"""Prepares the import of the owners, their accounts and logins from the sheet of charges (an .xls file).

Takes from the file only the number of the personal account, the full name of the owner and the apartment.
Writes, outside of git (the default folder is data/ in the root of the repository):
  migrations/021-import-owners.sql           accounts, owners, users with BCrypt hashes (personal data!)
  migrations/021-import-owners.rollback.sql  the deletion of what the import makes
  credentials/owner-passwords.csv            login;password;accounts, handed to the owners and deleted after that
Prints a report without personal data. See README.md.
"""
import argparse
import collections
import csv
import os
import re
import secrets
import subprocess
import sys
from pathlib import Path

STREET, HOUSE = 'ул. Тестовая', 'Дом'
ACCOUNT_WIDTH = 12
PASSWORD_LENGTH = 12
# without the look-alikes 0/O, 1/l/I
LOWER, UPPER, DIGITS = 'abcdefghijkmnopqrstuvwxyz', 'ABCDEFGHJKLMNPQRSTUVWXYZ', '23456789'

TRANSLIT = dict(
    zip(
        'абвгдеёжзийклмнопрстуфхцчшщъыьэюя',
        ['a', 'b', 'v', 'g', 'd', 'e', 'e', 'zh', 'z', 'i', 'y', 'k', 'l', 'm', 'n', 'o', 'p', 'r', 's', 't', 'u', 'f',
         'kh', 'ts', 'ch', 'sh', 'shch', '', 'y', '', 'e', 'yu', 'ya'],
    )
)
ORGANIZATION = re.compile(r'\b(ООО|ПАО|ОАО|ЗАО|АО|ИП|банк)\b|["«»]', re.IGNORECASE)

# what must not be in the file besides the data we take; the values are never printed
SENSITIVE = {
    'e-mail': r'[\w.+-]+@[\w-]+\.[\w.]+',
    'phone': r'(?<!\d)(?:\+7|8)[\s(-]*\d{3}[\s)-]*\d{3}[\s-]*\d{2}[\s-]*\d{2}(?!\d)',
    'SNILS': r'(?<!\d)\d{3}-\d{3}-\d{3}[ -]\d{2}(?!\d)',
    'passport': r'паспорт|серия\s*\d{2}\s*\d{2}',
    'birth date': r'дата рожд|\bд\.\s?р\.',
    'bank card': r'(?<!\d)\d{4}[ -]\d{4}[ -]\d{4}[ -]\d{4}(?!\d)',
}


class Stop(Exception):
    """The preparation cannot go on without a decision of a person."""


def transliterate(word: str) -> str:
    return ''.join(TRANSLIT.get(letter, '') for letter in word.lower())


def is_organization(name: str) -> bool:
    return bool(ORGANIZATION.search(name))


def login_of(full_name: str) -> str:
    """The first letter of the first name + the first letter of the patronymic + the surname, in latin."""
    surname, first, patronymic = full_name.split()
    return transliterate(first[0]) + transliterate(patronymic[0]) + transliterate(surname)


def new_password() -> str:
    while True:
        chars = [secrets.choice(LOWER + UPPER + DIGITS) for _ in range(PASSWORD_LENGTH)]
        password = ''.join(chars)
        if any(c in LOWER for c in chars) and any(c in UPPER for c in chars) and any(c in DIGITS for c in chars):
            return password


def bcrypt_hash(password: str) -> str:
    """BCrypt, cost 10, as `htpasswd -niBC 10`; the password goes through stdin, not through the command line."""
    out = subprocess.run(['htpasswd', '-niBC', '10', 'x'], input=password + '\n', capture_output=True, text=True, check=True)
    return '$2a$' + out.stdout.strip().split(':', 1)[1][4:]


def read_sheet(path: str):
    import xlrd

    return xlrd.open_workbook(path).sheet_by_index(0)


def check_sensitive(sheet) -> dict:
    """The kinds of sensitive data found and how many distinct values; a value that is in every receipt belongs to the organization."""
    receipts = sum(1 for r in range(sheet.nrows) if str(sheet.cell_value(r, 0)).startswith('Ф.И.О.')) or 1
    found = {}
    for kind, pattern in SENSITIVE.items():
        values = collections.Counter()
        for r in range(sheet.nrows):
            for c in range(sheet.ncols):
                for match in re.finditer(pattern, str(sheet.cell_value(r, c)), re.IGNORECASE):
                    values[match.group(0)] += 1
        personal = [v for v, n in values.items() if n < receipts]
        if personal:
            found[kind] = len(personal)
    return found


def read_receipts(sheet):
    """The distinct personal accounts: [(number, full name, apartment or None, office or None)]; every receipt is in the file twice."""
    accounts = {}
    for r in range(sheet.nrows):
        if not str(sheet.cell_value(r, 0)).startswith('Ф.И.О.'):
            continue
        name = ' '.join(str(sheet.cell_value(r + 1, 0)).split())
        number = re.search(r'№\s*(\d+)', str(sheet.cell_value(r + 2, 42)))
        address = str(sheet.cell_value(r + 2, 2))
        if not number:
            raise Stop(f'no number of the personal account at the row {r + 3}')
        apartment = re.search(r'Кв\.\s*(\d+)', address)
        office = re.search(r'Оф\.\s*(\d+)', address)
        entry = (name, apartment.group(1) if apartment else None, office.group(1) if office else None)
        if accounts.setdefault(number.group(1), entry) != entry:
            raise Stop(f'the personal account {number.group(1)} is met with different data')
    return [(number, *entry) for number, entry in accounts.items()]


def plan(receipts):
    """Splits the accounts into the loaded ones and the skipped ones; builds the owners and their logins."""
    loaded, skipped = [], []
    for number, name, apartment, office in receipts:
        if office or is_organization(name):
            skipped.append((number, 'office' if office else 'organization'))
        elif not apartment:
            raise Stop(f'the personal account {number} has neither an apartment nor an office')
        elif len(name.split()) != 3:
            raise Stop(f'the name at the personal account {number} is not of three words')
        else:
            loaded.append((int(number), name, apartment))
    owners = collections.OrderedDict()
    for number, name, apartment in sorted(loaded):
        owners.setdefault(name, []).append((number, apartment))
    logins = {}
    for name in owners:
        login = login_of(name)
        if logins.setdefault(login, name) != name:
            raise Stop(f'the login {login} comes out for different people (accounts {[a for a, _ in owners[name]]})')
    return owners, skipped


def quote(text: str) -> str:
    return "'" + text.replace("'", "''") + "'"


def sql_text(owners, hashes):
    rows = []
    for name, accounts in owners.items():
        for number, apartment in accounts:
            rows.append(f"({quote(str(number).zfill(ACCOUNT_WIDTH))}, {quote(apartment)}, {quote(name)}, {quote(login_of(name))}, {quote(hashes[name])})")
    values = ',\n  '.join(rows)
    return f"""-- Owners, personal accounts and logins of the owners from the sheet of charges. Made by tools/owners-import; NOT for git.
-- {len(rows)} accounts, {len(owners)} owners. Safe to run again: what exists is skipped.
CREATE TEMP TABLE tmp_owner_import (account_number varchar(32), apartment varchar(32), full_name varchar(255), login varchar(64), password_hash varchar(100)) ON COMMIT DROP;
INSERT INTO tmp_owner_import VALUES
  {values};

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM tmp_owner_import t WHERE NOT EXISTS (
      SELECT 1 FROM addresses a WHERE a.street = {quote(STREET)} AND a.house = {quote(HOUSE)} AND a.apartment = t.apartment)) THEN
    RAISE EXCEPTION 'owners import: an apartment of the sheet is missing in addresses';
  END IF;
END $$;

INSERT INTO owners (full_name, is_test)
SELECT DISTINCT t.full_name, FALSE FROM tmp_owner_import t
WHERE NOT EXISTS (SELECT 1 FROM owners o WHERE o.full_name = t.full_name AND NOT o.is_test);

INSERT INTO accounts (number, address_id, is_test)
SELECT t.account_number, a.id, FALSE FROM tmp_owner_import t
JOIN addresses a ON a.street = {quote(STREET)} AND a.house = {quote(HOUSE)} AND a.apartment = t.apartment
ON CONFLICT (number) DO NOTHING;

INSERT INTO address_owners (address_id, owner_id, is_test)
SELECT a.id, o.id, FALSE FROM tmp_owner_import t
JOIN addresses a ON a.street = {quote(STREET)} AND a.house = {quote(HOUSE)} AND a.apartment = t.apartment
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
"""


def rollback_text(owners):
    numbers = ', '.join(quote(str(n).zfill(ACCOUNT_WIDTH)) for accounts in owners.values() for n, _ in accounts)
    logins = ', '.join(quote(login_of(name)) for name in owners)
    names = ', '.join(quote(name) for name in owners)
    return f"""-- The deletion of what 021-import-owners.sql makes. Made by tools/owners-import; NOT for git.
DELETE FROM user_accounts WHERE account_id IN (SELECT id FROM accounts WHERE number IN ({numbers}));
DELETE FROM users WHERE login IN ({logins}) AND role = 'OWNER' AND NOT EXISTS (SELECT 1 FROM user_accounts ua WHERE ua.user_id = users.id);
DELETE FROM account_owners WHERE account_id IN (SELECT id FROM accounts WHERE number IN ({numbers}));
DELETE FROM address_owners WHERE owner_id IN (SELECT id FROM owners WHERE full_name IN ({names}) AND NOT is_test);
DELETE FROM accounts WHERE number IN ({numbers});
DELETE FROM owners WHERE full_name IN ({names}) AND NOT is_test AND NOT EXISTS (SELECT 1 FROM account_owners ao WHERE ao.owner_id = owners.id);
"""


def write_private(path: Path, text: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, 'w', encoding='utf-8', newline='') as file:
        file.write(text)
    os.chmod(path, 0o600)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('xls', help='the sheet of charges')
    parser.add_argument('--out', default=str(Path(__file__).resolve().parents[2] / 'data'), help='the folder for the results (must be out of git)')
    parser.add_argument('--allow-sensitive', action='store_true', help='go on although sensitive data were found in the file')
    args = parser.parse_args(argv)
    try:
        sheet = read_sheet(args.xls)
        found = check_sensitive(sheet)
        print('Check of the file for sensitive data:')
        if found:
            for kind, count in found.items():
                print(f'  FOUND {kind}: {count} distinct values (not printed)')
            if not args.allow_sensitive:
                raise Stop('sensitive data in the file; look at them and run again with --allow-sensitive')
        else:
            print('  nothing found (the phone, INN and bank details of the organization in every receipt are not counted)')
        owners, skipped = plan(read_receipts(sheet))
    except Stop as stop:
        print(f'STOPPED: {stop}', file=sys.stderr)
        return 1
    passwords = {name: new_password() for name in owners}
    hashes = {name: bcrypt_hash(password) for name, password in passwords.items()}
    out = Path(args.out)
    write_private(out / 'migrations' / '021-import-owners.sql', sql_text(owners, hashes))
    write_private(out / 'migrations' / '021-import-owners.rollback.sql', rollback_text(owners))
    lines = ['login;password;accounts']
    for name, accounts in owners.items():
        lines.append(f"{login_of(name)};{passwords[name]};{','.join(str(n) for n, _ in accounts)}")
    write_private(out / 'credentials' / 'owner-passwords.csv', '\n'.join(lines) + '\n')
    multi = sum(1 for accounts in owners.values() if len(accounts) > 1)
    print(f'Accounts: {sum(len(a) for a in owners.values())}; users: {len(owners)}; with several accounts: {multi}')
    print(f'Skipped: {len(skipped)} ({", ".join(f"{n} {why}" for n, why in sorted(skipped))})')
    print(f'Written to {out}: migrations/021-import-owners.sql, migrations/021-import-owners.rollback.sql, credentials/owner-passwords.csv')
    return 0


if __name__ == '__main__':
    sys.exit(main())
