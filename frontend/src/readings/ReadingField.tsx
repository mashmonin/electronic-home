import { Form, Input, Typography } from 'antd'
import type { Meter } from './types'
import { formatReading, validateReading } from './validation'

interface Props {
  name: [number, 'hot' | 'cold']
  label: string
  meter?: Meter
}

export default function ReadingField({ name, label, meter }: Props) {
  return (
    <Form.Item
      name={name}
      label={label}
      style={{ marginBottom: 0 }}
      rules={[{ validator: async (_, value) => {
        const error = validateReading(value, meter?.previous)
        if (error) throw new Error(error)
      } }]}
      extra={
        <>
          <Typography.Text type="secondary" style={{ display: 'block', fontSize: 11 }}>
            Счётчик № {meter?.number ?? '—'}
          </Typography.Text>
          <Typography.Text type="secondary" style={{ display: 'block', fontSize: 11 }}>
            Пред. показание: {meter?.previous === undefined ? '—' : `${formatReading(meter.previous)} м³`}
          </Typography.Text>
        </>
      }
    >
      <Input size="large" inputMode="decimal" placeholder="0.000" />
    </Form.Item>
  )
}
