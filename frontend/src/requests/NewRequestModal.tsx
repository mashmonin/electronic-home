import { Alert, Button, Form, Input, Modal, Select } from 'antd'
import { useState } from 'react'
import { createRequest, type CreateResult, type RequestItem } from './requestsApi'

const TEXT_LIMIT = 2000

interface Props {
  open: boolean
  /** The numbers of the accounts of the owner: with more than one he chooses the apartment. */
  accounts: string[]
  onCancel: () => void
  onCreated: (request: RequestItem) => void
}

interface FieldErrors {
  text?: string
  account?: string
}

const FAILURES: Record<Exclude<CreateResult['status'], 'ok'>, string> = {
  forbidden: 'Недостаточно прав',
  'no-account': 'К вашей учётной записи не привязан лицевой счёт',
  invalid: 'Проверьте сообщение',
  error: 'Не удалось создать заявку. Попробуйте позже',
}

// The window of a new request: the subject, the first message and, if the owner has several accounts, the apartment.
export default function NewRequestModal({ open, accounts, onCancel, onCreated }: Props) {
  const [text, setText] = useState('')
  const [account, setAccount] = useState<string | null>(null)
  const [errors, setErrors] = useState<FieldErrors>({})
  const [failure, setFailure] = useState<string | null>(null)
  const [sending, setSending] = useState(false)
  const chooseAccount = accounts.length > 1

  const submit = async () => {
    const found: FieldErrors = {}
    if (!text.trim()) found.text = 'Введите сообщение'
    else if (text.trim().length > TEXT_LIMIT) found.text = `Сообщение длиннее ${TEXT_LIMIT} символов`
    if (chooseAccount && !account) found.account = 'Выберите квартиру'
    setErrors(found)
    setFailure(null)
    if (Object.keys(found).length > 0) return

    setSending(true)
    const result = await createRequest(text.trim(), chooseAccount ? (account ?? undefined) : undefined)
    setSending(false)
    if (result.status === 'ok') {
      setText('')
      setAccount(null)
      onCreated(result.request)
    } else {
      setFailure(FAILURES[result.status])
    }
  }

  return (
    <Modal
      open={open}
      title="Новая заявка"
      onCancel={onCancel}
      destroyOnClose
      footer={[
        <Button key="cancel" onClick={onCancel}>
          Отмена
        </Button>,
        <Button key="create" type="primary" loading={sending} onClick={() => void submit()}>
          Создать
        </Button>,
      ]}
    >
      <Form layout="vertical" onFinish={() => void submit()}>
        {failure && <Alert type="error" showIcon message={failure} style={{ marginBottom: 16 }} />}
        {chooseAccount && (
          <Form.Item label="Квартира" validateStatus={errors.account ? 'error' : undefined} help={errors.account}>
            <Select
              placeholder="Выберите квартиру"
              value={account}
              onChange={setAccount}
              options={accounts.map((number) => ({ value: number, label: `Лицевой счёт № ${number}` }))}
            />
          </Form.Item>
        )}
        <Form.Item label="Сообщение" validateStatus={errors.text ? 'error' : undefined} help={errors.text}>
          <Input.TextArea
            aria-label="Сообщение"
            rows={5}
            value={text}
            onChange={(event) => setText(event.target.value)}
          />
        </Form.Item>
      </Form>
    </Modal>
  )
}
