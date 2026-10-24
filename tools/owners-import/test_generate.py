import unittest

import generate


class LoginTest(unittest.TestCase):
    def test_mask_of_the_example(self):
        self.assertEqual(generate.login_of('Шмонин Андрей Александрович'), 'aashmonin')

    def test_special_letters(self):
        self.assertEqual(generate.login_of('Щекотова Юлия Ёлкина'), 'yueshchekotova')
        self.assertEqual(generate.login_of('Ростовский Антон Львович'), 'alrostovskiy')
        self.assertEqual(generate.login_of('Леонтьев Дмитрий Васильевич'), 'dvleontev')
        self.assertEqual(generate.login_of('Яхин Ярослав Юрьевич'), 'yayuyakhin')


class OrganizationTest(unittest.TestCase):
    def test_organizations(self):
        for name in ['ООО Ромашка', 'ПАО Завод "Ветеринарные препараты"', 'Юлиян ООО', 'Банк Развития']:
            self.assertTrue(generate.is_organization(name), name)

    def test_people(self):
        self.assertFalse(generate.is_organization('Иванов Иван Иванович'))


class PlanTest(unittest.TestCase):
    def receipts(self):
        return [
            ('1', 'Иванов Иван Иванович', '1', None),
            ('2', 'Иванов Иван Иванович', '2', None),
            ('3', 'Петров Пётр Петрович', '3', None),
            ('117', 'ПАО Завод "Ветеринарные препараты"', '117', None),
            ('00', 'Сидоров Сидор Сидорович', None, '1'),
        ]

    def test_one_user_for_several_accounts_and_skips(self):
        owners, skipped = generate.plan(self.receipts())
        self.assertEqual(owners['Иванов Иван Иванович'], [(1, '1'), (2, '2')])
        self.assertEqual(len(owners), 2)
        self.assertEqual(sorted(skipped), [('00', 'office'), ('117', 'organization')])

    def test_same_login_for_different_people_stops(self):
        receipts = [('1', 'Иванов Иван Иванович', '1', None), ('2', 'Иванов Игорь Ильич', '2', None)]
        with self.assertRaises(generate.Stop):
            generate.plan(receipts)

    def test_name_of_two_words_stops(self):
        with self.assertRaises(generate.Stop):
            generate.plan([('1', 'Иванов Иван', '1', None)])


class SecretsTest(unittest.TestCase):
    def test_password_alphabet_and_length(self):
        for _ in range(200):
            password = generate.new_password()
            self.assertEqual(len(password), generate.PASSWORD_LENGTH)
            self.assertFalse(set(password) & set('0OIl1'))
            self.assertTrue(any(c.isupper() for c in password) and any(c.islower() for c in password) and any(c.isdigit() for c in password))

    def test_bcrypt_hash_is_of_the_format_of_the_project(self):
        hashed = generate.bcrypt_hash('Abc-test-123')
        self.assertRegex(hashed, r'^\$2a\$10\$.{53}$')


class SqlTest(unittest.TestCase):
    def test_sql_has_hashes_and_no_open_passwords(self):
        owners = {'Иванов Иван Иванович': [(5, '5')]}
        text = generate.sql_text(owners, {'Иванов Иван Иванович': '$2a$10$' + 'x' * 53})
        self.assertIn("'000000000005'", text)
        self.assertIn("'iiivanov'", text)
        self.assertIn('ON CONFLICT', text)

    def test_quotes_are_escaped(self):
        self.assertEqual(generate.quote("O'Neil"), "'O''Neil'")


if __name__ == '__main__':
    unittest.main()
