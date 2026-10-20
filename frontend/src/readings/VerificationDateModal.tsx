import { PaperClipOutlined } from '@ant-design/icons'
import { Alert, Button, DatePicker, Flex, Modal, Spin, Typography } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useEffect, useRef, useState } from 'react'
import {
  ACT_ACCEPT,
  type ChangeDateResult,
  actProblem,
  changeVerificationDate,
  uploadVerificationAct,
} from './verificationApi'

interface Props {
  open: boolean
  accountNumber: string
  meterNumber: string
  /** The date now, `YYYY-MM-DD`; it is what the picker starts with. */
  verificationDate?: string
  onCancel: () => void
  /** The server accepted the change. */
  onChanged: (result: Extract<ChangeDateResult, { status: 'ok' }>) => void
}

const FAILURES: Record<Exclude<ChangeDateResult['status'], 'ok'>, string> = {
  invalid: 'Сервер не принял дату или акт. Загрузите акт заново и проверьте дату',
  'not-found': 'Счётчик не найден',
  forbidden: 'Недостаточно прав',
  conflict: 'Изменение не сохранено из-за одновременного изменения. Повторите',
  error: 'Не удалось сохранить. Попробуйте позже',
}

type Act = { name: string; state: 'uploading' } | { name: string; state: 'ready'; key: string }

// The window of the change of the verification date: the new date and the act of the verification, which is required.
// The act is uploaded as soon as it is attached; "Сохранить" sends the date with the key of the uploaded file.
export default function VerificationDateModal({ open, accountNumber, meterNumber, verificationDate, onCancel, onChanged }: Props) {
  const input = useRef<HTMLInputElement>(null)
  const [date, setDate] = useState<Dayjs | null>(null)
  const [act, setAct] = useState<Act | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  // a file chosen later wins: the answer of an upload that was replaced is dropped
  const latest = useRef(0)

  useEffect(() => {
    if (!open) return
    setDate(verificationDate ? dayjs(verificationDate) : null)
    setAct(null)
    setProblem(null)
    setFailure(null)
    latest.current += 1
  }, [open, verificationDate])

  const choose = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    // the same file may be chosen again after it was removed
    event.target.value = ''
    if (!file) return
    setFailure(null)
    const wrong = actProblem(file)
    if (wrong) {
      setProblem(wrong)
      return
    }
    setProblem(null)
    const ticket = ++latest.current
    setAct({ name: file.name, state: 'uploading' })
    const result = await uploadVerificationAct(accountNumber, meterNumber, file)
    if (ticket !== latest.current) return
    if (result.status === 'ok') {
      setAct({ name: file.name, state: 'ready', key: result.fileKey })
    } else {
      setAct(null)
      setProblem(
        result.status === 'too-large'
          ? 'Файл больше 10 МБ'
          : result.status === 'unsupported'
            ? 'Тип файла не поддерживается или содержимое не соответствует расширению'
            : 'Не удалось загрузить файл. Попробуйте ещё раз',
      )
    }
  }

  const remove = () => {
    latest.current += 1
    setAct(null)
    setProblem(null)
  }

  const canSave = date !== null && act?.state === 'ready' && !saving

  const save = async () => {
    if (!date || act?.state !== 'ready') return
    setSaving(true)
    setFailure(null)
    const result = await changeVerificationDate(accountNumber, meterNumber, date.format('YYYY-MM-DD'), act.key)
    setSaving(false)
    if (result.status === 'ok') onChanged(result)
    else setFailure(FAILURES[result.status])
  }

  return (
    <Modal
      open={open}
      title="Изменение срока проверки"
      okText="Сохранить"
      cancelText="Отмена"
      okButtonProps={{ disabled: !canSave, loading: saving }}
      cancelButtonProps={{ disabled: saving }}
      maskClosable={!saving}
      closable={!saving}
      onOk={() => void save()}
      onCancel={onCancel}
      destroyOnHidden
    >
      <Flex vertical gap={16}>
        <Typography.Text strong>{meterNumber}</Typography.Text>
        <Flex vertical gap={4}>
          <Typography.Text id="verification-date-label">Новая дата проверки</Typography.Text>
          <DatePicker
            aria-labelledby="verification-date-label"
            format="DD.MM.YYYY"
            placeholder="Выберите дату"
            allowClear={false}
            value={date}
            onChange={setDate}
            style={{ width: '100%' }}
          />
        </Flex>
        <Flex vertical gap={8}>
          <Typography.Text>Акт проверки (PDF, JPG или PNG, до 10 МБ), обязательно</Typography.Text>
          <input ref={input} type="file" hidden accept={ACT_ACCEPT} data-testid="act-input" onChange={(event) => void choose(event)} />
          <div>
            <Button icon={<PaperClipOutlined />} disabled={saving || act?.state === 'uploading'} onClick={() => input.current?.click()}>
              {act ? 'Заменить файл' : 'Прикрепить акт'}
            </Button>
          </div>
          {problem && <Alert type="warning" showIcon message={problem} />}
          {act && (
            <Flex align="center" justify="space-between" gap={8} data-testid="chosen-act">
              <Typography.Text ellipsis style={{ flex: 1, minWidth: 0 }}>
                {act.name}
              </Typography.Text>
              {act.state === 'uploading' ? (
                <Flex align="center" gap={8}>
                  <Spin size="small" />
                  <Typography.Text type="secondary">Загрузка…</Typography.Text>
                </Flex>
              ) : (
                <Flex align="center" gap={8}>
                  <Typography.Text type="success">Загружен</Typography.Text>
                  <Button type="text" size="small" disabled={saving} onClick={remove} aria-label={`Удалить файл «${act.name}»`}>
                    Удалить
                  </Button>
                </Flex>
              )}
            </Flex>
          )}
        </Flex>
        {failure && <Alert type="error" showIcon message={failure} />}
      </Flex>
    </Modal>
  )
}
