import { UploadOutlined } from '@ant-design/icons'
import { Alert, Button, Card, DatePicker, Flex, Form, InputNumber, Popconfirm, Select, Spin, Upload } from 'antd'
import type { UploadFile } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useEffect, useState } from 'react'
import { type AccountListItem, fetchAccounts } from './accountApi'
import { type PublicationErrors, hasErrors, validatePublication } from './publication'
import { formatReceiptMonth } from './receiptFormat'
import { type PublishResult, fetchReceipts, publishReceipt } from './receiptsApi'

type Notice = { type: 'success' | 'error'; text: string }
type Accounts = { kind: 'loading' } | { kind: 'ready'; items: AccountListItem[] } | { kind: 'error' }
// The months that already have a receipt (`YYYY-MM`) for the chosen apartment: publishing one of them replaces it.
type Published = { kind: 'idle' } | { kind: 'loading' } | { kind: 'ready'; months: string[] } | { kind: 'error' }

const FAILURES: Record<Exclude<PublishResult['status'], 'created' | 'replaced'>, string> = {
  invalid: 'Квитанция не принята. Проверьте, что файл является PDF, а суммы указаны верно',
  'too-large': 'Файл слишком большой. Максимум 5 МБ',
  'not-found': 'Лицевой счёт не найден',
  forbidden: 'Недостаточно прав',
  error: 'Не удалось опубликовать квитанцию. Попробуйте позже',
}

// "кв. 1 · ул. Тестовая, д. Тест (счёт 000000000001)"; the address of the API ends with the apartment already.
export function apartmentLabel(account: AccountListItem): string {
  const street = account.address.endsWith(`, кв. ${account.apartment}`)
    ? account.address.slice(0, -`, кв. ${account.apartment}`.length)
    : account.address
  return `кв. ${account.apartment} · ${street} (счёт ${account.number})`
}

