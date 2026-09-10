import { useEffect, useState } from 'react'
import { fetchInventory, placeOrder } from './api'

// Shown until the live inventory loads, matching the seeded products.
const FALLBACK_PRODUCTS = [
  { productId: 'P100', name: 'Wireless Mouse', stock: null },
  { productId: 'P200', name: 'Mechanical Keyboard', stock: null },
  { productId: 'P300', name: 'USB-C Hub', stock: null },
]

function App() {
  const [products, setProducts] = useState(FALLBACK_PRODUCTS)
  const [productId, setProductId] = useState('P100')
  const [quantity, setQuantity] = useState(1)
  const [result, setResult] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)

  const loadInventory = async () => {
    try {
      const items = await fetchInventory()
      if (items?.length) setProducts(items)
    } catch {
      // Backend not reachable yet - keep the fallback list, no need to
      // surface this as a user-facing error.
    }
  }

  useEffect(() => {
    loadInventory()
  }, [])

  const handleSubmit = async (event) => {
    event.preventDefault()
    setError(null)
    setResult(null)
    setLoading(true)
    try {
      const response = await placeOrder(productId, Number(quantity))
      setResult(response)
      await loadInventory()
    } catch (err) {
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="app">
      <h1>Place an Order</h1>

      <form onSubmit={handleSubmit}>
        <label>
          Product
          <select value={productId} onChange={(e) => setProductId(e.target.value)}>
            {products.map((p) => (
              <option key={p.productId} value={p.productId}>
                {p.name} ({p.productId})
                {p.stock !== null && p.stock !== undefined ? ` — ${p.stock} in stock` : ''}
              </option>
            ))}
          </select>
        </label>

        <label>
          Quantity
          <input
            type="number"
            min="1"
            value={quantity}
            onChange={(e) => setQuantity(e.target.value)}
            required
          />
        </label>

        <button type="submit" disabled={loading}>
          {loading ? 'Placing order…' : 'Place order'}
        </button>
      </form>

      {error && <div className="result result-error">Error: {error}</div>}

      {result && (
        <div className={`result ${result.status === 'CONFIRMED' ? 'result-confirmed' : 'result-rejected'}`}>
          <h2>{result.status}</h2>
          {result.reason && <p>{result.reason}</p>}
          {result.inventory && (
            <p>
              {result.inventory.name} ({result.inventory.productId}): {result.inventory.stock} left in stock
            </p>
          )}
        </div>
      )}
    </div>
  )
}

export default App
