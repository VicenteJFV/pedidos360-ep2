# Pedidos360 — RabbitMQ, AWS y Microservicios (EP2 DSY1107)

Caso 0 — Pedidos360. Este repositorio es la **evolución de EP1 incorporando RabbitMQ**: conserva el dominio, los microservicios, la seguridad y la persistencia de la primera entrega, y les agrega la mensajería que el caso ya definía en el diseño.

```
pedidos360-ep2/
├── ms-pedidos360-orders/       Microservicio principal (8081) — pedidos, estados y PRODUCTOR
├── ms-pedidos360-catalog/      Microservicio de catálogo (8082) — productos y stock
├── ms-pedidos360-notify/       Consumidor RabbitMQ (8083) — sin base de datos        [EP2]
├── ms-pedidos360-rabbitadmin/  Administrador de RabbitMQ (8084) — REST + RabbitAdmin [EP2]
├── rabbitmq-cluster/           Clúster de dos nodos con Docker Compose               [EP2]
├── aws/                        Scripts de infraestructura y API Gateway
└── pruebas/                    Verificación local y de EP2, token de Entra ID y Postman
```

`ms-pedidos360-orders` sigue siendo el dueño del negocio y ahora además publica eventos; los otros dos microservicios son nuevos. **El flujo de EP1 no se modificó**: sigue siendo síncrono donde corresponde, y la mensajería se limita a lo que el caso define como asíncrono —notificaciones, tickets de cocina y generación de documentos.

## Contrato de integración

El BFF y el frontend Angular viven en repositorios aparte. El punto de contacto entre las tres piezas es este:

| Componente | Dirección | Expuesto |
| :--- | :--- | :--- |
| AWS API Gateway | `https://xpglku1pj5.execute-api.us-east-1.amazonaws.com/Desarrollo` | público — único punto de entrada |
| BFF | `http://<IP_EC2>:8080` | solo para el API Gateway |
| `ms-pedidos360-orders` | `http://127.0.0.1:8081` | solo loopback |
| `ms-pedidos360-catalog` | `http://127.0.0.1:8082` | solo loopback |

El API Gateway valida el JWT de Entra ID (`issuer` + `audience`), el BFF valida firma, vigencia y **autorización por rol**, y los microservicios resuelven el dominio y la persistencia. Los microservicios no evalúan roles: no ven el token.

### Códigos de estado del dominio

| Código | Cuándo |
| :--- | :--- |
| `400` | Validación de entrada, y **transición de estado inválida** |
| `404` | El pedido o producto no existe |
| `409` | Stock insuficiente, SKU duplicado, conflicto de concurrencia |
| `503` | El microservicio de catálogo no responde |

La transición inválida devuelve `400` porque así lo exige la matriz de pruebas de la pauta (`PUT /api/orders/{id}/ship` con estado no apto). Semánticamente `409` sería más preciso —el recurso existe y la petición está bien formada, lo que falla es el estado— y así está documentado en `ManejadorGlobalErrores`.

---

# EP2 — Mensajería con RabbitMQ

## Topología del caso

Los nombres son exactamente los que define la guía de EP2. No son decorativos: la evaluación se hace sobre ellos.

| Cola principal | Propósito | DLQ | Binding direct | Binding topic |
| :--- | :--- | :--- | :--- | :--- |
| `q.cmd.email` | Envío de correos/push | `q.cmd.email.dlq` | `email.send` | `email.*` |
| `q.cmd.kitchen` | Ticket/impresión cocina | `q.cmd.kitchen.dlq` | `kitchen.ticket` | `kitchen.#` |
| `q.cmd.invoice` | Generación de PDF/boleta/factura | `q.cmd.invoice.dlq` | `invoice.gen` | `invoice.*` |

Exchanges comunes: **`cmd.direct`** (direct, coincidencia exacta), **`cmd.topic`** (topic, patrones `*` y `#`) y **`cmd.dead.dlx`** (direct, recibe los mensajes rechazados).

Las tres colas y las tres DLQ se declaran en una sola clase por microservicio, [`RabbitMQConfig`](ms-pedidos360-orders/src/main/java/cl/duoc/pedidos360/orders/messaging/RabbitMQConfig.java), y **ninguna cadena literal aparece en la lógica de negocio**. Está duplicada a propósito en `orders` y en `notify`: cada microservicio se despliega por separado y debe poder arrancar solo, sin esperar a que el otro haya creado las colas. El precio es que las dos declaraciones tienen que coincidir exactamente en durabilidad y argumentos, o RabbitMQ rechaza la segunda con `PRECONDITION_FAILED - inequivalent arg`.

### Qué publica cada acción del negocio

La publicación está enganchada al flujo real y va **siempre al final del método**, cuando la transición y el descuento de stock ya tuvieron éxito. Si el catálogo rechaza por falta de stock, la excepción propaga antes y no se emite ningún mensaje: nunca se le avisa a un cliente de un pedido que en realidad no se aceptó.

| Acción | Exchange | Routing key | Cola | Por qué |
| :--- | :--- | :--- | :--- | :--- |
| `PUT /accept` | `cmd.direct` | `email.send` | `q.cmd.email` | avisar al cliente |
| `PUT /accept` | `cmd.topic` | `kitchen.ticket.new` | `q.cmd.kitchen` | encolar el ticket en cocina |
| `PUT /ship` | `cmd.direct` | `email.send` | `q.cmd.email` | avisar que el pedido salió |
| `PUT /deliver` | `cmd.direct` | `invoice.gen` | `q.cmd.invoice` | pedir el documento de venta |

