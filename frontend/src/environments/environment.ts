export const environment = {
  production: false,
  azure: {
    clientId: '6731c641-8037-476d-9d2b-76765ca2ecc3',
    tenantId: '17dd3345-54db-49ce-8172-092f2ccd50fd',
    authority: 'https://login.microsoftonline.com/17dd3345-54db-49ce-8172-092f2ccd50fd',
    redirectUri: 'http://localhost:4200/',
    api: {
      clientId: 'fc8de29a-f24d-4173-bfc1-12580819e4c7',
      scope: 'api://fc8de29a-f24d-4173-bfc1-12580819e4c7/access_as_user',
      url: 'https://xpglku1pj5.execute-api.us-east-1.amazonaws.com/Desarrollo'
    }
  }
};