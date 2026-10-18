# Guion de la prueba empírica — EP2 Caso 0

Las tres demostraciones obligatorias, con los comandos exactos y las líneas de log que hay que señalar. Todo preparado de antemano: **durante la prueba no se puede tocar código, ni comentar líneas, ni detener contenedores, ni desconectar servicios.**

## El dato de prueba para el error

> **`error-permanente@pedidos360.cl`** — falla definitiva, NACK inmediato → DLQ
> **`error-transitorio@pedidos360.cl`** — falla recuperable, 4 intentos → DLQ

El disparador es **el correo del cliente del pedido**. Nada más. Se crea el pedido con ese correo, se acepta, y el consumidor falla solo para ese mensaje mientras los demás siguen pasando normal.

---

## Antes de empezar — sobre la EC2 (lo que pide la guía)

La guía dice que la prueba *«debe ejecutarse sobre la solución desplegada y funcionando»*. Todo corre en la EC2 —frontend, BFF, los cuatro microservicios y el clúster— con Docker Compose, y la base en Oracle Cloud.

**Todo se hace desde el navegador y Postman.** No hace falta la llave SSH, ni el túnel, ni instalar nada: sirve desde un PC del instituto.

| Qué | Cómo se entra |
| :--- | :--- |
| Aplicación | https://xpglku1pj5.execute-api.us-east-1.amazonaws.com/Desarrollo/app/ |
| RabbitMQ Management | http://32.194.66.142:15672 (nodo 2: `:15673`), usuario `pedidos360` |
| Logs de `orders` y `notify` | consola de AWS → Session Manager → comando `logs-demo` |
| Postman (pruebas 1, 2 y 3) | environment **Pedidos360 EP2 - EC2**, con el token de un usuario Admin |

**1. Inicia el lab** en AWS Academy. La EC2 arranca sola y los contenedores vuelven solos; no hace falta desplegar. **Espera unos 3 minutos antes de abrir la aplicación**: pedidos y catálogo tardan en conectarse a Oracle, y si el panel de administración pide los pedidos antes, los muestra vacíos. Si pasa, basta con recargar la página.

**2. Terminal de la EC2 con los logs** (la que pide la guía en los pasos 3 y 4):

1. En la consola de AWS: **EC2 → Instancias → `pedidos360-bff` → Conectar**.
2. Pestaña **Session Manager → Conectar**. Se abre una terminal en el navegador.
3. Escribe:

```
logs-demo
```

Muestra en vivo los logs de `ms-pedidos360-orders` (productor) y `ms-pedidos360-notify` (consumidor), cada línea con el nombre de su contenedor. `Ctrl+C` para salir.

**3. Management** en http://32.194.66.142:15672, pestaña **Queues**. El usuario es `pedidos360`; la contraseña está en la EC2. Para verla, en la misma terminal de Session Manager:

```
sudo grep RABBITMQ_PASS /opt/pedidos360-ep2/despliegue/.env
```

En la EC2 **no** existe `guest`/`guest`.

**4. Token para Postman**, con el usuario **Admin** (es el que puede aceptar pedidos y usar la API administradora):

```powershell
cd pedidos360-ep2\pruebas ; .\obtener-token.ps1 -Cuenta "Admin@luissantacruz2026.onmicrosoft.com"
```

Se abre el navegador con el login de Microsoft: la contraseña se escribe ahí, no en PowerShell. El token queda copiado al portapapeles. Usa `-Cuenta` y no `-Usuario`: ese otro modo pide la contraseña en la consola y Entra ID lo rechaza con `AADSTS7000218`. En Postman, elige el environment **Pedidos360 EP2 - EC2** y pega el token en la variable `token`. Dura una hora.

**5. Purga las DLQ** en Postman, carpeta **0. Preparacion**, para que la prueba 2 muestre un mensaje nuevo y no uno viejo. Después sigue en **Prueba 1** más abajo: los pedidos se crean y se aceptan desde Postman, y la prueba 3 usa las mismas peticiones que en local.

