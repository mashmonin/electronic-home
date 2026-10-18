import { BellOutlined, HomeOutlined, UserOutlined } from '@ant-design/icons'
import { Button, Dropdown, Flex, Grid, Layout, Typography } from 'antd'
import type { Apartment } from './types'

interface Props {
  apartments: Apartment[]
  apartment: Apartment
  onApartmentChange: (id: string) => void
}

const ICON_STYLE = { fontSize: 18, color: '#8c8c8c' }

export default function AppHeader({ apartments, apartment, onApartmentChange }: Props) {
  const wide = Grid.useBreakpoint().md ?? false

  return (
    <Layout.Header
      style={{
        height: 'auto',
        minHeight: 80,
        lineHeight: 'normal',
        padding: wide ? '0 40px' : '12px 16px',
        borderBottom: '1px solid #e8e8e8',
      }}
    >
      <Flex align="center" justify="space-between" gap={16} wrap style={{ minHeight: 56 }}>
        <Flex align="center" gap={28}>
          <Typography.Text strong style={{ fontSize: 15 }}>
            ТСЖ «Комфорт»
          </Typography.Text>
          <Flex gap={24}>
            <Typography.Text strong style={{ color: '#ffb370' }}>
              Показания
            </Typography.Text>
            <Typography.Text type="secondary">Заявки</Typography.Text>
          </Flex>
        </Flex>
        <Flex align="center" gap={18}>
          <BellOutlined aria-label="Уведомления" style={ICON_STYLE} />
          <UserOutlined aria-label="Профиль" style={ICON_STYLE} />
          <HomeOutlined aria-label="Дом" style={ICON_STYLE} />
          <Dropdown
            trigger={['click']}
            menu={{
              items: apartments.map((a) => ({ key: a.id, label: a.address })),
              selectedKeys: [apartment.id],
              onClick: ({ key }) => onApartmentChange(key),
            }}
          >
            <Button type="text" aria-label="Сменить адрес" style={{ height: 'auto', padding: 0 }}>
              <Flex vertical align="flex-end">
                <Typography.Text strong>{apartment.address}</Typography.Text>
                <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                  Лицевой счёт № {apartment.account}
                </Typography.Text>
              </Flex>
            </Button>
          </Dropdown>
        </Flex>
      </Flex>
    </Layout.Header>
  )
}