Aceptar genera **dos** mensajes porque son dos trabajos distintos, de dueños distintos. Comparten `correlationId` —el código del pedido— para poder seguirlos juntos en los logs. `kitchen.ticket.new` se publica por `cmd.topic` a propósito: demuestra el segundo tipo de exchange con una clave que calza por patrón (`kitchen.#`) y no por coincidencia exacta.

Preparar no publica nada: el ticket ya salió al aceptar, y repetirlo sería duplicar el trabajo.

### ACK, NACK, reintento y DLQ

`ms-pedidos360-notify` tiene **un consumidor por dominio funcional** y confirmación manual: el mensaje solo se da por procesado cuando el código llama a `basicAck`. La decisión vive en un solo lugar, [`EjecutorDeConsumo`](ms-pedidos360-notify/src/main/java/cl/duoc/pedidos360/notify/consumer/EjecutorDeConsumo.java), porque un ACK de más o de menos es un error silencioso y caro.

| Situación | Acción | Evidencia en el log |
| :--- | :--- | :--- |
| Procesamiento correcto | `basicAck(deliveryTag, false)` | `ACK MANUAL ENVIADO` |
| Error **permanente** (dirección inexistente, mensaje incompleto) | `basicNack(..., false, false)` de inmediato | `ERROR NO RECUPERABLE` + `NACK SIN REQUEUE ENVIADO` |
| Error **transitorio** (proveedor caído) | 3 reintentos a 1 s, 2 s y 4 s | `ERROR RECUPERABLE ... intento 2 de 4 ... reintento en 2000 ms` |
| Reintentos agotados | `basicNack` → DLQ | `REINTENTOS AGOTADOS` + `NACK SIN REQUEUE ENVIADO` |
| Excepción inesperada | `basicNack` + traza completa | `ERROR INESPERADO` |

Distinguir las dos clases de falla no es adorno: reintentar cuatro veces un mensaje que nunca va a poder procesarse solo gasta el tiempo del consumidor y, con `prefetch=1`, retrasa a los mensajes que sí se pueden procesar.

`requeue=false` es lo que evita el ciclo infinito. Con `true`, el mensaje volvería a la misma cola, se volvería a entregar, fallaría por la misma razón y giraría para siempre.

> **Por qué el reintento es propio y no `spring.rabbitmq.listener.simple.retry.*`**
>
> El reintento del contenedor **es incompatible con la confirmación manual**. Al agotar los reintentos, `RejectAndDontRequeueRecoverer` lanza `AmqpRejectAndDontRequeueException`, pero el manejador de errores la envuelve en `ListenerExecutionFailedException`, y en modo manual el contenedor solo rechaza por su cuenta cuando la excepción que recibe **es** de rechazo, no cuando viene envuelta. El mensaje entonces no se confirma ni se rechaza: queda colgado en `Unacked`, y con `prefetch=1` detiene la cola completa detrás de ese único mensaje.
>
> No es una suposición. Se probó contra el clúster con la configuración de las guías: la cola quedó en `Unacked=1` y `Ready=1` de forma indefinida, con la DLQ vacía. Por eso los reintentos se hacen en `EjecutorDeConsumo`, donde el mismo código que reintenta es el que después rechaza, con los mismos valores de las guías (`notify.reintentos.*` en `application.properties`).

### El error controlado se dispara con un dato, no con código

La prueba de error tiene que poder ejecutarse en vivo durante la presentación sin tocar código, sin reiniciar servicios y sin apagar contenedores. El disparador es el **correo del cliente del pedido**:

| Correo del pedido | Qué provoca |
| :--- | :--- |
| cualquiera | procesamiento normal → ACK |
| `error-permanente@pedidos360.cl` | falla definitiva → NACK inmediato → DLQ |
| `error-transitorio@pedidos360.cl` | falla recuperable → 4 intentos → DLQ |

Es el **dato de prueba** que la guía de prueba empírica pide tener definido y preparado de antemano. El guion completo está en [`pruebas/DEMOSTRACION.md`](pruebas/DEMOSTRACION.md).

Se crea el pedido con ese correo, se acepta, y el consumidor falla **solo para ese mensaje** mientras los demás siguen pasando. Un pedido con `error-permanente@` deja un mensaje en cada una de las tres DLQ a lo largo del ciclo completo.

### Retención y limpieza

| Argumento | Colas funcionales | DLQ | Por qué |
| :--- | :--- | :--- | :--- |
| `x-message-ttl` | 10 min | 24 h | Una notificación que no se envió en 10 minutos ya perdió sentido; un mensaje muerto tiene que sobrevivir lo suficiente para que alguien lo revise |
| `x-max-length` | 10 000 | 5 000 | Sin tope, un consumidor caído durante horas hace crecer la cola hasta agotar la memoria del broker |
| `x-overflow` | `reject-publish-dlx` | — | Al superar el tope, el mensaje va al DLX en vez de perderse en silencio |
| `x-dead-letter-exchange` | `cmd.dead.dlx` | **ninguno** | Las DLQ no reencolan: un mensaje que ya falló no debe poder fallar otra vez, o se formaría un ciclo |

## Puesta en marcha de EP2 (local)

**1. Clúster de dos nodos** (necesita Docker Desktop corriendo):

```powershell
cd C:\dev\pedidos360-ep2\rabbitmq-cluster ; .\levantar-cluster.ps1
```

