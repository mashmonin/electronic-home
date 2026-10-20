import { SearchOutlined } from '@ant-design/icons'
import { Alert, Button, Empty, Flex, Grid, Input, Pagination, Spin, Typography } from 'antd'
import { useCallback, useEffect, useState } from 'react'
import type { Me } from '../auth/authApi'
import { requestFromSearch, writeRequest } from '../readings/tabs'
import ChatPanel from './ChatPanel'
import NewRequestModal from './NewRequestModal'
import RequestList from './RequestList'
import { type RequestItem, fetchRequests } from './requestsApi'
import { usePolling } from './usePolling'

const LIST_POLL_MS = 15000
// How long the typing may pause before the search is sent: one request for a word, not for every letter.
const SEARCH_DELAY_MS = 300

type List = { kind: 'loading' } | { kind: 'ready'; requests: RequestItem[]; total: number; pageSize: number } | { kind: 'error' }

interface Props {
  user: Me
  search?: string
}

const PANEL: React.CSSProperties = {
  border: '1px solid #f0f0f0',
  borderRadius: 8,
  background: '#fff',
  height: 'calc(100vh - 280px)',
  minHeight: 460,
  overflow: 'hidden',
}

// The requests: the list of them on the left (those without an answer first), the chat of the chosen one on the right
// (one at a time on a narrow screen).
export default function RequestsTab({ user, search = window.location.search }: Props) {
  const wide = Grid.useBreakpoint().md ?? false
  const admin = user.role === 'ADMIN'
  // what is typed in the field, and what the list was asked for (it follows the typing after a pause)
  const [typed, setTyped] = useState('')
  const [fragment, setFragment] = useState('')
  const [list, setList] = useState<List>({ kind: 'loading' })
  const [attempt, setAttempt] = useState(0)
  // the page of the list (from 1); a new search or a new request brings the list back to the first one
  const [page, setPage] = useState(1)
  const [selectedId, setSelectedId] = useState<number | null>(() => requestFromSearch(search))
  const [creating, setCreating] = useState(false)
  // the chosen request stays open in the chat while a search leaves it out of the list
  const [pinned, setPinned] = useState<RequestItem | null>(null)

  useEffect(() => {
    if (typed.trim() === fragment) return
    // an emptied field shows the whole list at once, a word is sent after the pause
    const timer = setTimeout(() => {
      setFragment(typed.trim())
      setPage(1)
    }, typed.trim() === '' ? 0 : SEARCH_DELAY_MS)
    return () => clearTimeout(timer)
  }, [typed, fragment])

  useEffect(() => {
    let cancelled = false
    setList({ kind: 'loading' })
    fetchRequests(fragment, page).then((result) => {
      if (cancelled) return
      setList(
        result.status === 'ok'
          ? { kind: 'ready', requests: result.requests, total: result.total, pageSize: result.pageSize }
          : { kind: 'error' },
      )
    })
    return () => {
      cancelled = true
    }
  }, [fragment, attempt, page])

  // Reloads the list in place: no spinner, the chosen request and the typed text stay; a failure is skipped.
  const refresh = useCallback(async () => {
    const result = await fetchRequests(fragment, page)
    if (result.status === 'ok') setList({ kind: 'ready', requests: result.requests, total: result.total, pageSize: result.pageSize })
  }, [fragment, page])

  usePolling(() => void refresh(), LIST_POLL_MS, list.kind === 'ready')

  const select = (id: number | null) => {
    setSelectedId(id)
    writeRequest(id)
  }

  const requests = list.kind === 'ready' ? list.requests : []

  // the page disappeared (requests were gone): go to the last one that is left
  useEffect(() => {
    if (list.kind !== 'ready' || list.requests.length > 0 || list.total === 0 || page <= 1) return
    setPage(Math.max(1, Math.ceil(list.total / list.pageSize)))
  }, [list, page])
  const listed = requests.find((request) => request.id === selectedId) ?? null
  const selected = listed ?? (pinned !== null && pinned.id === selectedId ? pinned : null)

  useEffect(() => {
    if (listed) setPinned(listed)
  }, [listed])
  const showList = wide || selected === null
  const showChat = wide || selected !== null

  // A new request waits for an answer, so the server puts it among those; the list is asked again for the right place.
  const created = (request: RequestItem) => {
    setCreating(false)
    setTyped('')
    setFragment('')
    setPage(1)
    select(request.id)
    setPinned(request)
    void fetchRequests().then((result) => {
      if (result.status === 'ok') setList({ kind: 'ready', requests: result.requests, total: result.total, pageSize: result.pageSize })
    })
  }

  return (
    <Flex vertical gap={16}>
      <Flex justify="space-between" align="center" gap={16} wrap>
        <Typography.Title level={1} style={{ fontSize: 26, margin: 0, textAlign: 'left' }}>
          Заявки
        </Typography.Title>
        {!admin && (
          <Button type="primary" onClick={() => setCreating(true)}>
            Новая заявка
          </Button>
        )}
      </Flex>
      <Flex gap={16} align="stretch">
        {showList && (
          <Flex vertical style={{ ...PANEL, flex: wide ? '0 0 33%' : '1 1 auto', minWidth: wide ? 280 : 0 }}>
            <div style={{ padding: 16, borderBottom: '1px solid #f0f0f0' }}>
              <Input
                allowClear
                aria-label="Поиск по номеру"
                placeholder="Поиск по номеру заявки"
                prefix={<SearchOutlined style={{ color: '#8c8c8c' }} />}
                value={typed}
                onChange={(event) => setTyped(event.target.value)}
              />
            </div>
            <div style={{ flex: 1, overflowY: 'auto', minHeight: 0 }}>
              {list.kind === 'loading' && <Spin style={{ display: 'block', margin: '40px auto' }} />}
              {list.kind === 'error' && (
                <Alert
                  type="error"
                  showIcon
                  message="Не удалось загрузить заявки"
                  style={{ margin: 16 }}
                  action={
                    <Button size="small" onClick={() => setAttempt((n) => n + 1)}>
                      Повторить
                    </Button>
                  }
                />
              )}
              {list.kind === 'ready' && requests.length === 0 && fragment !== '' && (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={`Ничего не найдено по запросу «${fragment}»`}
                  style={{ margin: '40px 16px' }}
                />
              )}
              {list.kind === 'ready' && requests.length === 0 && fragment === '' && (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={admin ? 'Заявок пока нет' : 'Заявок пока нет. Нажмите «Новая заявка», чтобы обратиться в ТСЖ'}
                  style={{ margin: '40px 16px' }}
                />
              )}
              {list.kind === 'ready' && requests.length > 0 && (
                <RequestList requests={requests} selectedId={selected?.id ?? null} showOwner={admin} onSelect={select} />
              )}
            </div>
            {list.kind === 'ready' && list.total > list.pageSize && (
              <Flex justify="center" style={{ padding: 8, borderTop: '1px solid #f0f0f0' }}>
                <Pagination
                  size="small"
                  simple
                  current={page}
                  total={list.total}
                  pageSize={list.pageSize}
                  showSizeChanger={false}
                  onChange={setPage}
                />
              </Flex>
            )}
          </Flex>
        )}

        {showChat && (
          <div style={{ ...PANEL, flex: '1 1 0', minWidth: 0 }}>
            {selected ? (
              <ChatPanel
                key={selected.id}
                request={selected}
                user={user}
                narrow={!wide}
                onBack={() => select(null)}
                onActivity={() => void refresh()}
              />
            ) : (
              <Flex align="center" justify="center" style={{ height: '100%' }}>
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="Выберите заявку" />
              </Flex>
            )}
          </div>
        )}
      </Flex>

      {!admin && (
        <NewRequestModal open={creating} accounts={user.accounts} onCancel={() => setCreating(false)} onCreated={created} />
      )}
    </Flex>
  )
}
