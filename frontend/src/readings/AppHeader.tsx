import { BellOutlined, HomeOutlined, LogoutOutlined, UserOutlined } from '@ant-design/icons'
import { Button, Flex, Grid, Layout, Popover, Typography } from 'antd'
import { type Me, ROLE_NAMES } from '../auth/authApi'
import type { Account } from './accountApi'
import type { Tab } from './tabs'

interface Props {
  /** The account of the screen; null for an administrator, who has none. */
  account: Account | null
  user: Me
  activeTab: Tab
  onTabChange: (tab: Tab) => void
  onSignOut: () => void
}

const ICON_STYLE = { fontSize: 18, color: '#8c8c8c' }

// A section of the navigation; the one of the open tab is bold and in the main colour.
function NavItem({ label, active, onClick }: { label: string; active: boolean; onClick: () => void }) {
  return (
    <Button
      type="link"
      aria-current={active ? 'page' : undefined}
      onClick={onClick}
      style={{ padding: 0, height: 'auto', fontWeight: active ? 700 : 400, color: active ? '#ffb370' : '#8c8c8c' }}
    >
      {label}
    </Button>
  )
}

export default function AppHeader({ account, user, activeTab, onTabChange, onSignOut }: Props) {
  const wide = Grid.useBreakpoint().md ?? false

  const profile = (
    <Flex vertical gap={12}>
      <Flex vertical>
        <Typography.Text strong>{user.login}</Typography.Text>
        <Typography.Text type="secondary">{ROLE_NAMES[user.role]}</Typography.Text>
      </Flex>
      {account && (
        <Flex vertical gap={4}>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            Совладельцы счёта
          </Typography.Text>
          {account.owners.length === 0 ? (
            <Typography.Text type="secondary">Совладельцы не указаны</Typography.Text>
          ) : (
            account.owners.map((owner) => <Typography.Text key={owner.full_name}>{owner.full_name}</Typography.Text>)
          )}
        </Flex>
      )}
      <Button onClick={onSignOut} icon={<LogoutOutlined />}>
        Выйти
      </Button>
    </Flex>
  )

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
            {user.role === 'ADMIN' ? (
              <NavItem label="Выставить квитанции" active={activeTab === 'upload'} onClick={() => onTabChange('upload')} />
            ) : (
              <>
                <NavItem label="Показания" active={activeTab === 'readings'} onClick={() => onTabChange('readings')} />
                {user.role === 'OWNER' && (
                  <NavItem label="Квитанции" active={activeTab === 'receipts'} onClick={() => onTabChange('receipts')} />
                )}
              </>
            )}
            <Typography.Text type="secondary">Заявки</Typography.Text>
          </Flex>
        </Flex>
        <Flex align="center" gap={18}>
          <BellOutlined aria-label="Уведомления" style={ICON_STYLE} />
          <Popover title="Профиль" content={profile} trigger="click" placement="bottomRight">
            <Button
              type="text"
              aria-label="Профиль"
              icon={<UserOutlined style={ICON_STYLE} />}
              style={{ padding: 0, width: 20, height: 20 }}
            />
          </Popover>
          <HomeOutlined aria-label="Дом" style={ICON_STYLE} />
          {account && (
            <Flex vertical align="flex-end">
              <Typography.Text strong>{account.address}</Typography.Text>
              <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                Лицевой счёт № {account.number}
              </Typography.Text>
            </Flex>
          )}
        </Flex>
      </Flex>
    </Layout.Header>
  )
}
