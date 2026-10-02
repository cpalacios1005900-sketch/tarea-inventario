# DECISIONS

Servicio de reservas de inventario en memoria. Aquí van los supuestos, las decisiones, lo que quedó fuera y lo que cambiaría antes de producción.

## Estructura

Solo `Inventory.create(...)` es público (más el contrato `api`, intacto). El resto es privado del paquete.

| Clase | Responsabilidad |
| --- | --- |
| `Inventory` | Punto de entrada; ensambla las piezas. |
| `InMemoryInventoryService` | Implementa el contrato: concurrencia, idempotencia, expiración y alertas. |
| `ProductStock` | Stock de un producto (`onHand`, `held`, ciclo de alerta). |
| `Hold` | Una reserva interna. |
| `ReservationPolicy` / `CategoryPolicies` | Tiempo para pagar y límite por pedido; tabla única de reglas por categoría. |

## Supuestos

1. **Reintentos:** repetir `reserve` con el mismo `orderId`, producto y cantidad devuelve la reserva original (no reserva dos veces, no extiende el plazo, no repite alertas). Con otro producto o cantidad lanza `IllegalArgumentException`.
2. **`confirm` es idempotente:** confirmar dos veces no hace nada. Pedido desconocido o vencido lanza `IllegalStateException`.
3. **Vencimiento:** una reserva vence cuando `ahora >= expiresAt`; confirmar en ese instante falla.
4. **Reusar un `orderId` vencido** crea una reserva nueva. Un pedido confirmado conserva su `orderId`.
5. **`registerProduct` es un upsert:** conserva stock y reservas; un cambio de categoría solo afecta reservas nuevas.
6. **Orden de validación en `reserve`:** argumentos, reintento, producto existe, límite de categoría, stock.
7. **Alerta de stock bajo:** con 5 unidades disponibles o menos, evaluada tras cada reserva exitosa. Una vez por ciclo; el ciclo se reinicia al reabastecer o si el disponible vuelve a superar el umbral por reservas vencidas.
8. **Entradas inválidas:** `null` lanza `NullPointerException`; textos vacíos, cantidades `<= 0` y desbordes de stock lanzan `IllegalArgumentException`.

## Decisiones de diseño

- **Categorías nuevas:** las reglas son datos resueltos en un `switch` sin `default`; si alguien agrega una categoría, el código no compila hasta definir sus reglas.
- **Expiración perezosa:** sin hilos en segundo plano; las reservas vencidas se liberan, con el `Clock` inyectado, al tocar el producto.
- **Concurrencia:** un único `ReentrantLock` hace atómico "revisar y reservar", que es la causa de la sobreventa.
- **Alertas fuera del lock:** un canal lento no frena a otros clientes y, si falla, se registra sin afectar la reserva.
- **Más canales de aviso:** `StockAlertListener` es el punto de extensión; basta un listener que reparta a varios canales.
- **Sin dependencias nuevas.**

## Pruebas

Solo los 3 tests originales (`InventoryServiceTest`), que cubren el flujo principal. No hay tests automáticos de expiración, idempotencia, límites por categoría, alertas ni concurrencia.
