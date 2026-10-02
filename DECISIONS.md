# DECISIONS

Servicio de reservas de inventario. Este documento explica qué se asumió, qué se decidió, qué quedó fuera y qué cambiaría antes de llevarlo a producción.

## Estructura

Solo `Inventory.create(...)` es público (más el contrato de `api`, intacto). Todo lo demás es privado del paquete `com.store.inventory`, para que nadie dependa de detalles internos.

| Clase | Responsabilidad |
| --- | --- |
| `Inventory` | Punto de entrada. Solo ensambla las piezas. |
| `InMemoryInventoryService` | Implementa `InventoryService`: concurrencia, idempotencia, expiración, entrega de alertas. |
| `ProductStock` | Estado y aritmética de stock de **un** producto (`onHand`, `held`, ciclo de alerta). |
| `Hold` | Una reserva interna. |
| `ReservationPolicy` | Reglas que impone una categoría: tiempo para pagar y límite por pedido. |
| `CategoryPolicies` | Única tabla de reglas por categoría. |

## Supuestos

El contrato no los define, así que los fijé explícitamente (y cada uno tiene test).

1. **Reintentos de la app.** Repetir `reserve` con el mismo `orderId`, producto y cantidad devuelve la reserva original: no reserva dos veces, **no extiende el plazo** y no vuelve a disparar alertas. Si el mismo `orderId` llega con otro producto o cantidad, es un error del cliente y se lanza `IllegalArgumentException`.
2. **`confirm` es idempotente.** Las notificaciones de pago pueden llegar duplicadas, así que confirmar un pedido ya confirmado no hace nada. Confirmar un pedido desconocido o vencido lanza `IllegalStateException`, como indica el contrato.
3. **Borde del vencimiento.** Una reserva está vencida cuando `ahora >= expiresAt`. Con 15 minutos de plazo, a los 14:59 sigue activa y a los 15:00 ya no. Confirmar en ese instante exacto falla.
4. **Reusar un `orderId` después de vencer** crea una reserva nueva (es un intento de compra nuevo). Un pedido **confirmado** conserva su `orderId` para siempre, de modo que un reintento tardío devuelve la reserva original.
5. **`registerProduct` es un upsert.** Registrar otra vez conserva stock y reservas. Si cambia la categoría, solo afecta a las reservas nuevas; las existentes mantienen el plazo que ya se les dio.
6. **Orden de validaciones en `reserve`:** argumentos, reintento idéntico, producto existe, límite de la categoría, stock. Por eso pedir 3 unidades de un `FLASH_SALE` con 1 en stock falla por límite y no por stock.
7. **Alerta de stock bajo.**
   - Umbral: 5 unidades disponibles o menos (inclusive), constante `Inventory.LOW_STOCK_THRESHOLD`.
   - Se evalúa después de cada reserva exitosa, que es cuando "le quedan" menos unidades. No se evalúa al agregar stock.
   - Se avisa una vez por ciclo. El ciclo se reinicia cuando hay un reabastecimiento (`addStock`) o cuando el disponible vuelve a superar el umbral porque vencieron reservas. Sin esto último, una reserva que se cae dejaría una falsa alarma y taparía la siguiente alerta real.
   - El aviso informa las unidades disponibles en el momento de la reserva.
8. **Entradas inválidas.** `null` lanza `NullPointerException`, textos vacíos y cantidades `<= 0` lanzan `IllegalArgumentException`, y sumar stock que desborda un `int` también (sin dejar el stock a medias).

## Decisiones de diseño

