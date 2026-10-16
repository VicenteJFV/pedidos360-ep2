# syntax=docker/dockerfile:1
#
# Imagen del frontend Angular para el despliegue de EP2. La guia exige que el
# frontend tambien este desplegado en la EC2.
#
# 1. Compilacion: Node construye la aplicacion con la configuracion de
#    produccion (environment.prod.ts y base href /Desarrollo/app/), en la EC2.
# 2. Servicio: nginx sirve los archivos estaticos. Node no llega a la imagen
#    final: en ejecucion el frontend es solo HTML, JS y CSS.

FROM node:24-alpine AS compilacion
WORKDIR /fuente
COPY package.json package-lock.json ./
# npm ci instala exactamente lo del package-lock. El cache sobrevive entre
# construcciones, asi que los despliegues siguientes no vuelven a descargar.
RUN --mount=type=cache,target=/root/.npm \
    npm ci --no-audit --no-fund
COPY angular.json tsconfig.json tsconfig.app.json ./
COPY public public
COPY src src
# La EC2 comparte su memoria con todos los servicios: se pone techo al
# compilador para que no los desplace.
ENV NODE_OPTIONS="--max-old-space-size=1024"
RUN npx ng build --configuration production

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=compilacion /fuente/dist/frontend-ep1/browser /usr/share/nginx/html/app
EXPOSE 80
