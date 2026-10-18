import { Button, Flex, Form, message } from 'antd'
import { useState } from 'react'
import MeterCard from './MeterCard'
import { submitReadings } from './readingsApi'
import type { Meter, ReadingServerError, ReadingToSend } from './types'
import { isFilled, normalizeReading } from './validation'

interface FormValues {
  /** The entered value of each meter card, by the index of the card. */
  values: string[]
}

interface Props {
  accountNumber: string
  meters: Meter[]
  /** Reloads the account after the server answered; resolves to false when the reload failed. */
  onChanged: () => Promise<boolean>
}

const ERROR_MESSAGES: Record<string, string> = {
  VALUE_LESS_THAN_PREVIOUS: 'Показание не может быть меньше предыдущего',
  INVALID_VALUE: 'Введите число, не более трёх знаков после запятой',
  METER_NOT_FOUND: 'Счётчик не найден',
  DUPLICATE_METER: 'Счётчик указан дважды',
  ALREADY_SUBMITTED: 'Показания за этот период уже сданы',
}

type Collected = ReadingToSend & { index: number }

export default function ReadingsForm({ accountNumber, meters, onChanged }: Props) {
  const [form] = Form.useForm<FormValues>()
  const [messageApi, contextHolder] = message.useMessage()
  const [sending, setSending] = useState(false)

  const initialValues: FormValues = { values: meters.map(() => '') }
  // Everything is submitted for this period: nothing can be sent, so the buttons are disabled.
  const allLocked = meters.length > 0 && meters.every((meter) => meter.locked)

  // The filled fields of the meters that can still be entered, each with the meter of its card.
  const collect = (values: string[]) => {
    const readings: Collected[] = []
    values.forEach((value, index) => {
      if (isFilled(value) && meters[index] && !meters[index].locked) {
        readings.push({ meterNumber: meters[index].number, value: normalizeReading(value), index })
      }
    })
    return readings
  }

  const showServerErrors = (errors: ReadingServerError[], readings: Collected[]) => {
    let unmatched = errors.length === 0
    const fields: { name: ['values', number]; errors: string[] }[] = []
    for (const error of errors) {
      const reading = readings.find((r) => r.meterNumber === error.meterNumber)
      const text = ERROR_MESSAGES[error.code]
      if (error.code === 'ALREADY_SUBMITTED' && text) {
        // the card turns into a locked one after the reload, so the message cannot stay under its field
        messageApi.warning(text)
      } else if (reading && text) {
        fields.push({ name: ['values', reading.index], errors: [text] })
      } else {
        unmatched = true
      }
    }
    form.setFields(fields)
    if (unmatched) messageApi.error('Показания не приняты сервером')
  }

  const onFinish = async ({ values }: FormValues) => {
    const readings = collect(values ?? [])
    if (readings.length === 0) {
      messageApi.warning('Введите хотя бы одно показание')
      return
    }
    setSending(true)
    try {
      const result = await submitReadings(
        accountNumber,
        readings.map(({ meterNumber, value }) => ({ meterNumber, value })),
      )
      switch (result.status) {
        case 'ok':
          messageApi.success('Показания переданы')
          form.resetFields()
          if (!(await onChanged())) {
            messageApi.warning('Показания сохранены, но данные на экране не обновились. Обновите страницу')
          }
          break
        case 'invalid':
          showServerErrors(result.errors, readings)
          await onChanged()
          break
        case 'conflict':
          messageApi.warning('Показания не сохранены из-за одновременного изменения. Повторите отправку')
          await onChanged()
          break
        case 'not-found':
          messageApi.error('Лицевой счёт не найден')
          break
        default:
          messageApi.error('Не удалось отправить показания, попробуйте позже')
      }
    } finally {
      setSending(false)
    }
  }

  return (
    <Form form={form} layout="vertical" initialValues={initialValues} onFinish={onFinish}>
      {contextHolder}
      <Flex vertical gap={16}>
        {meters.map((meter, index) => (
          <MeterCard key={meter.number} index={index} meter={meter} />
        ))}
      </Flex>
      <Flex gap={20} wrap style={{ marginTop: 24 }}>
        <Button
          type="primary"
          htmlType="submit"
          size="large"
          loading={sending}
          disabled={allLocked}
          style={{ height: 48, flex: '1 1 240px', fontWeight: 700, fontSize: 15 }}
        >
          Отправить показания
        </Button>
        <Button
          size="large"
          disabled={allLocked}
          style={{ height: 48, flex: '1 1 240px', fontWeight: 700, fontSize: 15 }}
          onClick={() => form.resetFields()}
        >
          Сбросить форму
        </Button>
      </Flex>
    </Form>
  )
}