- **Nuevas categorías cada temporada.** Las reglas son datos (`ReservationPolicy`) resueltos en un `switch` **sin `default`** (`CategoryPolicies`). Cuando alguien agregue una constante a `ProductCategory`, el código no compila hasta definir sus reglas, en vez de heredar en silencio un comportamiento equivocado. El servicio recibe las políticas por constructor, así que probarlo con otras reglas no exige tocar nada.
- **Expiración perezosa, sin hilos en segundo plano.** Las reservas vencidas se liberan, usando el `Clock` inyectado, cada vez que se toca el producto. No hay timers que se pierdan ni tests con `sleep`, y el tiempo es totalmente controlable en pruebas. Cada producto guarda sus reservas pendientes ordenadas por vencimiento, así que liberar es proporcional a lo que vence, no a todo lo reservado.
- **Concurrencia con un único `ReentrantLock`.** Es la opción más simple que garantiza que "revisar disponibilidad y reservar" sea atómico (la causa de la sobreventa). Es suficiente para memoria porque las operaciones son O(log n). Se eligió `ReentrantLock` sobre `synchronized` para no fijar hilos virtuales de Java 21.
- **Las alertas salen fuera del lock.** Un canal lento (SMTP) no frena a los demás clientes y un listener que llame de vuelta al servicio no se bloquea. Si el listener lanza una excepción, se registra y la reserva sigue valiendo: un fallo de correo no debe costarle una venta al cliente.
- **Canales de aviso.** No hay nada atado a correo. `StockAlertListener` ya es el punto de extensión: para sumar canales basta pasar un listener que reparta a varios (un compuesto). No lo agregué porque hoy hay un solo canal y sería código sin uso.
- **Sin dependencias nuevas.** Solo JDK y JUnit (que ya estaba). El `pom.xml` no cambió.

## Pruebas

`mvn test` ejecuta los 3 tests originales sin modificar 

El reloj es controlable (`MutableClock`), por lo que ningún test depende del tiempo real.

Verificación adicional hecha a mano: rompí el código a propósito (quitar el lock, desactivar la idempotencia, mover el borde del vencimiento, repetir alertas, cambiar el límite, no descontar al confirmar) y confirmé que los tests fallan en cada caso. La primera versión de los tests de concurrencia **no** detectaba la ausencia del lock; por eso ahora son repetitivos.

## Lo que dejé fuera

- **Persistencia y varias instancias.** Todo vive en memoria de un proceso.
- **Cancelar o liberar una reserva** (por ejemplo, pago rechazado). El contrato no lo ofrece; hoy las unidades vuelven al vencer el plazo.
- **Reintentos de alertas** si el canal falla (hoy se registra y se pierde).
- **Retención de datos.** Los pedidos confirmados y los productos nunca se eliminan de memoria.
- **Métricas y trazas.**
- **Reglas de categorías editables sin desplegar.** Hoy son código.

## Qué cambiaría antes de producción

1. **Base de datos como fuente de verdad.** Dos tablas: stock por SKU y reservas con `order_id` único. Reservar sería un `UPDATE` condicional atómico (`... WHERE on_hand - reserved >= :qty`) más un `INSERT` con restricción de unicidad en `order_id`. Así la idempotencia y la ausencia de sobreventa las garantiza la base, también con varias instancias. El lock en memoria deja de servir en ese escenario.
2. **Tiempo.** Usar la hora de la base de datos para vencimientos, para que el desfase de relojes entre instancias no cambie quién llega a tiempo.
3. **Expiración.** Filtrar por `expires_at` al calcular disponibilidad (consistencia inmediata) y un job que marque las vencidas para limpiar. Índice sobre `(status, expires_at)`.
4. **Alertas.** Outbox transaccional y un indicador de "ya avisado" actualizado de forma atómica, para que solo una de N instancias avise. Reintentos con espera creciente y cada canal (correo, Slack, SMS) como suscriptor independiente.
5. **Operación `cancel/release`** para devolver stock al instante cuando el pago es rechazado.
6. **Observabilidad:** métricas de reservas, vencimientos, rechazos por falta de stock y alertas fallidas; logs con `orderId`.
7. **Pruebas:** tests de integración contra una base real (Testcontainers), mutation testing (PIT) y cobertura (JaCoCo) en CI.
8. **Categorías como configuración** si Marketing necesita crearlas sin un despliegue. Hoy agregar una exige tocar el enum del contrato, que es parte de la API y requiere coordinarse con la app.
