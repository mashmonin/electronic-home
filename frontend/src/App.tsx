import { Spin } from 'antd'
import { AuthProvider, useAuth } from './auth/AuthProvider'
import LoginPage from './auth/LoginPage'
import MeterReadingsPage from './readings/MeterReadingsPage'

function Gate() {
  const { state, signOut } = useAuth()
  if (state.status === 'loading') return <Spin style={{ display: 'block', margin: '120px auto' }} />
  if (state.status === 'anonymous') return <LoginPage />
  return <MeterReadingsPage user={state.me} onSignOut={signOut} />
}

export default function App() {
  return (
    <AuthProvider>
      <Gate />
    </AuthProvider>
  )
}
