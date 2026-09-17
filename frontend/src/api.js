const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api'

async function handleResponse(response) {
  const data = await response.json().catch(() => null)
  if (!response.ok) {
    throw new Error(data?.error || `Request failed with status ${response.status}`)
  }
  return data
}

export async function fetchInventory() {
  const response = await fetch(`${API_BASE_URL}/inventory`)
  return handleResponse(response)
}

export async function placeOrder(items) {
  const response = await fetch(`${API_BASE_URL}/orders`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ items }),
  })
  return handleResponse(response)
}

export async function fetchOrders() {
  const response = await fetch(`${API_BASE_URL}/orders`)
  return handleResponse(response)
}

export async function cancelOrder(orderId) {
  const response = await fetch(`${API_BASE_URL}/orders/${orderId}/cancel`, {
    method: 'POST',
  })
  return handleResponse(response)
}

export async function fetchNotifications() {
  const response = await fetch(`${API_BASE_URL}/notifications`)
  return handleResponse(response)
}
