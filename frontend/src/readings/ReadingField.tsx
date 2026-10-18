import { Form, Input, Typography } from 'antd'
import type { Meter } from './types'
import { formatReading, validateReading } from './validation'

interface Props {
  index: number
  meter: Meter
}

const LABELS = { HOT: 'Горячая вода, м³', COLD: 'Холодная вода, м³' } as const

export default function ReadingField({ index, meter }: Props) {
  const label = LABELS[meter.waterType]
  const previous = (
    <Typography.Text type="secondary" style={{ fontSize: 11 }}>
      Предыдущее показание: {meter.previous === undefined ? '—' : `${formatReading(meter.previous)} м³`}
    </Typography.Text>
  )

  // The reading of the current period is already submitted: it is only shown and is not a part of the form.
  if (meter.locked) {
    return (
      <Form.Item label={label} style={{ marginBottom: 0 }} extra={previous}>
        <Input size="large" aria-label={label} disabled value={formatReading(meter.submitted ?? 0)} />
      </Form.Item>
    )
  }

  return (
    <Form.Item
      name={['values', index]}
      label={label}
      style={{ marginBottom: 0 }}
      rules={[{ validator: async (_, value) => {
        const error = validateReading(value, meter.previous)
        if (error) throw new Error(error)
      } }]}
      extra={previous}
    >
      <Input size="large" inputMode="decimal" placeholder="0.000" />
    </Form.Item>
  )
}
