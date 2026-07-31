import axios from 'axios'

const api = axios.create({
  baseURL: '/api/knowledge'
})

export function listDocuments(category) {
  return api.get('/documents', { params: { category } })
}

export function getDocument(id) {
  return api.get(`/documents/${id}`)
}

export function addDocument(data) {
  return api.post('/documents', data)
}

export function uploadFile(file, category) {
  const formData = new FormData()
  formData.append('file', file)
  if (category) formData.append('category', category)
  return api.post('/upload', formData)
}

export function deleteDocument(id) {
  return api.delete(`/documents/${id}`)
}

export function search(query, topK = 5) {
  return api.post('/search', { query, topK })
}
