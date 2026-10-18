import { Card, Typography } from 'antd'

export default function ContactCard() {
  return (
    <Card variant="outlined" style={{ borderColor: '#f0f0f0' }} styles={{ body: { padding: 24 } }}>
      <Typography.Text strong style={{ display: 'block' }}>
        Нужна помощь?
      </Typography.Text>
      <Typography.Text type="secondary" style={{ display: 'block', fontSize: 13, margin: '4px 0' }}>
        Председатель правления ТСЖ
      </Typography.Text>
      <Typography.Link href="tel:+79991234567" strong style={{ fontSize: 15 }}>
        +7 (999) 123-45-67
      </Typography.Link>
    </Card>
  )
}
