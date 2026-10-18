import { Button, Flex, Form, message } from 'antd'
import { useState } from 'react'
import RiserCard from './RiserCard'
import { submitReadings } from './readingsApi'
import type { Apartment, RiserValues } from './types'
import { isFilled, normalizeReading } from './validation'

interface FormValues {
  risers: RiserValues[]
}

interface Props {
  apartment: Apartment
}

export default function ReadingsForm({ apartment }: Props) {
  const [form] = Form.useForm<FormValues>()
  const [messageApi, contextHolder] = message.useMessage()
  const [sending, setSending] = useState(false)

  const initialValues: FormValues = { risers: apartment.risers.map(() => ({})) }

  const onFinish = async ({ risers }: FormValues) => {
    const values = risers.map(({ hot, cold }) => ({
      hot: isFilled(hot) ? normalizeReading(hot) : undefined,
      cold: isFilled(cold) ? normalizeReading(cold) : undefined,
    }))
    if (!values.some(({ hot, cold }) => hot !== undefined || cold !== undefined)) {
      messageApi.warning('Введите хотя бы одно показание')
      return
    }
    setSending(true)
    try {
      await submitReadings({ apartmentId: apartment.id, risers: values })
      messageApi.success('Показания переданы')
    } catch {
      messageApi.error('Не удалось отправить показания, попробуйте позже')
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
              <RiserCard key={field.key} index={field.name} riser={apartment.risers[field.name]} />
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
