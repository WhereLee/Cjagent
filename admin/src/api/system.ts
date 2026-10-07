import { request } from '@/api/http'

export interface HealthComponent {
  status: string
  details?: Record<string, unknown>
  components?: Record<string, HealthComponent>
}

export interface HealthPayload {
  status: string
  groups?: string[]
  components?: Record<string, HealthComponent>
}

export function fetchHealth() {
  return request<HealthPayload>({ url: '/api/system/health', method: 'get', silentError: true })
}
