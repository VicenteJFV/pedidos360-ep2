import { ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { MsalBroadcastService } from '@azure/msal-angular';
import { InteractionStatus } from '@azure/msal-browser';
import { filter, take } from 'rxjs/operators';
import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-admin-dashboard',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './admin-dashboard.html',
  styleUrls: ['./admin-dashboard.scss']
})
export class AdminDashboard implements OnInit {
  private http = inject(HttpClient);
  private broadcast = inject(MsalBroadcastService);
  private cdr = inject(ChangeDetectorRef);

  pedidos: any[] = [];

  ngOnInit() {
    // Igual que en el catalogo: no pedir nada hasta que MSAL tenga cuenta activa.
    this.broadcast.inProgress$
      .pipe(filter((estado) => estado === InteractionStatus.None), take(1))
      .subscribe(() => this.cargarPedidos());
  }

  cargarPedidos() {
    this.http.get<any[]>(`${environment.azure.api.url}/api/orders`)
      .subscribe({
        next: (data) => {
          this.pedidos = data;
          // Aplicacion zoneless: sin esto la tabla no se repinta.
          this.cdr.markForCheck();
          console.log('Pedidos cargados desde AWS:', data);
        },
        error: (err) => {
          console.error('Error HTTP. Revisa la pestaña Network en DevTools', err);
        }
      });
  }

  avanzar(pedido: any) {
   
    const mapaAcciones: Record<string, string> = {
      'CREADO': 'accept',
      'ACEPTADO': 'prepare',
      'EN_PREPARACION': 'ship',
      'DESPACHADO': 'deliver'
    };
    const accion = mapaAcciones[pedido.estado];

    if (!accion) { return; }

    this.http.put(`${environment.azure.api.url}/api/orders/${pedido.id}/${accion}`, {})
      .subscribe({
        next: () => this.cargarPedidos(),
        error: (err) => {
          if (err.status === 403) {
            alert('No tienes permiso para esta operación. Se requiere rol Admin.');
          } else if (err.status === 400) {
            alert(err.error?.mensaje ?? 'Transición de estado no permitida');
          } else {
            console.error('Error inesperado', err);
          }
        }
      });
  }
}