> **Las pruebas 1 y 2 pueden hacerse enteras desde la aplicación**, como sugiere la guía, entrando como **Admin**:
>
> 1. **Catálogo → Agregar al pedido** en uno o más productos.
> 2. **Mi pedido**: en *Correo del cliente* deja un correo cualquiera para la prueba 1, o escribe `error-permanente@pedidos360.cl` para la prueba 2. **Confirmar pedido.** La pantalla muestra el código del pedido creado, en estado `CREADO`.
> 3. **Panel Admin → Avanzar Estado** sobre ese pedido. Esa es la operación que publica el mensaje (`accept`): en la terminal de `logs-demo` aparece el ACK, o el NACK y la DLQ si usaste el correo de error.
>
> Un Cliente también puede comprar, pero el campo de correo no le aparece: el pedido queda siempre a su nombre, y el BFF lo garantiza aunque el navegador mande otro. La prueba 3 (RabbitAdmin) sigue siendo en Postman.

> **Antes de la presentación**, confirma en la consola de OCI que la base `pedidos360` esté **Available**. Las bases Always Free se detienen solas tras 7 días sin uso, y si está detenida, catálogo y pedidos no arrancan.

**Desde tu notebook** también puedes usar los scripts: `.\07-desplegar-ep2.ps1` autoriza tu IP y vuelve a desplegar, `.\08-tunel-demo.ps1 -Logs` muestra los logs por SSH, y `.\08-tunel-demo.ps1` abre el túnel para usar el environment *local* contra la EC2.

---

## Antes de empezar — en local (para ensayar)

**1. Clúster de dos nodos:**

```powershell
cd C:\dev\pedidos360-ep2\rabbitmq-cluster ; .\levantar-cluster.ps1
```

Tiene que decir `Los dos nodos estan en el cluster.`

**2. Los cuatro microservicios**, cada uno en su terminal. El catálogo primero:

```powershell
java -jar ms-pedidos360-catalog/target/ms-pedidos360-catalog.jar
```
```powershell
java -jar ms-pedidos360-orders/target/ms-pedidos360-orders.jar
```
```powershell
java -jar ms-pedidos360-notify/target/ms-pedidos360-notify.jar
```
```powershell
java -jar ms-pedidos360-rabbitadmin/target/ms-pedidos360-rabbitadmin.jar
```

**Deja las terminales de `orders` y de `notify` a la vista**: son la evidencia de las pruebas 1 y 2.

**3. Management abierto** en http://localhost:15672 (`guest`/`guest`), en la pestaña **Queues**.

**4. Postman** con la colección `Pedidos360-EP2` y su environment cargados.

**5. Purga las DLQ** para que la prueba 2 muestre un mensaje nuevo y no uno viejo:

```powershell
cd C:\dev\pedidos360-ep2\pruebas
foreach ($c in "q.cmd.email.dlq","q.cmd.kitchen.dlq","q.cmd.invoice.dlq") {
  Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8084/api/rabbitmq/queues/$c/purge" | Out-Null
}
```

**Ensayo completo automatizado**, si quieres comprobar que todo responde antes de presentar:

```powershell
cd C:\dev\pedidos360-ep2\pruebas ; .\verificar-ep2.ps1
```

---

## Prueba 1 — Camino exitoso: Productor → RabbitMQ → Consumidor → ACK

### Paso 1. Mostrar el clúster

> **Login de Management.** En la EC2 el usuario es **`pedidos360`** con la contraseña que muestra la ventana del túnel, no `guest`/`guest`: ese usuario se elimina al desplegar. Si la página dice *Not_Authorized* con `guest`, es esto, y de paso confirma que el túnel llega a la EC2. En local sí es `guest`/`guest`.

En Management, pestaña **Overview** → **Nodes**: los dos nodos.

> «Este es el clúster RabbitMQ que usan los microservicios.»

No hace falta explicar la configuración otra vez.

### Paso 2. Ejecutar una operación real del sistema

**No crear el mensaje a mano en Management.** La prueba arranca en una operación de `ms-pedidos360-orders`.

Crear un pedido y aceptarlo:

```powershell
$catalogo = Invoke-RestMethod "http://127.0.0.1:8082/api/catalog"
$producto = ($catalogo | Where-Object { $_.sku -eq "SKU-001" })[0]

$pedido = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8081/api/orders" `
  -ContentType "application/json" -Body (@{
      clienteEmail     = "cliente@duoc.cl"
      clienteNombre    = "Cliente Demo"
      direccionEntrega = "Av. Siempre Viva 742"
      items            = @(@{ productoId = $producto.id; cantidad = 1 })
  } | ConvertTo-Json)

Write-Host "Pedido $($pedido.codigo)"

