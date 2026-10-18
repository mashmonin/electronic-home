import { Form, Input, Typography } from 'antd'
import type { Meter } from './types'
import { formatReading, validateReading } from './validation'

interface Props {
  index: number
  meter: Meter
}

const LABELS = { HOT: 'Горячая вода, м³', COLD: 'Холодная вода, м³' } as const

export default function ReadingField({ index, meter }: Props) {
  return (
    <Form.Item
      name={['values', index]}
      label={LABELS[meter.waterType]}
      style={{ marginBottom: 0 }}
      rules={[{ validator: async (_, value) => {
        const error = validateReading(value, meter.previous)
        if (error) throw new Error(error)
      } }]}
      extra={
        <Typography.Text type="secondary" style={{ fontSize: 11 }}>
          Предыдущее показание: {meter.previous === undefined ? '—' : `${formatReading(meter.previous)} м³`}
        </Typography.Text>
      }
    >
      <Input size="large" inputMode="decimal" placeholder="0.000" />
    </Form.Item>
  )
}
