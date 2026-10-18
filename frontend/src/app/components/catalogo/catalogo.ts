import { ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { MsalBroadcastService } from '@azure/msal-angular';
import { InteractionStatus } from '@azure/msal-browser';
import { filter, take } from 'rxjs/operators';
import { environment } from '../../../environments/environment';
import { CarritoService } from '../../services/carrito.service';

@Component({
  selector: 'app-catalogo',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './catalogo.html',
  styleUrls: ['./catalogo.scss']
})
export class Catalogo implements OnInit {
  private http = inject(HttpClient);
  private broadcast = inject(MsalBroadcastService);
  private cdr = inject(ChangeDetectorRef);
  carrito = inject(CarritoService);

  productos: any[] = [];
  error: string | null = null;

  /** Se puede agregar mientras el producto este activo y quede stock sin reservar en el carrito. */
  puedeAgregar(producto: any): boolean {
    return producto.activo !== false
      && this.carrito.cantidadDe(producto.id) < this.carrito.maximoPara(producto.stock);
  }

  agregar(producto: any): void {
    this.carrito.agregar(producto);
  }

  ngOnInit() {
    // Esperar a que MSAL termine de procesar el redirect y fije la cuenta activa.
    // Sin esto la peticion sale antes de que exista token, el JWT Authorizer la
    // rechaza con un 401 sin cabeceras CORS, y el navegador lo reporta como un
    // error de CORS que oculta la causa real.
    this.broadcast.inProgress$
      .pipe(filter((estado) => estado === InteractionStatus.None), take(1))
      .subscribe(() => this.cargarCatalogo());
  }

  private cargarCatalogo() {
    this.http.get<any[]>(`${environment.azure.api.url}/api/catalog`)
      .subscribe({
        next: (data) => {
          this.productos = data;
          this.error = null;
          // La aplicacion es zoneless: sin esto la vista no se entera del cambio.
          this.cdr.markForCheck();
        },
        error: (err) => {
          console.error('Error cargando catálogo', err);
          this.error = err.status === 0
            ? 'No se pudo contactar la API.'
            : `La API respondio ${err.status}.`;
          this.cdr.markForCheck();
        }
      });
  }
}
