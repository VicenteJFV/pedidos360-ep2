#!/bin/bash
# Instala o actualiza el despliegue de EP2 en la EC2. Se ejecuta EN la instancia,
# como root, y lo invoca aws/07-desplegar-ep2.ps1 despues de subir el paquete.
#
# Es idempotente: correrlo dos veces deja lo mismo que correrlo una. Las
# credenciales que genera (cookie de Erlang, usuario de RabbitMQ) se crean solo
# la primera vez y se conservan despues, porque cambiarlas con el cluster ya
# formado dejaria a los nodos sin poder hablarse.
#
# Espera encontrar en /opt/pedidos360-ep2:
#   despliegue/docker-compose.yml, despliegue/oracle.env
#   rabbitmq-cluster/rabbitmq-{1,2}/rabbitmq.conf
#   ms-pedidos360-*/Dockerfile, pom.xml y src/main (el codigo: la imagen
#   compila con Maven, ver el Dockerfile)
#   wallet/

set -euo pipefail

RAIZ=/opt/pedidos360-ep2
DESPLIEGUE=$RAIZ/despliegue
COMPOSE="docker compose --project-directory $DESPLIEGUE -f $DESPLIEGUE/docker-compose.yml"
UID_APP=10001

paso() { echo; echo "=== $* ==="; }

# ---------------------------------------------------------------------------
paso "Docker"
# ---------------------------------------------------------------------------
if ! command -v docker >/dev/null; then
    dnf install -y -q docker
fi
systemctl enable --now docker >/dev/null 2>&1
usermod -aG docker ec2-user

if ! docker compose version >/dev/null 2>&1; then
    VERSION=v2.29.7
    DESTINO=/usr/local/lib/docker/cli-plugins
    mkdir -p "$DESTINO"
    cd /tmp
    curl -fsSL -o docker-compose "https://github.com/docker/compose/releases/download/${VERSION}/docker-compose-linux-x86_64"
    curl -fsSL -o docker-compose.sha256 "https://github.com/docker/compose/releases/download/${VERSION}/docker-compose-linux-x86_64.sha256"
    echo "$(cut -d' ' -f1 docker-compose.sha256)  docker-compose" | sha256sum -c -
    install -m 755 docker-compose "$DESTINO/docker-compose"
    rm -f docker-compose docker-compose.sha256
fi
docker --version
docker compose version

# ---------------------------------------------------------------------------
paso "Servicios de EP1"
# ---------------------------------------------------------------------------
# Catalogo, pedidos y el BFF pasan a contenedores, como exige la guia. Si
# siguieran como servicios systemd ocuparian los puertos 8081, 8082 y 8080 y
# los contenedores no podrian publicarse. Se deshabilitan, no se borran: si
# hiciera falta volver a EP1, basta con systemctl enable --now.
for unidad in ms-catalog ms-orders bff; do
    if systemctl list-unit-files "$unidad.service" >/dev/null 2>&1; then
        systemctl disable --now "$unidad" >/dev/null 2>&1 || true
        echo "  $unidad detenido y deshabilitado"
    fi
done

# ---------------------------------------------------------------------------
paso "Credenciales"
# ---------------------------------------------------------------------------
test -s "$DESPLIEGUE/oracle.env" || { echo "Falta $DESPLIEGUE/oracle.env"; exit 1; }
chmod 600 "$DESPLIEGUE/oracle.env"

# Se generan una sola vez. Hexadecimal para que no haya caracteres que el
# parser de Compose o el de rabbitmq.conf puedan interpretar.
if [ ! -s "$DESPLIEGUE/.env" ]; then
    {
        echo "RABBITMQ_COOKIE=$(openssl rand -hex 24)"
        echo "RABBITMQ_USER=pedidos360"
        echo "RABBITMQ_PASS=$(openssl rand -hex 16)"
    } > "$DESPLIEGUE/.env"
    echo "  credenciales de RabbitMQ generadas"
else
    echo "  credenciales de RabbitMQ conservadas"
