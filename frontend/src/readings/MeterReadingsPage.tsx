import { Alert, Flex, Grid, Layout, Spin, Typography } from 'antd'
import { useCallback, useEffect, useState } from 'react'
import AppHeader from './AppHeader'
import ContactCard from './ContactCard'
import ReadingsForm from './ReadingsForm'
import { type Account, fetchAccount, resolveAccountNumber } from './accountApi'
import { toMeters } from './meters'

const MONTHS = [
  'Январь', 'Февраль', 'Март', 'Апрель', 'Май', 'Июнь',
  'Июль', 'Август', 'Сентябрь', 'Октябрь', 'Ноябрь', 'Декабрь',
]
const DEADLINE_DAY = 25

type State =
  | { kind: 'loading' }
  | { kind: 'ready'; account: Account }
  | { kind: 'not-found' }
  | { kind: 'error' }
  | { kind: 'no-account' }

interface Props {
  today?: Date
  search?: string
  defaultAccountNumber?: string
}

export default function MeterReadingsPage({
  today = new Date(),
  search = window.location.search,
  defaultAccountNumber = import.meta.env.VITE_DEFAULT_ACCOUNT_NUMBER,
}: Props) {
  const [state, setState] = useState<State>({ kind: 'loading' })
  const wide = Grid.useBreakpoint().md ?? false
  const accountNumber = resolveAccountNumber(search, defaultAccountNumber)

  useEffect(() => {
    if (!accountNumber) {
      setState({ kind: 'no-account' })
      return
    }
    let cancelled = false
    setState({ kind: 'loading' })
    fetchAccount(accountNumber).then((result) => {
      if (cancelled) return
      if (result.status === 'ok') setState({ kind: 'ready', account: result.account })
      else setState({ kind: result.status })
    })
    return () => {
      cancelled = true
    }
  }, [accountNumber])

  // Reloads the account in place (no spinner, the form stays mounted); false when the reload failed.
  const refresh = useCallback(async () => {
    if (!accountNumber) return false
    const result = await fetchAccount(accountNumber)
    if (result.status !== 'ok') return false
    setState({ kind: 'ready', account: result.account })
    return true
  }, [accountNumber])

  if (state.kind === 'loading') {
    return <Spin style={{ display: 'block', margin: '120px auto' }} />
  }

  if (state.kind !== 'ready') {
    const message = {
      'not-found': 'Лицевой счёт не найден',
      error: 'Не удалось загрузить данные',
      'no-account': 'Лицевой счёт не указан. Добавьте в адрес параметр ?account=номер',
    }[state.kind]
    return (
      <Layout style={{ minHeight: '100vh' }}>
        <Layout.Content style={{ padding: wide ? '38px 40px' : '24px 16px' }}>
          <Alert type={state.kind === 'error' ? 'error' : 'warning'} showIcon message={message} style={{ maxWidth: 700, margin: '0 auto' }} />
        </Layout.Content>
      </Layout>
    )
  }

  const { account } = state
  const meters = toMeters(account.meters, today)
  return (
    <Layout style={{ minHeight: '100vh' }}>
      <AppHeader account={account} />
      <Layout.Content style={{ padding: wide ? '38px 40px' : '24px 16px' }}>
        <Flex vertical gap={24} style={{ maxWidth: 700, margin: '0 auto' }}>
          <div style={{ textAlign: 'center' }}>
            <Typography.Title level={1} style={{ fontSize: 26, margin: 0 }}>
              Ввод показаний
            </Typography.Title>
            <Typography.Text type="secondary" style={{ fontSize: 13 }}>
              {MONTHS[today.getMonth()]} {today.getFullYear()} · Передайте показания до {DEADLINE_DAY} числа
            </Typography.Text>
          </div>
          {meters.length === 0 ? (
            <Alert type="info" showIcon message="Для этого лицевого счёта счётчики не найдены" />
          ) : (
            <ReadingsForm key={account.number} accountNumber={account.number} meters={meters} onChanged={refresh} />
          )}
          <ContactCard />
        </Flex>
      </Layout.Content>
    </Layout>
  )
}
