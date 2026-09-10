const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api'

export async function placeOrder(productId, quantity) {
  const response = await fetch(`${API_BASE_URL}/orders`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ productId, quantity }),
  })

  const data = await response.json().catch(() => null)

  if (!response.ok) {
    throw new Error(data?.error || `Request failed with status ${response.status}`)
  }

  return data
}

export async function fetchInventory() {
  const response = await fetch(`${API_BASE_URL}/inventory`)
  if (!response.ok) {
    throw new Error('Failed to load inventory')
  }
  return response.json()
}