El script es idempotente: arranca los contenedores, espera a que ambos nodos respondan, une `rabbitmq-2` al clúster solo si hace falta e imprime el `cluster_status`. Management queda en **http://localhost:15672** (nodo 1) y **http://localhost:15673** (nodo 2), `guest`/`guest`.

> Si tienes el contenedor de las actividades anteriores (`rabbitmq-resiliencia`) ocupando el puerto 5672, el script lo detiene y te dice cómo reactivarlo.

**2. Compilar y verificar todo de una vez:**

```powershell
cd C:\dev\pedidos360-ep2\pruebas ; .\verificar-ep2.ps1 -Compilar
```

Arranca los cuatro microservicios, ejecuta las pruebas obligatorias de la guía —flujo normal con ACK, error controlado con NACK y DLQ, administración de recursos, validación con HTTP 400, métricas y retención— y apaga todo al terminar. Los logs quedan en `logs/`; **el de `notify` es el que conviene mostrar en la presentación**, porque ahí aparecen los ACK, los reintentos y los NACK con su `eventId`.

**3. Para la demostración en vivo**, con el clúster arriba, en cuatro terminales:

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

El catálogo primero, porque pedidos lo consulta al crear un pedido. `notify` conviene dejarlo a la vista: su consola es la evidencia.

**4. El guion de la prueba empírica** está en [`pruebas/DEMOSTRACION.md`](pruebas/DEMOSTRACION.md): las tres demostraciones obligatorias paso a paso, con los comandos exactos, las líneas de log que hay que señalar y las respuestas a las preguntas que es razonable esperar.

**5. Postman**: importar [`pruebas/Pedidos360-EP2.postman_collection.json`](pruebas/Pedidos360-EP2.postman_collection.json) y su environment, y correr con **Collection Runner, la colección completa y `Delay = 1000 ms`**. Dos comprobaciones repiten su petición hasta que el consumidor termina, y eso usa `setNextRequest`, que solo funciona dentro del Runner.

## Despliegue de EP2 en la EC2

La guía exige que *«el Frontend Angular, BFF y microservicios deberán estar desplegados en EC2»* con Docker Compose, y que la prueba empírica se haga sobre la solución desplegada. En la EC2 corre un solo Compose, [`despliegue/docker-compose.yml`](despliegue/docker-compose.yml), con toda la solución; la base está en Oracle Cloud.

```powershell
cd C:\dev\pedidos360-ep2\aws ; .\07-desplegar-ep2.ps1
```

| Servicio | En la EC2 | Acceso |
| :--- | :--- | :--- |
| Frontend Angular (nginx) | `:80` | **https://xpglku1pj5.execute-api.us-east-1.amazonaws.com/Desarrollo/app/** |
| BFF | `:8080` | por el API Gateway, rutas `/api/...` con JWT |
| RabbitMQ Management | `:15672` y `:15673` | **http://32.194.66.142:15672**, con login |
| API administradora (`rabbitadmin`) | `127.0.0.1` | por el API Gateway en `/api/rabbitmq/...`, con JWT y **solo rol Admin** (lo reenvía el BFF) |
| `orders`, `catalog`, `notify` | `127.0.0.1` | solo a través del BFF |
| Logs y terminal de la EC2 | — | **Session Manager** desde la consola de AWS, comando `logs-demo` |

| Decisión | Por qué |
| :--- | :--- |
| El frontend se entra por el API Gateway (`/app/`) | Entra ID no acepta direcciones de retorno `http://` fuera de `localhost`, y MSAL necesita HTTPS para PKCE. El Gateway da HTTPS con certificado válido, comparte origen con `/api/...` y sigue siendo el **único punto de entrada**, como en EP1 |
| **CORS en el API Gateway**, no en el código | Lo pidió el profesor en la evaluación de EP1. El Gateway responde el preflight y agrega las cabeceras; se eliminaron las rutas `OPTIONS` que EP1 reenviaba al BFF. Ver [`02-desplegar-api-gateway.ps1`](aws/02-desplegar-api-gateway.ps1) |
| `rabbitadmin` detrás del Gateway y del BFF, solo Admin | No tiene autenticación propia y puede borrar colas: no se publica directo. Entra como el resto de la API —JWT en el Gateway, rol en el BFF—, igual que en EP1 |
| Terminal por Session Manager (`LabInstanceProfile`) | Logs desde el navegador, sin llave SSH ni puerto 22: la demo funciona desde cualquier PC |
| Management publicado como en las guías (`"15672:15672"`) | Protegido por su login. No existe `guest/guest`: usuario y cookie del clúster se generan en la EC2 y no están en git |
| La EC2 compila; se sube el código, no los JAR | El paquete pesa unos 170 KB en lugar de 144 MB. Las dependencias las baja la EC2 por la red de AWS |
| Contraseña de Oracle por la entrada estándar de SSH | No aparece en la línea de comandos ni en un archivo del portátil |
| Instalación desacoplada con `nohup` | Si la red corta la sesión SSH, la instalación sigue |
| Catálogo y pedidos se detienen ante `ORA-01017` | Con `restart: unless-stopped`, una contraseña equivocada produce cientos de logins fallidos y bloquea ADMIN. Con la protección: un intento por servicio |

Verificado: los seis servicios responden; los contenedores vuelven solos cuando el lab reinicia la EC2; desde fuera, el frontend carga por HTTPS, la API sin token da 401 (con su cabecera CORS), Management pide login y `rabbitadmin` no es alcanzable.

