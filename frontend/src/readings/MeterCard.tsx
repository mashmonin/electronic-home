import { EditOutlined } from '@ant-design/icons'
import { Button, Card, Col, Flex, Row, Tag, Typography } from 'antd'
import ReadingField from './ReadingField'
import type { Meter } from './types'
import { formatVerificationDate } from './verification'

interface Props {
  index: number
  meter: Meter
  /** Opens the change of the verification date; the button is not shown without it. */
  onEditVerification?: () => void
}

// Left: the meter name with its verification date; right: the readings block.
export default function MeterCard({ index, meter, onEditVerification }: Props) {
  return (
    <Card variant="outlined" style={{ borderColor: '#f0f0f0' }} styles={{ body: { padding: 24 } }}>
      <Row gutter={[36, 16]}>
        <Col xs={24} md={10}>
          <Typography.Title level={5} style={{ margin: '0 0 4px', fontSize: 15 }}>
            {meter.number}
          </Typography.Title>
          <Flex vertical align="flex-start" gap={4}>
            <Flex align="center" gap={4}>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                Дата проверки: {formatVerificationDate(meter.verificationDate)}
              </Typography.Text>
              {onEditVerification && (
                <Button
                  type="link"
                  size="small"
                  icon={<EditOutlined />}
                  aria-label={`Изменить срок проверки счётчика ${meter.number}`}
                  onClick={onEditVerification}
                  style={{ padding: 0, height: 'auto', fontSize: 12 }}
                >
                  Изменить
                </Button>
              )}
            </Flex>
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
