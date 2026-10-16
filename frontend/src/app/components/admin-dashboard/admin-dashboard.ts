import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
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
  
  pedidos: any[] = []; 

  ngOnInit() {
    this.cargarPedidos();
  }

  cargarPedidos() {
    this.http.get<any[]>(`${environment.azure.api.url}/api/orders`)
      .subscribe({
        next: (data) => {
          this.pedidos = data;
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