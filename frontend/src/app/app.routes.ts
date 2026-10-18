import { Routes } from '@angular/router';
import { Catalogo } from './components/catalogo/catalogo';
import { AdminDashboard } from './components/admin-dashboard/admin-dashboard';
import { Carrito } from './components/carrito/carrito';

import { adminGuard } from './guards/admin.guard';
import { MsalGuard } from '@azure/msal-angular';

export const routes: Routes = [
  { path: 'catalogo', component: Catalogo },
  { path: 'admin', component: AdminDashboard, canActivate: [MsalGuard, adminGuard] },
  // Cualquier usuario con sesion; el rol solo cambia a nombre de quien queda el pedido.
  { path: 'carrito', component: Carrito, canActivate: [MsalGuard] },
  { path: '', redirectTo: '/catalogo', pathMatch: 'full' } 
];