import { Card, Col, Flex, Row, Tag, Typography } from 'antd'
import ReadingField from './ReadingField'
import type { Meter } from './types'
import { formatVerificationDate } from './verification'

interface Props {
  index: number
  meter: Meter
}

// Left: the meter name with its verification date; right: the readings block.
export default function MeterCard({ index, meter }: Props) {
  return (
    <Card variant="outlined" style={{ borderColor: '#f0f0f0' }} styles={{ body: { padding: 24 } }}>
      <Row gutter={[36, 16]}>
        <Col xs={24} md={10}>
          <Typography.Title level={5} style={{ margin: '0 0 4px', fontSize: 15 }}>
            {meter.number}
          </Typography.Title>
          <Flex vertical align="flex-start" gap={4}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              Дата проверки: {formatVerificationDate(meter.verificationDate)}
            </Typography.Text>
            {meter.expired && (
              <Tag color="error" style={{ margin: 0, fontSize: 11 }}>
                Просрочено
              </Tag>
            )}
          </Flex>
        </Col>
        <Col xs={24} md={14}>
          <ReadingField index={index} meter={meter} />
        </Col>
      </Row>
    </Card>
  )
}