Invoke-RestMethod -Method Put -Uri "http://127.0.0.1:8081/api/orders/$($pedido.id)/accept"
```

Aceptar es la operación que publica. Genera el mensaje con **Exchange `cmd.direct`**, **Routing Key `email.send`**, **Queue `q.cmd.email`**, que es justo lo que pide la guía.

### Paso 3. Señalar el log del productor

En la terminal de **`orders`**:

```
Publicando mensaje | Exchange: cmd.direct | Routing Key: email.send | eventId: 3f2a... | correlationId: PED-20261015-A1B2C3D4
Mensaje publicado | tipo: EMAIL | pedido: PED-20261015-A1B2C3D4
```

> «El productor publicó al exchange `cmd.direct` con la routing key `email.send`. El `correlationId` es el código del pedido, así que los mensajes del mismo pedido se pueden seguir juntos.»

### Paso 4. Señalar el log del consumidor

En la terminal de **`notify`**:

```
MENSAJE RECIBIDO | Queue: q.cmd.email | Routing Key: email.send | tipo: EMAIL | pedido: PED-... | eventId: 3f2a... | correlationId: PED-... | Delivery Tag: 1
CORREO ENVIADO | para: cliente@duoc.cl | pedido: PED-... | estado: ACEPTADO | total: 500000.00
PROCESAMIENTO EXITOSO | Queue: q.cmd.email | Routing Key: email.send | eventId: 3f2a... | pedido: PED-... | intento 1 de 4
```

> «El mismo `eventId` que publicó el productor llegó al consumidor por la cola `q.cmd.email`, y se procesó bien en el primer intento.»

### Paso 5. Señalar el ACK

```
ACK MANUAL ENVIADO | Queue: q.cmd.email | eventId: 3f2a... | Delivery Tag: 1
```

Y en Management, `q.cmd.email` con **Ready = 0**.

> «La confirmación es manual: el mensaje recién sale de la cola cuando el código llama a `basicAck`. Si el consumidor se cayera a mitad del procesamiento, el mensaje seguiría en la cola.»

**Extra, si hay tiempo**: aceptar también publicó por `cmd.topic` con `kitchen.ticket.new`, que entra a `q.cmd.kitchen` por el patrón `kitchen.#`. Los dos tipos de exchange en una sola operación.

---

## Prueba 2 — Error → NACK/reintento → DLQ

### Paso 1. Ejecutar la operación con el dato de prueba

El error se provoca **con los datos de entrada**, no tocando nada:

```powershell
$pedidoError = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8081/api/orders" `
  -ContentType "application/json" -Body (@{
      clienteEmail     = "error-permanente@pedidos360.cl"
      clienteNombre    = "Cliente Con Correo Invalido"
      direccionEntrega = "Av. Siempre Viva 742"
      items            = @(@{ productoId = $producto.id; cantidad = 1 })
  } | ConvertTo-Json)

