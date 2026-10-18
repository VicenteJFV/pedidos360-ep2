import { Component, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { MsalService } from '@azure/msal-angular';
import { CarritoService } from '../../services/carrito.service';
import { AuthService } from '../../services/auth.service';
import { environment } from '../../../environments/environment';

/** Lo que muestra la pantalla cuando la API rechaza el pedido. */
interface ErrorPedido {
  mensaje: string;
  detalles: string[];
}

/**
 * Carrito y confirmacion del pedido: arma el POST /api/orders que el BFF
 * reenvia al microservicio de pedidos.
 *
 * El correo del pedido depende del rol:
 *  - Cliente: siempre el suyo. Ni siquiera se le muestra el campo, y aunque el
 *    navegador mandara otro, el BFF lo reemplaza por el del token: un cliente no
 *    puede crear pedidos a nombre de otro.
 *  - Admin: puede registrar el pedido a nombre de un cliente, escribiendo su
 *    correo. El BFF respeta el correo que manda un Admin.
 *
 * El estado que cambia por respuestas HTTP vive en senales: la aplicacion es
 * zoneless, y una propiedad simple no actualizaria la vista al llegar la
 * respuesta.
 */
@Component({
  selector: 'app-carrito',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './carrito.html',
  styleUrls: ['./carrito.scss']
})
export class Carrito {
  private http = inject(HttpClient);
  private auth = inject(AuthService);
  private msal = inject(MsalService);
  carrito = inject(CarritoService);

  esAdmin = computed(() => this.auth.getRole() === 'Admin');

  enviando = signal(false);
  pedidoCreado = signal<any | null>(null);
  error = signal<ErrorPedido | null>(null);

  clienteEmail = this.cuenta()?.username ?? '';
  clienteNombre = this.cuenta()?.name ?? '';
  direccionEntrega = '';
  observacion = '';

  correoDeLaCuenta(): string {
    return this.cuenta()?.username ?? '';
  }

  /**
   * La cuenta con la que se inicio sesion. Si todavia no hay una activa -recien
   * terminado el login puede pasar-, se toma la primera: es la misma, porque la
   * aplicacion maneja una sola. Se resuelve aqui y no cambiando AuthService, para
   * no tocar el codigo del login.
   */
  private cuenta() {
    const msal = this.msal.instance;
    return msal.getActiveAccount() ?? msal.getAllAccounts()[0] ?? null;
  }

  confirmar(): void {
    const correo = this.esAdmin() ? this.clienteEmail.trim() : this.correoDeLaCuenta();
    if (!correo) {
      this.error.set({ mensaje: 'Falta el correo del cliente.', detalles: [] });
      return;
    }

    const pedido = {
      clienteEmail: correo,
      clienteNombre: this.clienteNombre.trim() || null,
      direccionEntrega: this.direccionEntrega.trim() || null,
      observacion: this.observacion.trim() || null,
      items: this.carrito.items().map((l) => ({ productoId: l.productoId, cantidad: l.cantidad }))
    };

    this.enviando.set(true);
    this.error.set(null);

    this.http.post<any>(`${environment.azure.api.url}/api/orders`, pedido).subscribe({
      next: (creado) => {
        this.pedidoCreado.set(creado);
        this.carrito.vaciar();
        this.enviando.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.error.set(this.describir(err));
        this.enviando.set(false);
      }
    });
  }

  nuevoPedido(): void {
    this.pedidoCreado.set(null);
    this.error.set(null);
  }

  /**
   * Traduce la respuesta de error a algo que se pueda leer. Los errores de
   * negocio -stock insuficiente, producto dado de baja- llegan del microservicio
   * con un mensaje y una lista de detalles que nombran el producto: se muestran
   * tal cual, porque dicen exactamente que corregir.
   */
  private describir(err: HttpErrorResponse): ErrorPedido {
    if (err.status === 0) {
      return { mensaje: 'No se pudo contactar la API.', detalles: [] };
    }
    if (err.status === 401) {
      return { mensaje: 'La sesion vencio. Cierra sesion y vuelve a entrar.', detalles: [] };
    }
    if (err.status === 403) {
      return { mensaje: 'Tu usuario no tiene permiso para crear pedidos.', detalles: [] };
    }
    const cuerpo = typeof err.error === 'object' && err.error !== null ? err.error : null;
    return {
      mensaje: cuerpo?.mensaje ?? `La API respondio ${err.status}.`,
      detalles: Array.isArray(cuerpo?.detalles) ? cuerpo.detalles : []
    };
  }
}
