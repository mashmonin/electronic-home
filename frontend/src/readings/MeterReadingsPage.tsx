import { Alert, Flex, Grid, Layout, Spin } from 'antd'
import { useCallback, useEffect, useState } from 'react'
import type { Me } from '../auth/authApi'
import AppHeader from './AppHeader'
import AllReadingsTab from './AllReadingsTab'
import ContactCard from './ContactCard'
import ReadingsForm from './ReadingsForm'
import ReceiptsTab from './ReceiptsTab'
import { type Account, fetchAccount, resolveAccountNumber } from './accountApi'
import PeriodCaption from './PeriodCaption'
import { toMeters } from './meters'
import RequestsTab from '../requests/RequestsTab'
import UploadReceiptTab from './UploadReceiptTab'
import { type Tab, allowedTab, tabFromSearch, writeTab } from './tabs'

type State =
  | { kind: 'loading' }
  | { kind: 'ready'; account: Account }
  | { kind: 'not-found' }
  | { kind: 'forbidden' }
  | { kind: 'error' }
  | { kind: 'no-account' }

interface Props {
  user: Me
  onSignOut?: () => void
  today?: Date
  search?: string
}

function AccountScreen({
  user,
  onSignOut = () => {},
  today = new Date(),
  search = window.location.search,
}: Props) {
  const [state, setState] = useState<State>({ kind: 'loading' })
  const [requestedTab, setTab] = useState<Tab>(() => tabFromSearch(search))
  // the tabs a role may not open fall back to the readings; the server refuses them too
  const tab: Tab = allowedTab(requestedTab, user.role)
  const wide = Grid.useBreakpoint().md ?? false
  const accountNumber = resolveAccountNumber(user)

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
      forbidden: 'Недостаточно прав',
      error: 'Не удалось загрузить данные',
      'no-account': 'К вашей учётной записи не привязан лицевой счёт',
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
      <AppHeader account={account} user={user} activeTab={tab} onTabChange={changeTab} onSignOut={onSignOut} />
      <Layout.Content style={{ padding: wide ? '38px 40px' : '24px 16px' }}>
        <Flex vertical gap={24} style={{ maxWidth: tab === 'requests' ? 1100 : 700, margin: '0 auto' }}>
          {tab === 'requests' ? (
            <RequestsTab user={user} />
          ) : tab === 'receipts' ? (
            <ReceiptsTab accountNumber={account.number} />
          ) : tab === 'upload' ? (
            <UploadReceiptTab />
          ) : (
            <>
              <PeriodCaption period={account.reading_period} meters={meters} />
              {!account.reading_period.enforced && (
                <Alert type="warning" showIcon message="Тестовый режим: ограничение периода передачи отключено" />
              )}
              {meters.length === 0 ? (
                <Alert type="info" showIcon message="Для этого лицевого счёта счётчики не найдены" />
              ) : (
                <ReadingsForm
                  key={account.number}
                  accountNumber={account.number}
                  meters={meters}
                  onChanged={refresh}
                  canChangeVerification={user.role !== 'RESIDENT'}
                />
              )}
              <ContactCard />
            </>
          )}
        </Flex>
      </Layout.Content>
    </Layout>
  )
}

// An administrator has no account of his own: the screen is the header and the upload tab, nothing is loaded.
function AdminScreen({ user, onSignOut, search }: { user: Me; onSignOut: () => void; search: string }) {
  const wide = Grid.useBreakpoint().md ?? false
  const [requestedTab, setTab] = useState<Tab>(() => tabFromSearch(search))
  const tab: Tab = allowedTab(requestedTab, user.role)
  const changeTab = (next: Tab) => {
    writeTab(next)
    setTab(next)
  }
  return (
    <Layout style={{ minHeight: '100vh' }}>
      <AppHeader account={null} user={user} activeTab={tab} onTabChange={changeTab} onSignOut={onSignOut} />
      <Layout.Content style={{ padding: wide ? '38px 40px' : '24px 16px' }}>
        <Flex vertical gap={24} style={{ maxWidth: tab === 'requests' ? 1100 : tab === 'all-readings' ? 900 : 700, margin: '0 auto' }}>
          {tab === 'requests' ? (
            <RequestsTab user={user} search={search} />
          ) : tab === 'all-readings' ? (
            <AllReadingsTab />
          ) : (
            <UploadReceiptTab />
          )}
        </Flex>
      </Layout.Content>
    </Layout>
  )
}

export default function MeterReadingsPage(props: Props) {
  return props.user.role === 'ADMIN' ? (
    <AdminScreen user={props.user} onSignOut={props.onSignOut ?? (() => {})} search={props.search ?? window.location.search} />
  ) : (
    <AccountScreen {...props} />
  )
}
