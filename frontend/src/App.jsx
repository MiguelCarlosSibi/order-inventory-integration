import { useEffect, useState } from 'react'
import {
  fetchInventory,
  placeOrder,
  fetchOrders,
  cancelOrder,
  fetchNotifications,
} from './api'

const LOW_STOCK_THRESHOLD = 5

// Shown until the live inventory loads.
const FALLBACK_PRODUCTS = [
  { productId: 'P100', name: 'Wireless Mouse', stock: null },
  { productId: 'P200', name: 'Mechanical Keyboard', stock: null },
  { productId: 'P300', name: 'USB-C Hub', stock: null },
]

function App() {
  const [inventory, setInventory] = useState(FALLBACK_PRODUCTS)
  const [orders, setOrders] = useState([])
  const [notifications, setNotifications] = useState([])

  const [cart, setCart] = useState([])
  const [productId, setProductId] = useState('P100')
  const [quantity, setQuantity] = useState(1)

  const [orderResult, setOrderResult] = useState(null)
  const [error, setError] = useState(null)
  const [submitting, setSubmitting] = useState(false)
  const [cancellingId, setCancellingId] = useState(null)

  const refreshAll = async () => {
    const [inv, ord, notif] = await Promise.allSettled([
      fetchInventory(),
      fetchOrders(),
      fetchNotifications(),
    ])
    if (inv.status === 'fulfilled') setInventory(inv.value)
    if (ord.status === 'fulfilled') setOrders(ord.value)
    if (notif.status === 'fulfilled') setNotifications(notif.value)
  }

  useEffect(() => {
    refreshAll()
  }, [])

  const addToCart = () => {
    setCart((prev) => {
      const existing = prev.find((item) => item.productId === productId)
      if (existing) {
        return prev.map((item) =>
          item.productId === productId
            ? { ...item, quantity: item.quantity + Number(quantity) }
            : item
        )
      }
      return [...prev, { productId, quantity: Number(quantity) }]
    })
  }

  const removeFromCart = (index) => {
    setCart((prev) => prev.filter((_, i) => i !== index))
  }

  const productName = (id) => {
    const found = inventory.find((p) => p.productId === id)
    return found ? found.name : id
  }

  const submitOrder = async () => {
    if (cart.length === 0) return
    setError(null)
    setOrderResult(null)
    setSubmitting(true)
    try {
      const response = await placeOrder(cart)
      setOrderResult(response)
      setCart([])
      await refreshAll()
    } catch (err) {
      setError(err.message)
    } finally {
      setSubmitting(false)
    }
  }

  const handleCancel = async (orderId) => {
    setError(null)
    setCancellingId(orderId)
    try {
      await cancelOrder(orderId)
      await refreshAll()
    } catch (err) {
      setError(err.message)
    } finally {
      setCancellingId(null)
    }
  }

  return (
    <div className="app">
      <h1>Order &amp; Inventory</h1>

      <section className="panel">
        <h2>Build an Order</h2>

        <div className="cart-form">
          <label>
            Product
            <select value={productId} onChange={(e) => setProductId(e.target.value)}>
              {inventory.map((p) => (
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
            />
          </label>

          <button type="button" onClick={addToCart}>
            Add to cart
          </button>
        </div>

        {cart.length > 0 && (
          <ul className="cart-list">
            {cart.map((item, index) => (
              <li key={`${item.productId}-${index}`}>
                {productName(item.productId)} ({item.productId}) × {item.quantity}
                <button type="button" className="link-button" onClick={() => removeFromCart(index)}>
                  remove
                </button>
              </li>
            ))}
          </ul>
        )}

        <button
          type="button"
          className="primary"
          disabled={cart.length === 0 || submitting}
          onClick={submitOrder}
        >
          {submitting ? 'Placing order…' : `Place order (${cart.length} item${cart.length === 1 ? '' : 's'})`}
        </button>

        {error && <div className="result result-error">Error: {error}</div>}

        {orderResult && (
          <div className={`result ${orderResult.status === 'CONFIRMED' ? 'result-confirmed' : 'result-rejected'}`}>
            <h3>
              Order #{orderResult.orderId}: {orderResult.status}
            </h3>
            {orderResult.reason && <p>{orderResult.reason}</p>}
            <ul>
              {orderResult.items?.map((item, i) => (
                <li key={i}>
                  {productName(item.productId)} × {item.quantity} — {item.outcome}
                </li>
              ))}
            </ul>
          </div>
        )}
      </section>

      <section className="panel">
        <h2>Inventory</h2>
        <table>
          <thead>
            <tr>
              <th>Product</th>
              <th>ID</th>
              <th>Stock</th>
            </tr>
          </thead>
          <tbody>
            {inventory.map((item) => (
              <tr key={item.productId} className={item.stock < LOW_STOCK_THRESHOLD ? 'low-stock' : ''}>
                <td>{item.name}</td>
                <td>{item.productId}</td>
                <td>{item.stock}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <section className="panel">
        <h2>Order History</h2>
        {orders.length === 0 && <p className="empty">No orders yet.</p>}
        <ul className="order-history">
          {orders
            .slice()
            .sort((a, b) => b.orderId - a.orderId)
            .map((order) => (
              <li key={order.orderId} className={`order-row status-${order.status.toLowerCase()}`}>
                <div className="order-row-header">
                  <strong>#{order.orderId}</strong>
                  <span className={`badge badge-${order.status.toLowerCase()}`}>{order.status}</span>
                  {order.status === 'CONFIRMED' && (
                    <button
                      type="button"
                      className="link-button"
                      disabled={cancellingId === order.orderId}
                      onClick={() => handleCancel(order.orderId)}
                    >
                      {cancellingId === order.orderId ? 'Cancelling…' : 'Cancel'}
                    </button>
                  )}
                </div>
                {order.reason && <p className="order-reason">{order.reason}</p>}
                <ul className="order-items">
                  {order.items.map((item, i) => (
                    <li key={i}>
                      {productName(item.productId)} × {item.quantity}
                    </li>
                  ))}
                </ul>
              </li>
            ))}
        </ul>
      </section>

      <section className="panel">
        <h2>Activity Feed</h2>
        {notifications.length === 0 && <p className="empty">No notifications yet.</p>}
        <ul className="notification-feed">
          {notifications.map((n) => (
            <li key={n.notificationId}>{n.message}</li>
          ))}
        </ul>
      </section>
    </div>
  )
}

export default App
