import { Flex, Typography } from 'antd'
import PublishReceiptForm from './PublishReceiptForm'

// The tab of an administrator: publishes the receipt of an apartment chosen in the form.
export default function UploadReceiptTab() {
  return (
    <Flex vertical gap={24}>
      <Typography.Title level={1} style={{ fontSize: 26, margin: 0, textAlign: 'left' }}>
        Выставить квитанции
      </Typography.Title>
      <PublishReceiptForm />
    </Flex>
  )
}
