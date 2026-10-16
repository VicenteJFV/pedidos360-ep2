import { Injectable, inject } from '@angular/core';
import { MsalService, MsalBroadcastService } from '@azure/msal-angular';
import { InteractionStatus } from '@azure/msal-browser';
import { environment } from '../../environments/environment';

@Injectable({
  providedIn: 'root',
})
export class AuthService {
  private msalService = inject(MsalService);
  private msalBroadcastService = inject(MsalBroadcastService);
  
  private currentToken: string | null = null; 
  private currentRole: string | null = null;

  constructor() {
    this.msalBroadcastService.inProgress$.subscribe((status) => {
      if (status === InteractionStatus.None) {
        let account = this.msalService.instance.getActiveAccount();
        if (!account) {
          const accounts = this.msalService.instance.getAllAccounts();
          if (accounts.length > 0) {
            account = accounts[0];
            this.msalService.instance.setActiveAccount(account);
          }
        }
        if (account) {
          this.obtenerAccessToken();
        }
      }
    });
  }

  login(): void {
    this.msalService.loginRedirect({
      scopes: ['openid', 'profile', environment.azure.api.scope],
      prompt: 'select_account',
    });
  }

  logout(): void {
    this.msalService.logoutRedirect();
  }

  isLoggedIn(): boolean {
    return this.msalService.instance.getAllAccounts().length > 0;
  }

  getAccount() {
    return this.msalService.instance.getActiveAccount();
  }

  obtenerToken(): string | null {
    return this.currentToken;
  }

  getRole(): string | null {
    return this.currentRole;
  }
  
  async obtenerAccessToken(): Promise<void> {
    const account = this.msalService.instance.getActiveAccount();
    if (!account) {
      console.error('No existe una cuenta activa.');
      return;
    }
    try {
      const result = await this.msalService.instance.acquireTokenSilent({
        account,
        scopes: [environment.azure.api.scope],
      });
      
      const token = result.accessToken;
      this.currentToken = token; 

      const partes = token.split('.');
      if (partes.length !== 3) {
        console.error('El Access Token no tiene formato JWT.');
        return;
      }
      const payload = JSON.parse(atob(partes[1].replace(/-/g, '+').replace(/_/g, '/')));

      setTimeout(() => {
        if (payload.roles && payload.roles.length > 0) {
          this.currentRole = payload.roles.includes('Admin') ? 'Admin' : 'Cliente';
        } else {
          this.currentRole = 'Cliente';
        }
      }, 0);

      console.log('==============================');
      console.log('ACCESS TOKEN PEDIDOS360');
      console.log('==============================');
      console.log('aud:', payload.aud);
      console.log('iss:', payload.iss);
      console.log('scp:', payload.scp);
      console.log('roles:', payload.roles);
      console.log('==============================');
    } catch (error) {
      console.error('Error obteniendo Access Token de Pedidos360:', error);
    }
  }
}