fi
chmod 600 "$DESPLIEGUE/.env"

# El usuario por defecto del broker se fija en un archivo de configuracion y no
# con variables de entorno: RabbitMQ 4.x ignora varias de las variables que
# usaban las versiones anteriores. Con default_user definido, el broker ya no
# crea guest/guest.
. "$DESPLIEGUE/.env"
cat > "$DESPLIEGUE/rabbitmq-usuario.conf" <<EOF
default_user = $RABBITMQ_USER
default_pass = $RABBITMQ_PASS
EOF
# Solo lo lee el usuario con el que corre RabbitMQ dentro del contenedor. Su UID
# se le pregunta a la imagen en vez de suponerlo.
IMAGEN_RABBIT=rabbitmq:4.2.9-management
docker pull -q "$IMAGEN_RABBIT" >/dev/null
UID_RABBIT=$(docker run --rm --entrypoint id "$IMAGEN_RABBIT" -u rabbitmq)
GID_RABBIT=$(docker run --rm --entrypoint id "$IMAGEN_RABBIT" -g rabbitmq)
chown "$UID_RABBIT:$GID_RABBIT" "$DESPLIEGUE/rabbitmq-usuario.conf"
chmod 600 "$DESPLIEGUE/rabbitmq-usuario.conf"
echo "  usuario de RabbitMQ: $RABBITMQ_USER (sin guest/guest)"

