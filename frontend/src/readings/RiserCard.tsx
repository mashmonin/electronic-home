import { Card, Col, Flex, Row, Typography } from 'antd'
import ReadingField from './ReadingField'
import type { Riser } from './types'

interface Props {
  index: number
  riser?: Riser
}

export default function RiserCard({ index, riser }: Props) {
  return (
    <Card
      variant="outlined"
      style={{ borderColor: '#f0f0f0' }}
      styles={{ body: { padding: 24 } }}
    >
      <Flex align="baseline" gap={12} style={{ marginBottom: 16 }}>
        <Typography.Title level={5} style={{ margin: 0, fontSize: 15 }}>
          Стояк {index + 1}
        </Typography.Title>
        <Typography.Text type="secondary" style={{ fontSize: 11 }}>
          Счётчик № {riser?.meterNumber ?? '—'}
        </Typography.Text>
      </Flex>
      <Row gutter={[36, 16]}>
        <Col xs={24} md={12}>
          <ReadingField name={[index, 'hot']} label="Горячая вода, м³" meter={riser?.hot} />
        </Col>
        <Col xs={24} md={12}>
          <ReadingField name={[index, 'cold']} label="Холодная вода, м³" meter={riser?.cold} />
        </Col>
      </Row>
    </Card>
  )
}
