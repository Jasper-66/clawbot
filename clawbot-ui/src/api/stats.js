import axios from 'axios'

const api = axios.create({ baseURL: '/api/stats' })

export function getDashboardStats() {
  return api.get('/dashboard')
}

export function getTokenUsage() {
  return api.get('/token-usage')
}