# El wallet es una credencial: cwallet.sso abre la base sin contrasena. Solo lo
# puede leer el usuario con el que corren los microservicios en el contenedor.
test -s "$RAIZ/wallet/cwallet.sso" || { echo "Falta el wallet en $RAIZ/wallet"; exit 1; }
chown -R "$UID_APP:$UID_APP" "$RAIZ/wallet"
chmod 700 "$RAIZ/wallet"
chmod 600 "$RAIZ/wallet"/*

# ---------------------------------------------------------------------------
paso "Imagenes"
# ---------------------------------------------------------------------------
# Maven y Node compilan dentro de las imagenes. La primera vez descargan las
# dependencias y tardan unos minutos cada una; despues las toman del cache de
# BuildKit.
construir() {
    echo "  construyendo pedidos360/$1:ep2..."
    docker build -q -t "pedidos360/$1:ep2" "$2" >/dev/null
    echo "  pedidos360/$1:ep2 lista"
}
for m in catalog orders notify rabbitadmin; do
    construir "$m" "$RAIZ/ms-pedidos360-$m"
done
construir bff "$RAIZ/bff"
construir frontend "$RAIZ/frontend"

# ---------------------------------------------------------------------------
paso "Contenedores"
# ---------------------------------------------------------------------------
# Marca de tiempo para revisar solo los logs de este despliegue: un contenedor
# que no se recrea conserva los de antes.
INICIO=$(date -u +%Y-%m-%dT%H:%M:%SZ)
$COMPOSE up -d --remove-orphans

# ---------------------------------------------------------------------------
paso "Cluster RabbitMQ"
# ---------------------------------------------------------------------------
for nodo in rabbitmq-1 rabbitmq-2; do
    for _ in $(seq 1 30); do
        docker exec "$nodo" rabbitmq-diagnostics -q ping >/dev/null 2>&1 && break
        sleep 5
    done
done

# Igual que levantar-cluster.ps1: solo se une si todavia no esta unido. La
# membresia sobrevive en el volumen, asi que en los despliegues siguientes este
# paso no hace nada.
# Se guarda la salida antes de buscar en ella: con pipefail, un grep -q que
# corta la tuberia puede hacer fallar al comando anterior y dar un falso "no
# esta unido", y unir un nodo ya unido aborta el script.
estado=$(docker exec rabbitmq-1 rabbitmqctl cluster_status 2>/dev/null || true)
if grep -q "rabbit@rabbitmq-2" <<<"$estado"; then
    echo "  el cluster ya estaba formado"
else
    docker exec rabbitmq-2 rabbitmqctl stop_app >/dev/null
    docker exec rabbitmq-2 rabbitmqctl join_cluster rabbit@rabbitmq-1 >/dev/null
    docker exec rabbitmq-2 rabbitmqctl start_app >/dev/null
    echo "  rabbitmq-2 unido al cluster de rabbitmq-1"
fi
echo "  nodos corriendo:"
docker exec rabbitmq-1 rabbitmqctl cluster_status 2>/dev/null |
    awk '/^Running Nodes/{f=1;next} /^[A-Z]/{f=0} f && /^rabbit@/{print "    " $0}'

# ---------------------------------------------------------------------------
paso "Salud de los servicios"
# ---------------------------------------------------------------------------
# Que cuenta como "responde" depende del servicio:
#   microservicios  /actuator/health en 200
#   bff             401 en /api/catalog: esta vivo y exige el JWT, que es
#                   justo lo correcto. No tiene actuator.
#   frontend        200 en /app/, sirviendo el index.html con su base href
responde() {
    case "$1" in
        bff)
            [ "$(curl -s -o /dev/null -w '%{http_code}' -m 5 http://127.0.0.1:8080/api/catalog)" = "401" ] ;;
        frontend)
            curl -fs -m 5 http://127.0.0.1/app/ | grep '<base href="/Desarrollo/app/">' >/dev/null ;;
        *)
            curl -fs -m 5 "http://127.0.0.1:$1/actuator/health" >/dev/null ;;
    esac
}

# Pedidos y catalogo tardan mas: en el primer arranque Hibernate crea las tablas
# en Oracle Cloud, y la base esta en Santiago.
#
# Si la contrasena de Oracle esta mal, catalogo y pedidos fallan al arrancar y
# restart: unless-stopped los vuelve a lanzar sin fin. Cada arranque es un login
# fallido, y ADMIN se bloquea tras unos pocos: se comprobo en una prueba con un
# usuario inexistente, que acumulo 75 intentos en minutos. Por eso, apenas
# aparece ORA-01017 se detienen los dos, en vez de esperar a que agoten el
# tiempo de espera.
pendientes="8082 8081 8083 8084 bff frontend"
for _ in $(seq 1 60); do
    for c in ms-pedidos360-catalog ms-pedidos360-orders; do
        # Sin -q: grep -q corta la tuberia al primer hallazgo, docker logs recibe
        # SIGPIPE y con pipefail la tuberia entera da falso justo cuando SI
        # encontro el error. Leyendo todo, la condicion es fiable.
        if docker logs --since "$INICIO" "$c" 2>&1 | grep -E "ORA-01017|ORA-28000" >/dev/null; then
            $COMPOSE stop catalog orders >/dev/null 2>&1
            echo
            echo "  [FALLA] Oracle rechazo el usuario o la contrasena ($c)."
            echo "          Se detuvieron catalogo y pedidos para no bloquear la cuenta ADMIN."
            echo "          Revisa la contrasena y vuelve a desplegar."
            exit 1
        fi
    done
    siguen=""
    for servicio in $pendientes; do
        responde "$servicio" || siguen="$siguen $servicio"
    done
    pendientes=$siguen
    [ -z "$pendientes" ] && break
    sleep 5
done

for par in 8082:catalog:ms-pedidos360-catalog 8081:orders:ms-pedidos360-orders \
           8083:notify:ms-pedidos360-notify 8084:rabbitadmin:ms-pedidos360-rabbitadmin \
           bff:bff:bff-pedidos360 frontend:frontend:frontend-pedidos360; do
    clave=${par%%:*}; resto=${par#*:}; nombre=${resto%%:*}; contenedor=${resto##*:}
    if responde "$clave"; then
        echo "  [OK]    $nombre"
    else
        echo "  [FALLA] $nombre: docker logs $contenedor"
    fi
done

if [ -n "$pendientes" ]; then
    exit 1
fi

echo
echo "Memoria del host:"
free -m | head -2
