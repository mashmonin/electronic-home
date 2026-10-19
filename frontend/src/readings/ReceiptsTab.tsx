import { Alert, Button, Collapse, Flex, Spin, Typography } from 'antd'
import { useEffect, useState } from 'react'
import { saveFile } from '../download'
import { type Receipt, downloadReceipt, fetchReceipts } from './receiptsApi'
import { formatMoney, formatReceiptMonth } from './receiptFormat'

type State = { kind: 'loading' } | { kind: 'ready'; receipts: Receipt[] } | { kind: 'error' }

interface Props {
  accountNumber: string
}

function Amount({ label, amount }: { label: string; amount: number | null }) {
  return (
    <Flex justify="space-between" align="baseline" gap={16}>
      <Typography.Text type="secondary">{label}</Typography.Text>
      <Typography.Text strong>{formatMoney(amount)}</Typography.Text>
    </Flex>
  )
}

// The receipts of the account, newest first, as an accordion: a panel per month with the two amounts and the PDF link.
export default function ReceiptsTab({ accountNumber }: Props) {
  const [state, setState] = useState<State>({ kind: 'loading' })
  const [attempt, setAttempt] = useState(0)
  const [downloading, setDownloading] = useState<string | null>(null)
  const [downloadFailed, setDownloadFailed] = useState(false)

  const download = async (month: string) => {
    setDownloading(month)
    setDownloadFailed(false)
    const result = await downloadReceipt(accountNumber, month)
    setDownloading(null)
    if (result.status === 'ok') saveFile(result.blob, result.filename)
    else setDownloadFailed(true)
  }

  useEffect(() => {
    let cancelled = false
    setState({ kind: 'loading' })
    fetchReceipts(accountNumber).then((result) => {
      if (cancelled) return
      setState(result.status === 'ok' ? { kind: 'ready', receipts: result.receipts } : { kind: 'error' })
    })
    return () => {
      cancelled = true
    }
  }, [accountNumber, attempt])

  return (
    <Flex vertical gap={24}>
      <Typography.Title level={1} style={{ fontSize: 26, margin: 0, textAlign: 'left' }}>
        Квитанции
      </Typography.Title>
      {state.kind === 'loading' && <Spin style={{ display: 'block', margin: '40px auto' }} />}
      {state.kind === 'error' && (
        <Alert
          type="error"
          showIcon
          message="Не удалось загрузить квитанции"
          action={
            <Button size="small" onClick={() => setAttempt((n) => n + 1)}>
              Повторить
            </Button>
          }
        />
      )}
      {downloadFailed && <Alert type="error" showIcon message="Не удалось скачать квитанцию" />}
      {state.kind === 'ready' && state.receipts.length === 0 && (
        <Alert type="info" showIcon message="Квитанции пока не опубликованы" />
      )}
      {state.kind === 'ready' && state.receipts.length > 0 && (
        <Collapse
          accordion
          defaultActiveKey={state.receipts[0].month}
          items={state.receipts.map((receipt) => ({
            key: receipt.month,
            label: <Typography.Text strong>{formatReceiptMonth(receipt.month)}</Typography.Text>,
            children: (
              <Flex vertical gap={12}>
                <Amount label="Капитальный ремонт" amount={receipt.capital_repair_amount} />
                <Amount label="Коммунальные услуги" amount={receipt.utilities_amount} />
                <Button
                  type="primary"
                  loading={downloading === receipt.month}
                  onClick={() => download(receipt.month)}
                  style={{ alignSelf: 'flex-start' }}
                >
                  Скачать PDF
                </Button>
              </Flex>
            ),
          }))}
        />
      )}
    </Flex>
  )
}
