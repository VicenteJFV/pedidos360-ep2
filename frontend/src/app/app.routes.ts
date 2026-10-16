import { Routes } from '@angular/router';
import { Catalogo } from './components/catalogo/catalogo';
import { AdminDashboard } from './components/admin-dashboard/admin-dashboard';

import { adminGuard } from './guards/admin.guard';
import { MsalGuard } from '@azure/msal-angular';

export const routes: Routes = [
  { path: 'catalogo', component: Catalogo },
  { path: 'admin', component: AdminDashboard, canActivate: [MsalGuard, adminGuard] },
  { path: '', redirectTo: '/catalogo', pathMatch: 'full' } 
];