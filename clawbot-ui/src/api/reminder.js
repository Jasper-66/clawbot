import axios from 'axios'

const api = axios.create({ baseURL: '/api/reminders' })

export function listReminders(status) {
  return api.get('', { params: { status } })
}

export function deleteReminder(id) {
  return api.delete(`/${id}`)
}
