import { CloseOutlined, PaperClipOutlined } from '@ant-design/icons'
import { Alert, Button, Flex, Tooltip, Typography } from 'antd'
import { useRef, useState } from 'react'
import { ACCEPT, addFiles, formatSize } from './attachments'

interface Props {
  files: File[]
  onChange: (files: File[]) => void
  disabled?: boolean
  /** "button" is the text button over the list; "icon" is the round paper clip in a row with the children (the field and the send button). */
  variant?: 'button' | 'icon'
  children?: React.ReactNode
}

// The button (or the paper clip) "Прикрепить файл" and the list of the chosen files; the files that do not fit are left out with the reason.
export default function AttachmentPicker({ files, onChange, disabled = false, variant = 'button', children }: Props) {
  const input = useRef<HTMLInputElement>(null)
  const [problems, setProblems] = useState<string[]>([])

  const choose = (event: React.ChangeEvent<HTMLInputElement>) => {
    const result = addFiles(files, Array.from(event.target.files ?? []))
    setProblems(result.problems)
    onChange(result.files)
    // the same file may be chosen again after it was removed
    event.target.value = ''
  }

  const remove = (index: number) => {
    setProblems([])
    onChange(files.filter((_, position) => position !== index))
  }

  return (
    <Flex vertical gap={8}>
      <input ref={input} type="file" multiple hidden accept={ACCEPT} data-testid="attachment-input" onChange={choose} />
      {variant === 'button' && (
        <div>
          <Button icon={<PaperClipOutlined />} disabled={disabled} onClick={() => input.current?.click()}>
            Прикрепить файл
          </Button>
        </div>
      )}
      {problems.map((problem) => (
        <Alert key={problem} type="warning" showIcon message={problem} />
      ))}
      {files.length > 0 && (
        <Flex vertical gap={4} data-testid="chosen-files">
          {files.map((file, index) => (
            <Flex key={`${file.name}-${index}`} align="center" justify="space-between" gap={8}>
              <Typography.Text ellipsis style={{ flex: 1, minWidth: 0 }}>
                {file.name}
              </Typography.Text>
              <Typography.Text type="secondary" style={{ fontSize: 12, whiteSpace: 'nowrap' }}>
                {formatSize(file.size)}
              </Typography.Text>
              <Button
                type="text"
                size="small"
                aria-label={`Удалить файл «${file.name}»`}
                icon={<CloseOutlined />}
                disabled={disabled}
                onClick={() => remove(index)}
              />
            </Flex>
          ))}
        </Flex>
      )}
      {variant === 'icon' && (
        <Flex align="flex-end" gap={8}>
          <Tooltip title="Прикрепить файл">
            <Button
              type="text"
              shape="circle"
              size="large"
              aria-label="Прикрепить файл"
              icon={<PaperClipOutlined style={{ fontSize: 20, color: '#8c8c8c' }} />}
              disabled={disabled}
              onClick={() => input.current?.click()}
            />
          </Tooltip>
          {children}
        </Flex>
      )}
    </Flex>
  )
}
