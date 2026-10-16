#!/bin/bash
# Lanza instalar-en-ec2.sh desacoplado de la sesion SSH que lo invoca.
#
# Si la instalacion corriera dentro de la sesion, un corte de red la mataria a
# mitad de camino: SSH envia SIGHUP al cerrarse. Y los cortes no son teoricos:
# en algunas redes una conexion que pasa mas de un minuto sin trafico se
# descarta en silencio, y una construccion de imagenes pasa minutos sin
# escribir nada.
#
# Aqui la instalacion corre con nohup, escribe su salida en un log y deja su
# codigo de salida en otro archivo. aws/07-desplegar-ep2.ps1 los consulta con
# conexiones cortas hasta que aparece el codigo.

LOG=/tmp/instalacion.log
CODIGO=/tmp/instalacion.codigo

if pgrep -f "[i]nstalar-en-ec2.sh" >/dev/null; then
    echo "Ya hay una instalacion en curso; se espera esa en vez de lanzar otra."
    exit 0
fi

rm -f "$LOG" "$CODIGO"
nohup bash -c "bash /opt/pedidos360-ep2/despliegue/instalar-en-ec2.sh; echo \$? > $CODIGO" \
    </dev/null >"$LOG" 2>&1 &
echo "instalacion lanzada (pid $!)"
