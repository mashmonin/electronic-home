import { Button, Flex, Form, message } from 'antd'
import { useState } from 'react'
import RiserCard from './RiserCard'
import { submitReadings } from './readingsApi'
import type { ReadingServerError, ReadingToSend, Riser, RiserValues } from './types'
import { isFilled, normalizeReading } from './validation'

interface FormValues {
  risers: RiserValues[]
}

interface Props {
  accountNumber: string
  risers: Riser[]
  /** Reloads the account after the server answered; resolves to false when the reload failed. */
  onChanged: () => Promise<boolean>
}

type Kind = 'hot' | 'cold'

const ERROR_MESSAGES: Record<string, string> = {
  VALUE_LESS_THAN_PREVIOUS: 'Показание не может быть меньше предыдущего',
  INVALID_VALUE: 'Введите число, не более трёх знаков после запятой',
  METER_NOT_FOUND: 'Счётчик не найден',
  DUPLICATE_METER: 'Счётчик указан дважды',
}

export default function ReadingsForm({ accountNumber, risers: knownRisers, onChanged }: Props) {
  const [form] = Form.useForm<FormValues>()
  const [messageApi, contextHolder] = message.useMessage()
  const [sending, setSending] = useState(false)

  const initialValues: FormValues = { risers: knownRisers.map(() => ({})) }

  // The filled fields that have a meter, each with the form field it came from.
  const collect = (risers: RiserValues[]) => {
    const readings: (ReadingToSend & { field: [number, Kind] })[] = []
    risers.forEach((values, index) => {
      for (const kind of ['hot', 'cold'] as const) {
        const value = values?.[kind]
        const meter = knownRisers[index]?.[kind]
        if (isFilled(value) && meter) {
          readings.push({ meterNumber: meter.number, value: normalizeReading(value), field: [index, kind] })
        }
      }
    })
    return readings
  }

  const showServerErrors = (errors: ReadingServerError[], readings: ReturnType<typeof collect>) => {
    let unmatched = errors.length === 0
    const fields: { name: ['risers', number, Kind]; errors: string[] }[] = []
    for (const error of errors) {
      const reading = readings.find((r) => r.meterNumber === error.meterNumber)
      const text = ERROR_MESSAGES[error.code]
      if (reading && text) {
        fields.push({ name: ['risers', reading.field[0], reading.field[1]], errors: [text] })
      } else {
        unmatched = true
      }
    }
    form.setFields(fields)
    if (unmatched) messageApi.error('Показания не приняты сервером')
  }

  const onFinish = async ({ risers }: FormValues) => {
    const readings = collect(risers)
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
      <Form.List name="risers">
        {(fields, { add }) => (
          <Flex vertical gap={16}>
            {fields.map((field) => (
              <RiserCard key={field.key} index={field.name} riser={knownRisers[field.name]} />
            ))}
            <Button
              size="large"
              color="primary"
              variant="outlined"
              block
              style={{ height: 48, fontWeight: 700 }}
              onClick={() => add({})}
            >
              + Добавить стояк
            </Button>
          </Flex>
        )}
      </Form.List>
      <Flex gap={20} wrap style={{ marginTop: 24 }}>
        <Button
          type="primary"
          htmlType="submit"
          size="large"
          loading={sending}
          style={{ height: 48, flex: '1 1 240px', fontWeight: 700, fontSize: 15 }}
        >
          Отправить показания
        </Button>
        <Button
          size="large"
          style={{ height: 48, flex: '1 1 240px', fontWeight: 700, fontSize: 15 }}
          onClick={() => form.resetFields()}
        >
          Сбросить форму
        </Button>
      </Flex>
    </Form>
  )
}
