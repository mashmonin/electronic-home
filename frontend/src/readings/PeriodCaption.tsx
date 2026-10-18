import { Typography } from 'antd'
import type { ReadingPeriod } from './accountApi'
import { GENITIVE, NOMINATIVE } from './months'
import type { Meter } from './types'

interface Props {
  period: ReadingPeriod
  meters: Meter[]
}

// The title of the page and the status under it, aligned left. Only text: the period and whether it is submitted
// come from the server, no dates are compared here.
export default function PeriodCaption({ period, meters }: Props) {
  const month = Number(period.end.slice(5, 7)) - 1
  const day = Number(period.end.slice(8, 10))
  const lastDay = `${day} ${GENITIVE[month]}`

  let status = `Передайте показания до ${lastDay}`
  if (period.all_submitted) {
    status = `Показания сданы, новые можно будет отправить после ${lastDay}`
  } else if (meters.some((meter) => meter.locked)) {
    status = `Часть показаний сдана. Передайте остальные до ${lastDay}`
  }

  return (
    <div style={{ textAlign: 'left' }}>
      <Typography.Title level={1} style={{ fontSize: 26, margin: 0 }}>
        Показания за {NOMINATIVE[month]}
      </Typography.Title>
      <Typography.Text strong style={{ display: 'block', fontSize: 15 }}>
        {status}
      </Typography.Text>
    </div>
  )
}
