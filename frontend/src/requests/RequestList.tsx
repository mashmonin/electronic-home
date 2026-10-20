import { Flex, Tag, Typography } from 'antd'
import { formatTime, ownerLabel } from './format'
import type { RequestItem } from './requestsApi'
import { STATUS_COLORS, STATUS_LABELS } from './status'

interface Props {
  requests: RequestItem[]
  selectedId: number | null
  /** An administrator also sees whose request it is. */
  showOwner: boolean
  onSelect: (id: number) => void
}

// The accent colour of the site (the active item of the navigation).
const ACCENT = '#ffb370'

// A row waits for an answer: a warm background and a stripe on the left; the chosen row is darker.
function rowStyle(awaiting: boolean, selected: boolean): React.CSSProperties {
  return {
    cursor: 'pointer',
    padding: '12px 16px',
    borderBottom: '1px solid #f0f0f0',
    borderLeft: `4px solid ${awaiting ? ACCENT : 'transparent'}`,
    background: awaiting ? (selected ? '#ffe3c7' : '#fff4e8') : selected ? '#f0f0f0' : undefined,
  }
}

// The requests in the order of the API (those without an answer come first): the number, the beginning of the last
// message and its time; a request that waits for an answer is marked.
export default function RequestList({ requests, selectedId, showOwner, onSelect }: Props) {
  return (
    <Flex vertical>
      {requests.map((request) => {
        const selected = request.id === selectedId
        return (
          <div
            key={request.id}
            role="button"
            tabIndex={0}
            aria-current={selected ? 'true' : undefined}
            data-testid={`request-${request.id}`}
            data-awaiting={request.awaiting_reply ? 'true' : 'false'}
            onClick={() => onSelect(request.id)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault()
                onSelect(request.id)
              }
            }}
            style={rowStyle(request.awaiting_reply, selected)}
          >
            <Flex justify="space-between" align="baseline" gap={8}>
              <Typography.Text strong ellipsis style={{ flex: 1 }}>
                {request.number}
              </Typography.Text>
              <Tag color={STATUS_COLORS[request.status]} style={{ marginInlineEnd: 0 }}>
                {STATUS_LABELS[request.status]}
              </Tag>
            </Flex>
            {showOwner && (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {ownerLabel(request)}
              </Typography.Text>
            )}
            <Typography.Paragraph type="secondary" ellipsis={{ rows: 1 }} style={{ margin: '2px 0' }}>
              {request.last_message.text}
            </Typography.Paragraph>
            <Typography.Text type="secondary" style={{ fontSize: 11 }}>
              {formatTime(request.last_message_at)}
            </Typography.Text>
          </div>
        )
      })}
    </Flex>
  )
}
