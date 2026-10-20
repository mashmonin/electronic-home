import { SearchOutlined } from '@ant-design/icons'
import { Alert, Button, Collapse, Divider, Empty, Flex, Input, Spin, Table, Tag, Typography } from 'antd'
import { useEffect, useState } from 'react'
import { NOMINATIVE } from './months'
import {
  type OverviewApartment,
  type OverviewMeter,
  type OverviewMonth,
  type OverviewPeriod,
  fetchOverviewMonth,
  fetchPeriods,
} from './readingsOverviewApi'

const NOT_HANDED_IN = 'Не передано'

// 245.3 -> "245.3", 230 -> "230": up to three digits after the point and no fraction when there is none.
const cubic = (value: number) => String(Number(value.toFixed(3)))

// "2026-10" -> "Октябрь": the year is in the divider over the months.
function monthName(month: string): string {
  const name = NOMINATIVE[Number(month.slice(5, 7)) - 1] ?? month
  return `${name.charAt(0).toUpperCase()}${name.slice(1)}`
}

const WATER: Record<OverviewMeter['water_type'], string> = { HOT: 'Горячая', COLD: 'Холодная' }

// "Сдано 2 из 2" (green), "Сдано 1 из 2" (yellow) or "Не передано" (red).
function StatusTag({ apartment }: { apartment: OverviewApartment }) {
  if (apartment.submitted === 0) return <Tag color="red">{NOT_HANDED_IN}</Tag>
  const all = apartment.submitted === apartment.total
  return <Tag color={all ? 'green' : 'gold'}>{`Сдано ${apartment.submitted} из ${apartment.total}`}</Tag>
}

const COLUMNS = [
  { title: 'Счётчик', dataIndex: 'number', key: 'number' },
  { title: 'Вода', key: 'water', render: (_: unknown, meter: OverviewMeter) => WATER[meter.water_type] },
  {
    title: 'Показание, м³',
    key: 'value',
    render: (_: unknown, meter: OverviewMeter) => (meter.reading ? cubic(meter.reading.value) : <Typography.Text type="danger">{NOT_HANDED_IN}</Typography.Text>),
  },
  {
    title: 'Предыдущее, м³',
    key: 'previous',
    render: (_: unknown, meter: OverviewMeter) => (meter.reading && meter.reading.previous_value !== null ? cubic(meter.reading.previous_value) : '—'),
  },
  {
    title: 'Расход, м³',
    key: 'consumption',
    render: (_: unknown, meter: OverviewMeter) => (meter.reading && meter.reading.consumption !== null ? cubic(meter.reading.consumption) : '—'),
  },
]

type MonthState = { kind: 'loading' } | { kind: 'ready'; month: OverviewMonth } | { kind: 'error' }

// The panel of a month: it is mounted when the month is opened for the first time and keeps what it loaded.
function MonthPanel({ month, query }: { month: string; query: string }) {
  const [state, setState] = useState<MonthState>({ kind: 'loading' })
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let cancelled = false
    setState({ kind: 'loading' })
    fetchOverviewMonth(month).then((result) => {
      if (cancelled) return
      setState(result.status === 'ok' ? { kind: 'ready', month: result.month } : { kind: 'error' })
    })
    return () => {
      cancelled = true
    }
  }, [month, attempt])

  if (state.kind === 'loading') return <Spin style={{ display: 'block', margin: '16px auto' }} />
  if (state.kind === 'error') {
    return (
      <Alert
        type="error"
        showIcon
        message="Не удалось загрузить месяц"
        action={
          <Button size="small" onClick={() => setAttempt((n) => n + 1)}>
            Повторить
          </Button>
        }
      />
    )
  }
  const apartments = state.month.apartments
  if (apartments.length === 0) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="Нет квартир со счётчиками" />
  const found = query === '' ? apartments : apartments.filter((item) => item.apartment.includes(query))
  if (found.length === 0) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={`Ничего не найдено по запросу «${query}»`} />
  }
  return (
    <Collapse
      size="small"
      items={found.map((item) => ({
        key: item.address,
        label: (
          <Flex justify="space-between" align="center" gap={8}>
            <Typography.Text strong>{`кв. ${item.apartment}`}</Typography.Text>
            <StatusTag apartment={item} />
          </Flex>
        ),
        children: <Table size="small" pagination={false} rowKey="number" columns={COLUMNS} dataSource={item.meters} />,
      }))}
    />
  )
}

// The months (newest first) in groups by the year they end in, in the same order: [["2026", [...]], ["2025", [...]]].
function byYear(periods: OverviewPeriod[]): [string, OverviewPeriod[]][] {
  const groups: [string, OverviewPeriod[]][] = []
  for (const period of periods) {
    const year = period.month.slice(0, 4)
    const last = groups[groups.length - 1]
    if (last && last[0] === year) last[1].push(period)
    else groups.push([year, [period]])
  }
  return groups
}

type Periods = { kind: 'loading' } | { kind: 'ready'; periods: OverviewPeriod[] } | { kind: 'error' }

// The readings of all the apartments for the administrator: an accordion of the months and inside it one of the apartments.
export default function AllReadingsTab() {
  const [periods, setPeriods] = useState<Periods>({ kind: 'loading' })
  const [attempt, setAttempt] = useState(0)
  const [typed, setTyped] = useState('')

  useEffect(() => {
    let cancelled = false
    setPeriods({ kind: 'loading' })
    fetchPeriods().then((result) => {
      if (cancelled) return
      setPeriods(result.status === 'ok' ? { kind: 'ready', periods: result.periods } : { kind: 'error' })
    })
    return () => {
      cancelled = true
    }
  }, [attempt])

  const query = typed.trim()

  return (
    <Flex vertical gap={24}>
      <Typography.Title level={1} style={{ fontSize: 26, margin: 0, textAlign: 'left' }}>
        Все показания
      </Typography.Title>
      {periods.kind === 'loading' && <Spin style={{ display: 'block', margin: '40px auto' }} />}
      {periods.kind === 'error' && (
        <Alert
          type="error"
          showIcon
          message="Не удалось загрузить показания"
          action={
            <Button size="small" onClick={() => setAttempt((n) => n + 1)}>
              Повторить
            </Button>
          }
        />
      )}
      {periods.kind === 'ready' && (
        <>
          <Input
            allowClear
            aria-label="Поиск по квартире"
            placeholder="Поиск по квартире"
            prefix={<SearchOutlined style={{ color: '#8c8c8c' }} />}
            value={typed}
            onChange={(event) => setTyped(event.target.value)}
          />
          {byYear(periods.periods).map(([year, months]) => (
            <Flex vertical key={year}>
              <Divider orientation="left" style={{ margin: '0 0 12px', fontWeight: 600 }}>
                {year}
              </Divider>
              <Collapse
                items={months.map((period) => ({
                  key: period.month,
                  label: monthName(period.month),
                  children: <MonthPanel month={period.month} query={query} />,
                }))}
              />
            </Flex>
          ))}
        </>
      )}
    </Flex>
  )
}
