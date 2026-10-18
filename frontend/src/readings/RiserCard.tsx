import { Card, Col, Row, Typography } from 'antd'
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
      <Typography.Title level={5} style={{ margin: '0 0 16px', fontSize: 15 }}>
        Стояк {index + 1}
      </Typography.Title>
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