**Pendiente fuera del código:** registrar `https://xpglku1pj5.execute-api.us-east-1.amazonaws.com/Desarrollo/app/` como dirección de retorno (plataforma SPA) en la app de Entra ID del frontend. Hasta entonces el login funciona desde `localhost:4200`, pero no desde el frontend desplegado.

## API del microservicio administrador

`RabbitMQController` valida y traduce a HTTP; `RabbitResourceManager` encapsula `RabbitAdmin` y es el único que habla con el broker.

| Operación | Endpoint | Validación |
| :--- | :--- | :--- |
| Crear cola | `POST /api/rabbitmq/queues?queueName=` | `queueName` no vacío → `201` |
| Consultar cola | `GET /api/rabbitmq/queues/{queueName}` | `404` si no existe |
| Eliminar cola | `DELETE /api/rabbitmq/queues/{queueName}` | `200`, idempotente |
| Purgar cola | `POST /api/rabbitmq/queues/{queueName}/purge` | informa cuántos descartó |
| Crear exchange | `POST /api/rabbitmq/exchanges?exchangeName=&type=` | tipo en `direct, topic, fanout, headers` → `201` |
| Eliminar exchange | `DELETE /api/rabbitmq/exchanges/{exchangeName}` | `200`, idempotente |
| Crear binding | `POST /api/rabbitmq/bindings?queueName=&exchangeName=&routingKey=` | los tres obligatorios → `201` |
| Eliminar binding | `DELETE /api/rabbitmq/bindings?queueName=&exchangeName=&routingKey=` | los tres obligatorios |
| Métricas | `GET /api/rabbitmq/metricas` | — |

Un parámetro ausente o en blanco devuelve `400` **nombrando el parámetro**, y lo hace antes de abrir una conexión. Importa que sea antes: declarar una cola con nombre vacío no es un error en RabbitMQ, es una cola con nombre generado por el servidor, y eso no es lo que nadie quiso pedir.

### Métricas y qué decisión permite tomar cada una

| Métrica | Cómo interpretarla |
| :--- | :--- |
| `colasActivas` / `colasEsperadas` | Comprueba que la topología está desplegada. Si no son 6 de 6, falta arrancar `orders` o `notify` |
| `mensajesPendientes` | Un crecimiento sostenido indica que el consumidor no da abasto, o que está caído |
| `consumidores` | Verifica que los listeners están activos. `0` con mensajes en cola es una alarma |
| `mensajesEnDlq` | Cada uno es un mensaje que no se pudo procesar y que alguien tiene que revisar |
| `tasaDlq` | Una tasa que sube delata un problema recurrente, no un incidente aislado |

Las métricas se obtienen preguntando por cada cola conocida y no listando el broker completo, porque `RabbitAdmin` trabaja sobre AMQP y **AMQP no tiene una operación para listar colas**: eso solo lo ofrece la API de Management, que exigiría credenciales HTTP aparte. Preguntar por nombre alcanza, porque las colas del caso son seis y están definidas.

### Dos detalles que se decidieron mirando el broker

**Los bindings se construyen con `new Binding(...)`** y no con `BindingBuilder.bind(cola).to(new DirectExchange(nombre))`, como aparece en los ejemplos. El tipo del exchange local **nunca viaja al broker**: un binding lleva nombre de exchange, destino y routing key, nada más. Escribir `DirectExchange` para enlazar una cola a `cmd.topic` funciona igual, pero deja el código afirmando algo que no es cierto.

**El borrado no informa si el recurso existía.** `RabbitAdmin.deleteQueue` devuelve un `boolean`, pero ese `boolean` no significa eso: se comprobó contra el clúster que devuelve `true` también cuando la cola nunca estuvo. Para saber si una cola existe, se la consulta —y el `GET` responde `404` después de eliminarla, que es la evidencia real.

El microservicio administrador **no declara topología al arrancar** (`spring.rabbitmq.dynamic=false` y `setAutoStartup(false)`). Si lo hiciera, bastaría reiniciarlo para resucitar una cola que alguien acaba de eliminar a propósito, y las pruebas de eliminación dejarían de significar algo.

---

## Puesta en marcha (AWS, EP1)

> **La base de RDS ya no existe.** Se eliminó el 15 de octubre de 2026: en EP2 la persistencia pasa a Oracle Cloud (Autonomous Database). Los pasos de esta sección que la arrancan o la detienen quedan como registro de cómo funcionaba EP1.
>
> El motivo fue el costo. Detenerla no alcanzaba: una instancia RDS detenida sigue cobrando su almacenamiento, y **a los 7 días AWS la vuelve a encender sola**. Eso pasó el 5 de octubre y la base quedó diez días corriendo con licencia Oracle SE2, que se llevó la mayor parte del crédito del Learner Lab. Para revisar que nada más esté cobrando: `aws/05-revisar-gasto.ps1`.


El entorno vive en AWS Academy Learner Lab, cuya sesión caduca cada ~4 horas: la EC2 se detiene sola y la base hay que arrancarla a mano. Estos cinco pasos dejan todo operativo. **Cuentan unos 10 minutos**, casi todos esperando a Oracle.

**1. Iniciar el lab** en AWS Academy y recargar las credenciales:

```powershell
cd C:\dev\pedidos360\aws ; .\01-credenciales-learner-lab.ps1
```

**2. Arrancar la base primero**, que es lo más lento (~5 min):