// The form of the administrator: the apartment, a month, a PDF and, optionally, the two amounts of the receipt.
export default function PublishReceiptForm() {
  const [accounts, setAccounts] = useState<Accounts>({ kind: 'loading' })
  const [accountsAttempt, setAccountsAttempt] = useState(0)
  const [selected, setSelected] = useState<string | null>(null)
  const [published, setPublished] = useState<Published>({ kind: 'idle' })
  const [publishedAttempt, setPublishedAttempt] = useState(0)
  const [month, setMonth] = useState<Dayjs | null>(null)
  const [files, setFiles] = useState<UploadFile[]>([])
  const [capitalRepair, setCapitalRepair] = useState<number | null>(null)
  const [utilities, setUtilities] = useState<number | null>(null)
  const [errors, setErrors] = useState<PublicationErrors>({})
  const [notice, setNotice] = useState<Notice | null>(null)
  const [confirming, setConfirming] = useState(false)
  const [sending, setSending] = useState(false)

  useEffect(() => {
    let cancelled = false
    setAccounts({ kind: 'loading' })
    fetchAccounts().then((result) => {
      if (!cancelled) setAccounts(result.status === 'ok' ? { kind: 'ready', items: result.accounts } : { kind: 'error' })
    })
    return () => {
      cancelled = true
    }
  }, [accountsAttempt])

  useEffect(() => {
    if (!selected) {
      setPublished({ kind: 'idle' })
      return
    }
    let cancelled = false
    setPublished({ kind: 'loading' })
    fetchReceipts(selected).then((result) => {
      if (cancelled) return
      setPublished(
        result.status === 'ok' ? { kind: 'ready', months: result.receipts.map((receipt) => receipt.month) } : { kind: 'error' },
      )
    })
    return () => {
      cancelled = true
    }
  }, [selected, publishedAttempt])

  const account = accounts.kind === 'ready' ? accounts.items.find((item) => item.number === selected) : undefined
  const file = files[0]?.originFileObj ?? null
  const monthKey = month?.format('YYYY-MM') ?? null
  const replaces = monthKey !== null && published.kind === 'ready' && published.months.includes(monthKey)
  const ready = account !== undefined && published.kind === 'ready' && month !== null && file !== null

  const send = async () => {
    if (!account || !monthKey || !file) return
    setSending(true)
    setNotice(null)
    const amounts = capitalRepair !== null && utilities !== null ? { capitalRepair, utilities } : null
    const result = await publishReceipt(account.number, monthKey, file, amounts)
    setSending(false)
    if (result.status === 'created' || result.status === 'replaced') {
      const verb = result.status === 'created' ? 'опубликована' : 'заменена'
      setNotice({ type: 'success', text: `Квитанция за ${formatReceiptMonth(monthKey)} (кв. ${account.apartment}) ${verb}` })
      // the apartment stays chosen: the next month of the same apartment is the usual next step
      setMonth(null)
      setFiles([])
      setCapitalRepair(null)
      setUtilities(null)
      setPublishedAttempt((n) => n + 1)
    } else {
      setNotice({ type: 'error', text: FAILURES[result.status] })
    }
  }

  // Checks the form; a receipt that already exists is replaced only after the administrator confirms it.
  const submit = () => {
    const found = validatePublication({
      file: file && { name: file.name, type: file.type, size: file.size },
      capitalRepair,
      utilities,
    })
    setErrors(found)
    if (hasErrors(found)) return
    if (replaces) setConfirming(true)
    else void send()
  }

  const retry = (action: () => void) => (
    <Button size="small" onClick={action}>
      Повторить
    </Button>
  )

  return (
    <Card title="Опубликовать квитанцию" size="small">
      <Flex vertical gap={16}>
        {accounts.kind === 'error' && (
          <Alert
            type="error"
            showIcon
            message="Не удалось загрузить список квартир"
            action={retry(() => setAccountsAttempt((n) => n + 1))}
          />
        )}
        {published.kind === 'error' && (
          <Alert
            type="error"
            showIcon
            message="Не удалось проверить опубликованные квитанции"
            action={retry(() => setPublishedAttempt((n) => n + 1))}
          />
        )}
        {notice && <Alert type={notice.type} showIcon message={notice.text} />}
        <Form layout="vertical" onFinish={submit}>
          <Form.Item label="Квартира" htmlFor="receipt-apartment">
            <Select
              id="receipt-apartment"
              showSearch
              optionFilterProp="label"
              placeholder="Выберите квартиру"
              loading={accounts.kind === 'loading' || published.kind === 'loading'}
              disabled={accounts.kind !== 'ready'}
              notFoundContent={accounts.kind === 'loading' ? <Spin size="small" /> : 'Ничего не найдено'}
              value={selected}
              onChange={(number: string) => {
                setSelected(number)
                setNotice(null)
              }}
              options={
                accounts.kind === 'ready'
                  ? accounts.items.map((item) => ({ value: item.number, label: apartmentLabel(item) }))
                  : []
              }
            />
          </Form.Item>
          <Form.Item label="Месяц" htmlFor="receipt-month">
            <DatePicker
              id="receipt-month"
              picker="month"
              format="MM.YYYY"
              placeholder="Выберите месяц"
              value={month}
              onChange={setMonth}
              disabledDate={(date) => date.isAfter(dayjs(), 'month')}
              style={{ width: '100%' }}
            />
          </Form.Item>
          <Form.Item label="Файл PDF" validateStatus={errors.file ? 'error' : undefined} help={errors.file}>
            <Upload
              accept=".pdf,application/pdf"
              maxCount={1}
              fileList={files}
              beforeUpload={() => false}
              onChange={({ fileList }) => setFiles(fileList.slice(-1))}
            >
              <Button icon={<UploadOutlined />}>Выбрать файл</Button>
            </Upload>
          </Form.Item>
          <Form.Item
            label="Суммы, ₽ (необязательно)"
            validateStatus={errors.amounts ? 'error' : undefined}
            help={errors.amounts}
          >
            <Flex gap={12} wrap>
              <InputNumber
                aria-label="Капитальный ремонт, ₽"
                placeholder="Капитальный ремонт, ₽"
                min={0}
                precision={2}
                decimalSeparator=","
                value={capitalRepair}
                onChange={setCapitalRepair}
                style={{ flex: '1 1 200px' }}
              />
              <InputNumber
                aria-label="Коммунальные услуги, ₽"
                placeholder="Коммунальные услуги, ₽"
                min={0}
                precision={2}
                decimalSeparator=","
                value={utilities}
                onChange={setUtilities}
                style={{ flex: '1 1 200px' }}
              />
            </Flex>
          </Form.Item>
          <Popconfirm
            open={confirming}
            title={`Квитанция за ${monthKey ? formatReceiptMonth(monthKey) : ''} (кв. ${account?.apartment ?? ''}) уже опубликована. Заменить?`}
            description="Файл и суммы будут заменены"
            okText="Заменить"
            cancelText="Отмена"
            onConfirm={() => {
              setConfirming(false)
              void send()
            }}
            onCancel={() => setConfirming(false)}
          >
            <Button type="primary" htmlType="submit" disabled={!ready || sending} loading={sending}>
              Опубликовать
            </Button>
          </Popconfirm>
        </Form>
      </Flex>
    </Card>
  )
}
