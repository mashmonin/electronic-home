import { Flex, Typography } from 'antd'
import PublishReceiptForm from './PublishReceiptForm'
import StatementUploadCard from './StatementUploadCard'

// The tab of an administrator: the receipts of a month from a statement of the charges, and the receipt of one apartment from the form.
export default function UploadReceiptTab() {
  return (
    <Flex vertical gap={24}>
      <Typography.Title level={1} style={{ fontSize: 26, margin: 0, textAlign: 'left' }}>
        Выставить квитанции
      </Typography.Title>
      <StatementUploadCard />
      <PublishReceiptForm />
    </Flex>
  )
}