```powershell
aws rds start-db-instance --db-instance-identifier pedidos360-oracle --region us-east-1
```

**3. Arrancar la instancia** (~1 min; systemd levanta los tres servicios solo):

```powershell
aws ec2 start-instances --instance-ids i-0598cbdf24a3ab50e --region us-east-1
```

**4. Verificar** que los tres respondan:

```powershell
ssh -i C:\dev\pedidos360\aws\pedidos360-key.pem ec2-user@32.194.66.142 "systemctl is-active ms-catalog ms-orders bff"
```

Tres veces `active` y está listo.

**5. Obtener tokens** para probar:

```powershell
cd C:\dev\pedidos360\pruebas ; .\obtener-token.ps1 -Cuenta "Cliente@luissantacruz2026.onmicrosoft.com"
```

Quedan en el portapapeles. Repetir con `Admin@...`.

### Al terminar

```powershell
aws rds stop-db-instance --db-instance-identifier pedidos360-oracle --region us-east-1
```

La EC2 se detiene sola al cerrar el lab; **la base no**, y sigue consumiendo presupuesto.

**Apágala antes de cerrar el lab, no después.** Cuando la sesión del lab termina, AWS aplica la política `voc-cancel-cred` a las credenciales y deniega *todas* las llamadas —incluida la de apagar la base—, así que si se te pasa hay que volver a iniciar el lab solo para eso. El comando devuelve al instante con estado `stopping`: no hace falta esperar a que termine, AWS completa el apagado aunque cierres el lab enseguida.

RDS tampoco permite tenerla detenida más de 7 días: pasado ese plazo AWS la enciende sola. Si entre una sesión y otra pasa más de una semana, hay que entrar a apagarla de nuevo.

### Referencias fijas

| | |
| :--- | :--- |
| URL pública | `https://xpglku1pj5.execute-api.us-east-1.amazonaws.com/Desarrollo` |
| Instancia EC2 | `i-0598cbdf24a3ab50e` — IP elástica `32.194.66.142`, **no cambia** |
| Base de datos | `pedidos360-oracle`, SID `PEDIDOS`, sin acceso público |
| Región | `us-east-1` |

Al conservarse la IP elástica, **el API Gateway nunca hay que reconfigurarlo** entre sesiones del lab.

### Si algo falla

| Síntoma | Causa probable |
| :--- | :--- |
| `ExpiredToken` en cualquier comando | La sesión del lab caducó: volver al paso 1 |
| SSH da timeout | La instancia está detenida, o tu IP pública cambió y el Security Group solo admite la anterior |
| El catálogo responde `[]` | La base no terminó de arrancar; esperar y reiniciar `ms-catalog` |
| Todo responde 401 | Los tokens vencieron: duran una hora |

---

## Estado de verificación

### EP2 — mensajería

| Qué | Resultado |
| :--- | :--- |
| `mvn clean package` en los cuatro microservicios | **BUILD SUCCESS** |
| Tests de pedidos, con las 8 nuevas de mensajería | **52/52 pasando** |
| Tests de `ms-pedidos360-notify` (ACK/NACK, backoff, disparador) | **16/16 pasando** |
| Tests de `ms-pedidos360-rabbitadmin` — capa HTTP | **14/14 pasando** |
| Tests de `ms-pedidos360-rabbitadmin` — integración contra el clúster real | **9/9 pasando** |
| Clúster de dos nodos formado, ambos en *Disk Nodes* y *Running Nodes* | **OK** |
| Flujo normal contra el clúster: ACK manual en las tres colas | **OK** |
| Error permanente: NACK inmediato → DLQ | **OK** |
| Error transitorio: 4 intentos a 1 s, 2 s y 4 s → DLQ | **OK** |
| Un mensaje en cada una de las tres DLQ | **OK** |
| TTL de 10 min dead-letterizando por sí solo al expirar | **OK — observado** |
| JSON de la colección Postman de EP2 (38 peticiones) | **válido** |

Las 9 pruebas de integración declaran y eliminan recursos reales en el broker, y **se saltan solas si el clúster no está levantado**: una prueba que falla porque falta la infraestructura no informa nada útil y vuelve inutilizable el build de cualquiera que no tenga Docker corriendo.

La verificación de las tres rutas de ACK/NACK se hizo publicando mensajes directamente al broker por la API de Management y leyendo las profundidades de cola resultantes: `q.cmd.email.dlq = 2`, `q.cmd.kitchen.dlq = 1`, `q.cmd.invoice.dlq = 1`, con las tres colas principales drenadas y tres consumidores conectados.

### EP1 — sigue vigente

| Qué | Resultado |
| :--- | :--- |
| Tests de catálogo (`ProductoServiceTest`, `StockServiceTest`) | **13/13 pasando** |
| Arranque real de Tomcat y pruebas HTTP extremo a extremo | **26/26 pasando** |
| Matriz de seguridad contra AWS con tokens reales de Entra ID | **19/19 pasando** |
| Persistencia en Oracle verificada tras reiniciar los servicios | **OK** |
| Sintaxis de los scripts PowerShell | **OK** |
| JSON de la colección Postman de EP1 | **válido** |

Los tests de Maven verifican la máquina de estados, la atomicidad del descuento de stock, la consolidación de líneas, el congelado de precios y el mapeo JPA. Las 26 comprobaciones de [pruebas/verificar-local.ps1](pruebas/verificar-local.ps1) verifican lo que los tests no pueden: que Tomcat arranca, que la serialización JSON funciona, que la llamada pedidos → catálogo viaja por la red, y que los microservicios no son alcanzables fuera de loopback.

