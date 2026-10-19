import type { Me } from './auth/authApi'

export const ADMIN: Me = { login: 'admin', role: 'ADMIN', accounts: ['000000000001', '000000000002'] }
export const OWNER: Me = { login: 'owner', role: 'OWNER', accounts: ['000000000001'] }
export const RESIDENT: Me = { login: 'resident', role: 'RESIDENT', accounts: ['000000000001'] }
