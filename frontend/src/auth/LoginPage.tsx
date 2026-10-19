import { Alert, Button, Card, Flex, Form, Grid, Input, Layout, Typography } from 'antd'
import { useState } from 'react'
import { useAuth } from './AuthProvider'

interface Values {
  login?: string
  password?: string
}

const MESSAGES = {
  invalid: 'Неверный логин или пароль',
  error: 'Не удалось выполнить вход. Попробуйте позже',
}

export default function LoginPage() {
  const { state, signIn } = useAuth()
  const [form] = Form.useForm<Values>()
  const [error, setError] = useState<keyof typeof MESSAGES | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const wide = Grid.useBreakpoint().md ?? false
  const login = Form.useWatch('login', form)
  const password = Form.useWatch('password', form)
  const filled = Boolean(login?.trim()) && Boolean(password?.trim())
  const notice = state.status === 'anonymous' ? state.notice : undefined

  const submit = async (values: Values) => {
    setSubmitting(true)
    setError(null)
    const result = await signIn((values.login ?? '').trim(), values.password ?? '')
    if (result !== 'ok') {
      setSubmitting(false)
      setError(result)
      if (result === 'invalid') form.setFieldValue('password', '')
    }
  }

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Header
        style={{
          height: 'auto',
          minHeight: 80,
          lineHeight: 'normal',
          padding: wide ? '0 40px' : '12px 16px',
          borderBottom: '1px solid #e8e8e8',
          display: 'flex',
          alignItems: 'center',
        }}
      >
        <Typography.Text strong style={{ fontSize: 15 }}>
          ТСЖ «Комфорт»
        </Typography.Text>
      </Layout.Header>
      <Layout.Content style={{ padding: wide ? '80px 40px' : '32px 16px' }}>
        <Card style={{ maxWidth: 400, margin: '0 auto' }}>
          <Flex vertical gap={20}>
            <Typography.Title level={2} style={{ margin: 0 }}>
              Вход
            </Typography.Title>
            {notice && !error && <Alert type="warning" showIcon message={notice} />}
            {error && <Alert type="error" showIcon message={MESSAGES[error]} />}
            <Form form={form} layout="vertical" onFinish={submit} requiredMark={false}>
              <Form.Item label="Логин" name="login">
                <Input autoComplete="username" autoFocus />
              </Form.Item>
              <Form.Item label="Пароль" name="password">
                <Input.Password autoComplete="current-password" />
              </Form.Item>
              <Button type="primary" htmlType="submit" block disabled={!filled || submitting} loading={submitting}>
                Войти
              </Button>
            </Form>
          </Flex>
        </Card>
      </Layout.Content>
    </Layout>
  )
}