Las 19 comprobaciones de [pruebas/verificar-matriz.ps1](pruebas/verificar-matriz.ps1) recorren la cadena completa —Angular → API Gateway → BFF → microservicio → Oracle— con tokens reales obtenidos por Authorization Code + PKCE:

| Escenario | Esperado | Resultado |
| :--- | :---: | :---: |
| Sin token | 401 | ✅ |
| Token con firma inválida | 401 | ✅ |
| Acceso permitido (rol `Cliente`) | 200 | ✅ |
| Sin permiso de rol (`Cliente` sobre `/accept`) | 403 | ✅ |
| Error de regla de negocio (`/ship` sin aceptar) | 400 | ✅ |
| Preflight CORS sin token | 200 + cabeceras | ✅ |

### Persistencia

La base es **Oracle SE2 en Amazon RDS**, dentro de la misma VPC que la EC2 y sin acceso desde Internet: el Security Group solo admite el puerto 1521 desde el Security Group de la instancia. Se optó por RDS en lugar de Autonomous Database porque el registro en Oracle Cloud exige tarjeta de crédito; el motor, el driver `ojdbc11` y el dialecto de Hibernate son los mismos, y cambiar de una a otra es editar tres variables de entorno.

Prueba de persistencia ejecutada sobre la instancia: se crea un pedido, se acepta (con el consiguiente descuento de stock), se reinician **ambos** microservicios y se verifica que el pedido, su estado y el stock descontado sobreviven, sin que los datos de demostración se dupliquen.

---

## 0. Instalar el toolchain

**JDK 17 o superior.** El `pom` compila con `release 17`, así que un JDK 21 sirve igual:

```powershell
winget install --id EclipseAdoptium.Temurin.21.JDK -e --accept-package-agreements --accept-source-agreements ; winget install --id Amazon.AWSCLI -e
```

**Maven no está en el repositorio de winget**, hay que instalarlo a mano. Sin permisos de administrador:

```powershell
mkdir C:\dev\tools -Force ; curl.exe -sSL -o C:\dev\tools\maven.zip https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip ; Expand-Archive C:\dev\tools\maven.zip -DestinationPath C:\dev\tools -Force ; del C:\dev\tools\maven.zip
```

Y agrégalo al `PATH` de tu usuario de forma permanente:

```powershell
$p = [Environment]::GetEnvironmentVariable('Path','User'); [Environment]::SetEnvironmentVariable('Path', $p.TrimEnd(';') + ';C:\dev\tools\apache-maven-3.9.9\bin', 'User')
```

Cierra y vuelve a abrir PowerShell para que tome el `PATH`, y comprueba:

```bash
java -version; mvn -v; aws --version
```

### Política de ejecución de PowerShell

Windows bloquea los `.ps1` por defecto (`Restricted`). Para poder correr los scripts de este repo:

```powershell
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned
```

No necesita administrador. `RemoteSigned` permite scripts locales como estos y sigue bloqueando los descargados sin firmar. Si prefieres no cambiar nada, usa `powershell -ExecutionPolicy Bypass -File .\script.ps1` en cada invocación.

---

## 1. Compilar y correr en local

```bash
cd ms-pedidos360-catalog; mvn clean package
```

```bash
cd ms-pedidos360-orders; mvn clean package
```

Los tests corren solos en el `package`. Si quieres saltarlos, agrega `-DskipTests`.

> **Ojo con la ruta**: si el proyecto queda en una carpeta muy profunda, Windows corta las rutas en 260 caracteres y la compilación falla con errores raros de "path not found". Déjalo en algo corto como `C:\dev\pedidos360`.

En dos terminales separadas — **el catálogo primero**, porque pedidos lo consulta al arrancar un pedido:

```bash
java -jar ms-pedidos360-catalog/target/ms-pedidos360-catalog.jar
```

```bash
java -jar ms-pedidos360-orders/target/ms-pedidos360-orders.jar
```

El perfil por defecto es `dev`: base **H2 en memoria**, sin dependencias externas, con siete productos de demostración precargados. Consola web en `http://localhost:8082/h2-console` (JDBC URL `jdbc:h2:mem:catalogdb`, usuario `sa`, sin contraseña).

Prueba rápida sin pasar por AWS:

```bash
curl http://localhost:8082/api/catalog
```

```bash
curl -X POST http://localhost:8081/api/orders -H "Content-Type: application/json" -d "{\"clienteEmail\":\"test@duoc.cl\",\"items\":[{\"productoId\":1,\"cantidad\":2}]}"
```

---

## 2. Pasar a Oracle Autonomous Database

1. Crea la ADB en OCI (Always Free sirve) y descarga el wallet.
2. Descomprime el wallet, por ejemplo en `C:\oracle\wallet`.
3. Define las variables de entorno **sin escribir credenciales en ningún archivo del proyecto**:

```powershell
$env:TNS_ADMIN = "C:\oracle\wallet"
$env:ORACLE_JDBC_URL = "jdbc:oracle:thin:@pedidos360_high?TNS_ADMIN=C:\oracle\wallet"
$env:ORACLE_USER = "ADMIN"
$env:ORACLE_PASSWORD = Read-Host "Password de Oracle"
```

4. Arranca con el perfil `oracle`:

