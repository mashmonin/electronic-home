import { Alert, Card, Spin } from 'antd'
import { useEffect, useState } from 'react'

type State = 'loading' | 'up' | 'error'

export default function HealthStatus() {
  const [state, setState] = useState<State>('loading')

  useEffect(() => {
    fetch('/api/actuator/health')
      .then((response) => response.json())
      .then((body: { status?: string }) => setState(body.status === 'UP' ? 'up' : 'error'))
      .catch(() => setState('error'))
  }, [])

  return (
    <Card title="Состояние сервиса">
      {state === 'loading' && <Spin />}
      {state === 'up' && <Alert type="success" showIcon message="Сервис работает" />}
      {state === 'error' && <Alert type="error" showIcon message="Нет связи с сервером" />}
    </Card>
  )
}
