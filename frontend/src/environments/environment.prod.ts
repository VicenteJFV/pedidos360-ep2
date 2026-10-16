// Configuracion del frontend desplegado en la EC2 (EP2).
//
// El frontend se sirve desde la EC2, pero el navegador llega a el a traves del
// API Gateway, en la ruta /app/. Asi se obtienen dos cosas:
//
//  - HTTPS con un certificado valido. Entra ID no acepta direcciones de retorno
//    http:// salvo para localhost, y MSAL necesita un contexto seguro para el
//    login con PKCE: con http://<ip-de-la-ec2> el login no funcionaria.
//  - El mismo origen que la API. El frontend y /api/... estan bajo el mismo
//    dominio del API Gateway, asi que el navegador no hace peticiones CORS.
//
// redirectUri tiene que estar registrada, EXACTAMENTE asi y con la barra
// final, en la app de Entra ID del frontend (plataforma SPA).
const gateway = 'https://xpglku1pj5.execute-api.us-east-1.amazonaws.com/Desarrollo';

export const environment = {
  production: true,
  azure: {
    clientId: '6731c641-8037-476d-9d2b-76765ca2ecc3',
    tenantId: '17dd3345-54db-49ce-8172-092f2ccd50fd',
    authority: 'https://login.microsoftonline.com/17dd3345-54db-49ce-8172-092f2ccd50fd',
    redirectUri: `${gateway}/app/`,
    api: {
      clientId: 'fc8de29a-f24d-4173-bfc1-12580819e4c7',
      scope: 'api://fc8de29a-f24d-4173-bfc1-12580819e4c7/access_as_user',
      url: gateway
    }
  }
};
