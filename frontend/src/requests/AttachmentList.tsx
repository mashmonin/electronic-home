import { FileOutlined, FilePdfOutlined, FileImageOutlined, FileTextOutlined, FileExcelOutlined, FileWordOutlined, LoadingOutlined } from '@ant-design/icons'
import { Alert, Flex, Typography } from 'antd'
import { useState } from 'react'
import { saveFile } from '../download'
import { formatSize } from './attachments'
import { type Attachment, downloadAttachment } from './requestsApi'

interface Props {
  requestId: number
  attachments: Attachment[]
}

function iconOf(type: string) {
  if (type.startsWith('image/')) return <FileImageOutlined />
  if (type === 'application/pdf') return <FilePdfOutlined />
  if (type.includes('wordprocessing') || type === 'application/msword') return <FileWordOutlined />
  if (type.includes('spreadsheet') || type === 'application/vnd.ms-excel') return <FileExcelOutlined />
  if (type.startsWith('text/')) return <FileTextOutlined />
  return <FileOutlined />
}

// The files of a message: the icon, the name and the size; a click downloads the file (with the token of the session).
export default function AttachmentList({ requestId, attachments }: Props) {
  const [loading, setLoading] = useState<number | null>(null)
  const [failed, setFailed] = useState(false)

  const download = async (attachment: Attachment) => {
    setLoading(attachment.id)
    setFailed(false)
    const result = await downloadAttachment(requestId, attachment.id)
    setLoading(null)
    if (result.status === 'ok') saveFile(result.blob, attachment.name)
    else setFailed(true)
  }

  if (attachments.length === 0) return null
  return (
    <Flex vertical gap={4} style={{ marginTop: 6 }}>
      {attachments.map((attachment) => (
        <Flex
          key={attachment.id}
          role="button"
          tabIndex={0}
          aria-label={`Скачать файл «${attachment.name}»`}
          data-testid={`attachment-${attachment.id}`}
          align="center"
          gap={8}
          onClick={() => void download(attachment)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' || event.key === ' ') {
              event.preventDefault()
              void download(attachment)
            }
          }}
          style={{ cursor: 'pointer', padding: '4px 8px', borderRadius: 8, background: 'rgba(0, 0, 0, 0.05)' }}
        >
          {loading === attachment.id ? <LoadingOutlined /> : iconOf(attachment.content_type)}
          <Typography.Text ellipsis style={{ flex: 1, minWidth: 0 }}>
            {attachment.name}
          </Typography.Text>
          <Typography.Text type="secondary" style={{ fontSize: 12, whiteSpace: 'nowrap' }}>
            {formatSize(attachment.size)}
          </Typography.Text>
        </Flex>
      ))}
      {failed && <Alert type="error" showIcon message="Не удалось скачать файл" />}
    </Flex>
  )
}
