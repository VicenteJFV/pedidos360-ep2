import {
  MSAL_INSTANCE, MSAL_GUARD_CONFIG, MSAL_INTERCEPTOR_CONFIG,
  MsalService, MsalBroadcastService, MsalGuard, MsalInterceptor,
  MsalGuardConfiguration, MsalInterceptorConfiguration
} from '@azure/msal-angular';
import { InteractionType } from '@azure/msal-browser';
import { HTTP_INTERCEPTORS, provideHttpClient, withInterceptorsFromDi } from '@angular/common/http';

import { ApplicationConfig } from '@angular/core';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { IPublicClientApplication, PublicClientApplication } from '@azure/msal-browser';
import { environment } from '../environments/environment';

export function MSALInstanceFactory(): IPublicClientApplication {
  return new PublicClientApplication({
    auth: {
      clientId: environment.azure.clientId,
      authority: environment.azure.authority,
      redirectUri: environment.azure.redirectUri
    }
  });
}

export function MSALGuardConfigFactory(): MsalGuardConfiguration {
  return {
    interactionType: InteractionType.Redirect,
    authRequest: { scopes: [environment.azure.api.scope] }
  };
}

export function MSALInterceptorConfigFactory(): MsalInterceptorConfiguration {
  const protectedResourceMap = new Map<string, Array<string>>();

  // El comodin es obligatorio desde msal-angular v6: 'strictMatching' viene
  // activado por defecto y compara la ruta como patron anclado, asi que la URL
  // base '/Desarrollo' ya NO cubre '/Desarrollo/api/catalog'. Sin el '/*' el
  // interceptor no encuentra scopes, deja pasar la peticion sin 'Authorization',
  // el JWT Authorizer responde 401 sin cabeceras CORS y el navegador lo reporta
  // como un error de CORS que oculta la causa.
  protectedResourceMap.set(`${environment.azure.api.url}/*`, [environment.azure.api.scope]);

  return { interactionType: InteractionType.Redirect, protectedResourceMap };
}

export const appConfig: ApplicationConfig = {
  providers: [
    provideRouter(routes),
    provideHttpClient(withInterceptorsFromDi()),
    { provide: HTTP_INTERCEPTORS, useClass: MsalInterceptor, multi: true },
    { provide: MSAL_INSTANCE, useFactory: MSALInstanceFactory },
    { provide: MSAL_GUARD_CONFIG, useFactory: MSALGuardConfigFactory },
    { provide: MSAL_INTERCEPTOR_CONFIG, useFactory: MSALInterceptorConfigFactory },
    MsalService,
    MsalGuard,
    MsalBroadcastService
  ]
};