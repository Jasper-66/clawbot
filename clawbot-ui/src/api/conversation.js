import axios from 'axios'

const api = axios.create({ baseURL: '/api/conversations' })

export function listConversations() {
  return api.get('')
}

export function getMessages(conversationId) {
  return api.get(`/${conversationId}/messages`)
}

export function deleteConversation(id) {
  return api.delete(`/${id}`)
}
