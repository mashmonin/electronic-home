import { ArrowLeftOutlined } from '@ant-design/icons'
import { Alert, Button, Flex, Input, Spin, Tag, Typography } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'
import type { Me } from '../auth/authApi'
import AttachmentList from './AttachmentList'
import AttachmentPicker from './AttachmentPicker'
import { authorLabel, formatTime, ownerLabel } from './format'
import { type ChatMessage, type RequestItem, type SendResult, changeRequestStatus, fetchMessages, sendMessage } from './requestsApi'
import { STATUS_COLORS, STATUS_LABELS, nextStep } from './status'
import { usePolling } from './usePolling'

export const MESSAGE_LIMIT = 2000
const POLL_MS = 5000

type Messages = { kind: 'loading' } | { kind: 'ready'; messages: ChatMessage[] } | { kind: 'error' }

interface Props {
  request: RequestItem
  user: Me
  /** On a narrow screen the chat is alone and needs a way back to the list. */
  narrow: boolean
  onBack: () => void
  /** The list should be reloaded: a message was sent, so the order of the requests may have changed. */
  onActivity: () => void
}

const SEND_FAILURES: Record<Exclude<SendResult['status'], 'ok'>, string> = {
  'too-large': 'Файл слишком большой или вложения превышают допустимый размер',
  unsupported: 'Тип файла не поддерживается',
  'not-found': 'Заявка недоступна',
  forbidden: 'Недостаточно прав',
  closed: 'Заявка закрыта, писать в неё нельзя',
  invalid: 'Не удалось отправить сообщение. Попробуйте позже',
  error: 'Не удалось отправить сообщение. Попробуйте позже',
}

// Adds the new messages to the old ones without repeating any (a message may come from the poll and from the sending).
function merge(old: ChatMessage[], added: ChatMessage[]): ChatMessage[] {
  const known = new Set(old.map((message) => message.id))
  const fresh = added.filter((message) => !known.has(message.id))
  return fresh.length === 0 ? old : [...old, ...fresh].sort((a, b) => a.id - b.id)
}