```bash
java -jar ms-pedidos360-catalog/target/ms-pedidos360-catalog.jar --spring.profiles.active=oracle
```

Hibernate crea solo las tablas (`PRODUCTO`, `PEDIDO`, `PEDIDO_ITEM`) y las secuencias. El alias `pedidos360_high` sale de `tnsnames.ora` dentro del wallet — ábrelo y usa el que corresponda a tu base.

---

## 3. Desplegar en AWS (Learner Lab)

### Cada vez que inicias el lab

```powershell
cd aws; .\01-credenciales-learner-lab.ps1
```

Pega el bloque de *AWS Details → AWS CLI*. El script escribe `~/.aws/credentials`, verifica la identidad y te dice si la cuenta permite Elastic IP.

### Primera vez

```powershell
.\00-crear-ec2.ps1
```

Crea el Security Group (solo `8080` al mundo, `22` a tu IP), el par de llaves y la instancia con Java 17 y las unidades de systemd ya listas.

```powershell
.\02-desplegar-api-gateway.ps1 -DestinoBff "http://<IP_QUE_IMPRIMIO>:8080"
```

Crea la HTTP API, el JWT Authorizer de Entra ID, las 8 rutas funcionales protegidas, las 6 rutas `OPTIONS` públicas y el stage con auto-deploy. Es idempotente: puedes volver a correrlo sin duplicar nada.

Las 8 rutas son exactamente las que el frontend consume: `GET`/`POST /api/orders`, los cuatro cambios de estado (`accept`, `prepare`, `ship`, `deliver`) y `GET`/`POST /api/catalog`. Cada una tiene su `OPTIONS` salvo las que comparten ruta base.

Al final imprime la **URL de invocación**, que es lo único que tu compañero necesita.

### En las sesiones siguientes

**No hace falta.** La instancia tiene una IP elástica (`32.194.66.142`), así que conserva su dirección entre sesiones del lab y el API Gateway nunca se toca.

Si alguna vez se pierde la IP elástica, un solo comando reapunta las 14 integraciones:

```powershell
.\03-actualizar-destino-bff.ps1 -DesdeInstancia i-0abc123def456
```

### Subir los JAR

```bash
scp -i aws/pedidos360-key.pem ms-pedidos360-catalog/target/ms-pedidos360-catalog.jar ms-pedidos360-orders/target/ms-pedidos360-orders.jar ec2-user@<IP>:/tmp/
```

```bash
ssh -i aws/pedidos360-key.pem ec2-user@<IP> "sudo mv /tmp/*.jar /opt/pedidos360/bin/ && sudo chown pedidos360:pedidos360 /opt/pedidos360/bin/*.jar && sudo systemctl enable --now ms-catalog ms-orders bff"
```

Ver logs:

```bash
ssh -i aws/pedidos360-key.pem ec2-user@<IP> "sudo journalctl -u ms-orders -n 50 --no-pager"
```

### Plan B: sin EC2

Si el lab se pone difícil el día de la defensa, corre todo local y expón solo el BFF:

```bash
cloudflared.exe tunnel --url http://localhost:8080
```

```powershell
.\03-actualizar-destino-bff.ps1 -NuevoDestinoBff "https://xxxx.trycloudflare.com"
```

---

## 4. Pruebas

### Tests automatizados (sin AWS ni Entra ID)

```bash
mvn test
```

57 tests que cubren la máquina de estados completa, la atomicidad del descuento de stock, la consolidación de líneas repetidas, el congelado de precios, la baja lógica y los filtros de listado. `PedidoServiceTest` simula el microservicio de catálogo con un mock, así que corre sin levantar nada más.

Sirven para la defensa: son la evidencia de que las reglas de negocio funcionan, independiente de que la infraestructura esté arriba.

### Verificación de punta a punta en local (sin AWS ni Entra ID)

```bash
cd C:\dev\pedidos360\pruebas ; .\verificar-local.ps1
```

Levanta los dos microservicios, ejecuta 26 comprobaciones sobre HTTP real y los apaga al terminar. A diferencia de `mvn test`, esto sí prueba que Tomcat arranca, que la serialización JSON funciona y que la comunicación pedidos → catálogo ocurre por la red.

Comprueba entre otras cosas que crear un pedido **no** descuenta stock, que aceptarlo **sí** lo descuenta, que cancelar lo repone, que no se puede despachar sin aceptar, y que los microservicios **no** son accesibles desde fuera de loopback.

Córrelo antes de subir nada a AWS: depurar en tu máquina es mucho más rápido que por SSH en una EC2.

### Token de Entra ID

```powershell
cd pruebas; .\obtener-token.ps1 -Cuenta "Cliente@luissantacruz2026.onmicrosoft.com"
```

Pide el token, lo copia al portapapeles y **diagnostica si va a pasar el authorizer de AWS**: revisa `iss`, `aud` y `scp`, y si algo está mal te dice exactamente qué tiene que cambiar tu compañero en Entra ID.

Luego importa en Postman `Pedidos360.postman_collection.json` y `Pedidos360.postman_environment.json`, rellena `baseUrl`, `tokenCliente` y `tokenAdmin`, y corre la carpeta **Matriz de seguridad** con el Collection Runner.

| Escenario | Esperado | Quién valida |
| :--- | :--- | :--- |
| Sin token | 401 | API Gateway |
| Firma inválida | 401 | API Gateway |
| Preflight `OPTIONS` | 200 + headers CORS | API Gateway / BFF |
| Rol `Cliente` en `/accept` | 403 | BFF |
| `GET /api/orders` con token válido | 200 + JSON | Flujo completo |