Invoke-RestMethod -Method Put -Uri "http://127.0.0.1:8081/api/orders/$($pedidoError.id)/accept"
```

> «El pedido se acepta igual: el negocio no se entera de que la notificación falló, y así tiene que ser —de eso se trata desacoplar. El problema se resuelve por el lado de la DLQ.»

### Paso 2. Señalar el log

En la terminal de **`notify`**:

```
MENSAJE RECIBIDO | Queue: q.cmd.email | Routing Key: email.send | ... | eventId: a3f0...
ERROR NO RECUPERABLE | Queue: q.cmd.email | eventId: a3f0... | motivo: Envio de correo: la direccion error-permanente@pedidos360.cl no existe. Reintentar no va a cambiar el resultado | no se reintenta
NACK SIN REQUEUE ENVIADO | Queue: q.cmd.email | eventId: a3f0... | el mensaje sale por cmd.dead.dlx hacia q.cmd.email.dlq
```

Las tres cosas que la guía pide identificar: **el mensaje llegó**, **hubo un error**, **se aplicó NACK**.

> «La dirección no existe, así que reintentar daría exactamente el mismo resultado: se rechaza de inmediato. `requeue=false` es lo que evita el bucle infinito —con `true`, el mensaje volvería a la cola, fallaría igual y giraría para siempre bloqueando a los que vienen detrás.»

### Paso 3. Mostrar la DLQ

En Management, **Queues** → `q.cmd.email.dlq` con **Ready ≥ 1**. Abrirla, **Get messages** → **Get Message(s)**, y mostrar el JSON con el `eventId` del log.

> «El mismo mensaje que el consumidor rechazó está acá, íntegro, para que alguien lo revise.»

### Si quieres mostrar también el reintento con backoff

Mismo flujo con `error-transitorio@pedidos360.cl`. Tarda unos 7 segundos y el log muestra:

```
ERROR RECUPERABLE | ... | intento 1 de 4 | motivo: el proveedor no responde ... | reintento en 1000 ms
ERROR RECUPERABLE | ... | intento 2 de 4 | ... | reintento en 2000 ms
ERROR RECUPERABLE | ... | intento 3 de 4 | ... | reintento en 4000 ms
REINTENTOS AGOTADOS | ... | 4 intentos | ...
NACK SIN REQUEUE ENVIADO | ...
```

> «Acá la falla es del entorno, no del dato: el proveedor podría responder en el siguiente intento. Se reintenta con backoff creciente —1, 2, 4 segundos— porque si el proveedor está saturado, golpearlo cada 100 ms lo empeora. Recién al agotar los reintentos va a la DLQ.»

**Distinguir las dos clases de falla es la decisión de diseño que conviene explicar**: reintentar cuatro veces un mensaje que nunca va a poder procesarse solo gasta el tiempo del consumidor y, con `prefetch=1`, retrasa a los mensajes que sí se pueden procesar.

---

## Prueba 3 — RabbitAdmin: operación válida + entrada inválida

### Paso 1. Operación válida

En Postman, carpeta **4. Administracion de recursos** → **Crear cola valida (201)**:

```
POST http://127.0.0.1:8084/api/rabbitmq/queues?queueName=q.test.pedidos360
```

Respuesta **201**:

```json
{ "operacion": "crear", "recurso": "cola", "nombre": "q.test.pedidos360" }
```

Y en Management, **Queues**: `q.test.pedidos360` aparece en la lista.

### Paso 2. Operación inválida

Carpeta **5. Validacion: HTTP 400** → **Cola con nombre vacio (400)**:

```
POST http://127.0.0.1:8084/api/rabbitmq/queues?queueName=
```

Respuesta **400 Bad Request**:

```json
{ "error": "queueName es obligatorio", "parametro": "queueName" }
```

Y en Management, **no se creó nada**.

> «La validación corre en el controlador, antes de abrir una conexión al broker. Importa que sea antes: en RabbitMQ una cola con nombre vacío no es un error, es una cola con nombre generado por el servidor. Si dejáramos pasar el parámetro vacío, se crearía un recurso que nadie pidió.»

### Limpiar después (opcional)

```
DELETE http://127.0.0.1:8084/api/rabbitmq/queues/q.test.pedidos360
```

---

## Si te preguntan

**«¿Por qué dos mensajes al aceptar?»** Son dos trabajos distintos, de dueños distintos: avisar al cliente y encolar el ticket en cocina. Comparten `correlationId` para poder seguirlos juntos.

**«¿Por qué `cmd.topic` y no todo por `direct`?»** `kitchen.ticket.new` calza con el patrón `kitchen.#`, no por coincidencia exacta. Demuestra el segundo tipo de exchange con una clave real del flujo, no con un ejemplo inventado.

**«¿Dónde está la publicación en el código?»** `PedidoService.aceptar()`, al final del método —después de que la transición y el descuento de stock ya tuvieron éxito. Si el catálogo rechaza por stock, la excepción propaga antes y no se publica nada: nunca se le avisa a un cliente de un pedido que en realidad no se aceptó.

**«¿Por qué no usas el retry de Spring por propiedades?»** Porque es incompatible con la confirmación manual. Al agotar los reintentos, el recoverer lanza `AmqpRejectAndDontRequeueException`, pero el contenedor la envuelve en `ListenerExecutionFailedException` y en modo manual ya no la reconoce como rechazo: el mensaje queda colgado en `Unacked` y con `prefetch=1` detiene la cola entera. Lo probamos contra el clúster —`Unacked=1`, `Ready=1`, DLQ vacía indefinidamente— y por eso el reintento lo hace `EjecutorDeConsumo`, con los mismos valores de las guías.

**«¿Por qué las DLQ no tienen `x-dead-letter-exchange`?»** Porque un mensaje que ya falló no debe poder reencolarse otra vez: se formaría un ciclo. Sí tienen TTL, más largo que el de la cola principal (24 h contra 10 min), porque un mensaje muerto tiene que sobrevivir lo suficiente para que alguien lo revise.

**«¿Qué pasa si se cae un nodo del clúster?»** Los microservicios se conectan con `spring.rabbitmq.addresses` apuntando a los dos endpoints, así que Spring reconecta al otro nodo sin intervención.