// The chat of a request: the header, the messages and, while the request is open, the field to write in.
export default function ChatPanel({ request: listed, user, narrow, onBack, onActivity }: Props) {
  const admin = user.role === 'ADMIN'
  // the request as the chat knows it: a step of the status shows at once, the list catches up with the next reload
  const [request, setRequest] = useState(listed)
  const [stepping, setStepping] = useState(false)
  const [stepFailure, setStepFailure] = useState<string | null>(null)
  useEffect(() => setRequest(listed), [listed])
  const closed = request.status === 'CLOSED'
  const step = admin ? nextStep(request) : null
  const [messages, setMessages] = useState<Messages>({ kind: 'loading' })
  const [attempt, setAttempt] = useState(0)
  const [text, setText] = useState('')
  const [files, setFiles] = useState<File[]>([])
  const [sending, setSending] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const scroller = useRef<HTMLDivElement>(null)
  // Whether the reader is at the end of the feed: only then a new message scrolls it down.
  const stickToEnd = useRef(true)
  const latestId = useRef(0)

  useEffect(() => {
    let cancelled = false
    setMessages({ kind: 'loading' })
    stickToEnd.current = true
    fetchMessages(request.id).then((result) => {
      if (cancelled) return
      setMessages(result.status === 'ok' ? { kind: 'ready', messages: result.messages } : { kind: 'error' })
    })
    return () => {
      cancelled = true
    }
  }, [request.id, attempt])

  useEffect(() => {
    if (messages.kind === 'ready') latestId.current = messages.messages.at(-1)?.id ?? 0
    const feed = scroller.current
    if (feed && stickToEnd.current) feed.scrollTop = feed.scrollHeight
  }, [messages])

  const append = useCallback((added: ChatMessage[]) => {
    const feed = scroller.current
    if (feed) stickToEnd.current ||= feed.scrollHeight - feed.scrollTop - feed.clientHeight < 60
    setMessages((previous) => (previous.kind === 'ready' ? { kind: 'ready', messages: merge(previous.messages, added) } : previous))
  }, [])

  const poll = useCallback(async () => {
    const result = await fetchMessages(request.id, latestId.current)
    if (result.status === 'ok' && result.messages.length > 0) {
      const feed = scroller.current
      stickToEnd.current = feed ? feed.scrollHeight - feed.scrollTop - feed.clientHeight < 60 : true
      append(result.messages)
    }
  }, [request.id, append])

  usePolling(() => void poll(), POLL_MS, messages.kind === 'ready')

  const trimmed = text.trim()
  const tooLong = trimmed.length > MESSAGE_LIMIT
  const canSend = (trimmed.length > 0 || files.length > 0) && !tooLong && !sending && !closed

  const makeStep = async () => {
    if (!step || stepping) return
    setStepping(true)
    setStepFailure(null)
    const result = await changeRequestStatus(request.id, step.status)
    setStepping(false)
    if (result.status === 'ok') {
      setRequest(result.request)
    } else {
      setStepFailure(
        result.status === 'conflict' ? 'Статус заявки уже изменён или шаг невозможен' : 'Не удалось изменить статус. Попробуйте позже',
      )
    }
    // after a failure too: the list shows the status the server has
    onActivity()
  }

  const send = async () => {
    if (!canSend) return
    setSending(true)
    setFailure(null)
    const result = await sendMessage(request.id, trimmed, files)
    setSending(false)
    if (result.status === 'ok') {
      stickToEnd.current = true
      append([result.message])
      setText('')
      setFiles([])
      onActivity()
    } else {
      setFailure(SEND_FAILURES[result.status])
    }
  }

  return (
    <Flex vertical style={{ height: '100%' }}>
      <Flex vertical gap={4} style={{ padding: '12px 16px', borderBottom: '1px solid #f0f0f0' }}>
        <Flex align="center" gap={8} wrap>
          {narrow && (
            <Button type="text" icon={<ArrowLeftOutlined />} onClick={onBack} style={{ padding: 0 }}>
              К списку
            </Button>
          )}
          <Typography.Text strong style={{ fontSize: 16, flex: 1, minWidth: 0 }} ellipsis>
            {request.number}
          </Typography.Text>
          <Tag color={STATUS_COLORS[request.status]} style={{ marginInlineEnd: 0 }}>
            {STATUS_LABELS[request.status]}
          </Tag>
          {step && (
            <Button type="primary" size="small" loading={stepping} onClick={() => void makeStep()}>
              {step.label}
            </Button>
          )}
        </Flex>
        {admin && <Typography.Text type="secondary">{ownerLabel(request)}</Typography.Text>}
        {stepFailure && <Alert type="error" showIcon message={stepFailure} />}
      </Flex>

      <div ref={scroller} data-testid="chat-feed" style={{ flex: 1, overflowY: 'auto', padding: 16, minHeight: 0 }}>
        {messages.kind === 'loading' && <Spin style={{ display: 'block', margin: '40px auto' }} />}
        {messages.kind === 'error' && (
          <Alert
            type="error"
            showIcon
            message="Не удалось загрузить сообщения"
            action={
              <Button size="small" onClick={() => setAttempt((n) => n + 1)}>
                Повторить
              </Button>
            }
          />
        )}
        {messages.kind === 'ready' && (
          <Flex vertical gap={12}>
            {messages.messages.map((message) => (
              <Flex key={message.id} vertical align={message.mine ? 'flex-end' : 'flex-start'}>
                <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                  {authorLabel(message)} · {formatTime(message.created_at)}
                </Typography.Text>
                <div
                  style={{
                    maxWidth: '85%',
                    padding: '8px 12px',
                    borderRadius: 12,
                    background: message.mine ? '#fff7e6' : '#f5f5f5',
                    whiteSpace: 'pre-wrap',
                    overflowWrap: 'anywhere',
                  }}
                >
                  {message.text}
                  <AttachmentList requestId={request.id} attachments={message.attachments ?? []} />
                </div>
              </Flex>
            ))}
          </Flex>
        )}
      </div>

      <div style={{ padding: 16, borderTop: '1px solid #f0f0f0' }}>
        <Flex vertical gap={8}>
          {closed && <Alert type="info" showIcon message="Заявка закрыта" />}
          {failure && <Alert type="error" showIcon message={failure} />}
          <Input.TextArea
            disabled={closed}
            aria-label="Сообщение"
            placeholder="Введите сообщение"
            autoSize={{ minRows: 2, maxRows: 6 }}
            value={text}
            status={tooLong ? 'error' : undefined}
            onChange={(event) => setText(event.target.value)}
            onKeyDown={(event) => {
              if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
                event.preventDefault()
                void send()
              }
            }}
          />
          {tooLong && <Typography.Text type="danger">Сообщение длиннее {MESSAGE_LIMIT} символов</Typography.Text>}
          <AttachmentPicker files={files} onChange={setFiles} disabled={sending || closed} />
          <Flex justify="flex-end">
            <Button type="primary" disabled={!canSend} loading={sending} onClick={() => void send()}>
              Отправить
            </Button>
          </Flex>
        </Flex>
      </div>
    </Flex>
  )
}