La carpeta **Flujo de pedido** recorre la máquina de estados completa e incluye los dos casos de regla de negocio: despachar sin aceptar (400, por la matriz de la pauta) y stock insuficiente (409).

---

## 5. Publicar en GitHub

El proyecto vive en `C:\dev\pedidos360` y **conviene dejarlo ahí**, no moverlo a `Documents\GitHub`: esa ruta tiene espacios y tilde, y sumada a los paquetes Java profundos vuelve a superar el límite de 260 caracteres de Windows que ya rompió una compilación.

```bash
winget install --id Git.Git -e --accept-package-agreements --accept-source-agreements
```

Reabre PowerShell y, la primera vez, identifícate:

```bash
git config --global user.name "Vicente Farias" ; git config --global user.email "vifarias304@gmail.com"
```

Inicializa y haz el primer commit:

```bash
cd C:\dev\pedidos360 ; git init -b main ; git add . ; git commit -m "EP1 DSY1107: microservicios, API Gateway y pruebas"
```

Antes de subir, confirma que no se coló ninguna llave:

```bash
git ls-files | Select-String -Pattern "\.pem$|wallet|credentials|salida-despliegue"
```

Ese comando **no debe devolver nada**. Si devuelve algo, no hagas push y avísame.

Luego crea el repo en github.com y conéctalo:

```bash
git remote add origin https://github.com/<TU_USUARIO>/pedidos360-ep1.git ; git push -u origin main
```

### Sobre hacerlo público

En el repositorio quedan el Tenant ID y los dos Client ID de Entra ID. **No son secretos** — son identificadores públicos que el frontend expone igual en cada petición — así que un repo público no filtra nada peligroso. Lo que sí son secretos y por eso están en `.gitignore`: las llaves `.pem`, el wallet de Oracle, las credenciales de AWS y el archivo `entorno`.

Este repositorio quedó **público** para que el docente pueda revisarlo sin gestión de permisos, que es lo que pide la entrega. La alternativa —privado con el docente como colaborador— protege algo más, porque es un tenant de pruebas con cuentas reales y no gana nada estando indexado por Google. Se descartó solo porque depende de que el docente acepte la invitación a tiempo.

---

### Repositorios del equipo

| Componente | Repositorio | Responsable |
| :--- | :--- | :--- |
| AWS + microservicios + persistencia | https://github.com/VicenteJFV/pedidos360-ep1 | Vicente |
| Frontend Angular + MSAL | https://github.com/Zerete/pedidos360_frontend | Zerete |
| BFF Spring Boot | https://github.com/Zerete/pedidos360-bff | Zerete |

Los tres son públicos, para que el docente pueda revisarlos sin gestión de permisos.

Los repositorios están separados a propósito: son responsabilidades distintas. El único punto donde se tocan es el despliegue — **el BFF se compila en la máquina de su autor y el JAR se copia a mi EC2**, porque el `user-data` instala `java-17-amazon-corretto-headless`, que es solo runtime y no trae `javac` ni Maven.

```bash
scp -i aws\pedidos360-key.pem bff-pedidos360.jar ec2-user@<IP>:/tmp/
```

```bash
ssh -i aws\pedidos360-key.pem ec2-user@<IP> "sudo mv /tmp/bff-pedidos360.jar /opt/pedidos360/bin/ && sudo chown pedidos360:pedidos360 /opt/pedidos360/bin/bff-pedidos360.jar && sudo systemctl restart bff"
```

Si prefieren compilar en la instancia, hay que agregar `java-17-amazon-corretto-devel` y `maven` al `dnf install` de [aws/ec2-user-data.sh](aws/ec2-user-data.sh).

---

## 6. Decisiones de diseño

**Los microservicios no validan JWT.** La autenticación es del API Gateway y la autorización por rol es del BFF. Lo que impide llamarlos directamente es la red: escuchan en `127.0.0.1` (`server.address`) y sus puertos no están en el Security Group. Defensa en profundidad sin duplicar lógica de seguridad en tres capas.

**El stock se descuenta al aceptar, no al crear.** Crear un pedido solo verifica disponibilidad de forma informativa. Un carrito abandonado no debe bloquear inventario.

**El descuento de stock es atómico.** Si una sola línea no alcanza, no se descuenta ninguna y se devuelve 409 con el detalle de todas las que fallaron. Las filas se bloquean con `SELECT ... FOR UPDATE` siempre en orden de id ascendente, para que dos reservas concurrentes se serialicen en vez de provocar un deadlock.

**Las transiciones de estado viven en el enum `EstadoPedido`**, no repartidas por los servicios. Es la única fuente de verdad y hace estructuralmente imposible despachar sin haber aceptado.

**En `accept` se persiste antes de llamar al catálogo.** Si el catálogo responde 409 por falta de stock, la excepción propaga, la transacción hace rollback y el pedido vuelve a `CREADO`. Queda una ventana residual conocida — si el catálogo descuenta y el commit local falla después — que resolverla de verdad exigiría un patrón saga con mensajería, fuera del alcance de esta entrega. Está documentado en el código.

**Los precios se congelan en `PEDIDO_ITEM`.** Si mañana sube el precio en el catálogo, los pedidos históricos no cambian.

**Los productos se dan de baja lógicamente**, nunca se borran: los pedidos históricos los referencian.
