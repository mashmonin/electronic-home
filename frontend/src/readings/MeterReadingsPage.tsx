import { Alert, Flex, Grid, Layout, Spin } from 'antd'
import { useCallback, useEffect, useState } from 'react'
import AppHeader from './AppHeader'
import ContactCard from './ContactCard'
import ReadingsForm from './ReadingsForm'
import ReceiptsTab from './ReceiptsTab'
import { type Account, fetchAccount, resolveAccountNumber } from './accountApi'
import PeriodCaption from './PeriodCaption'
import { toMeters } from './meters'
import { type Tab, tabFromSearch, writeTab } from './tabs'

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
  const [tab, setTab] = useState<Tab>(() => tabFromSearch(search))
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

  // The tab is part of the address, so a link or a reload keeps it; the other parameters (account) stay.
  const changeTab = (next: Tab) => {
    writeTab(next)
    setTab(next)
  }

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
      <AppHeader account={account} activeTab={tab} onTabChange={changeTab} />
      <Layout.Content style={{ padding: wide ? '38px 40px' : '24px 16px' }}>
        <Flex vertical gap={24} style={{ maxWidth: 700, margin: '0 auto' }}>
          {tab === 'receipts' ? (
            <ReceiptsTab accountNumber={account.number} />
          ) : (
            <>
              <PeriodCaption period={account.reading_period} meters={meters} />
              {!account.reading_period.enforced && (
                <Alert type="warning" showIcon message="Тестовый режим: ограничение периода передачи отключено" />
              )}
              {meters.length === 0 ? (
                <Alert type="info" showIcon message="Для этого лицевого счёта счётчики не найдены" />
              ) : (
                <ReadingsForm key={account.number} accountNumber={account.number} meters={meters} onChanged={refresh} />
              )}
              <ContactCard />
            </>
          )}
        </Flex>
      </Layout.Content>
    </Layout>
  )
}
