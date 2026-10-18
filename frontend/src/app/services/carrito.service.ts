import { Injectable, computed, effect, signal } from '@angular/core';

/** Un producto en el carrito, con lo necesario para mostrarlo sin volver a pedirlo. */
export interface LineaCarrito {
  productoId: number;
  sku: string;
  nombre: string;
  precio: number;
  stock: number;
  cantidad: number;
}

/** Tope por linea que exige el microservicio de pedidos (@Max en LineaPedidoRequest). */
const MAXIMO_POR_LINEA = 999;

const CLAVE_SESION = 'pedidos360.carrito';

/**
 * Carrito de compras.
 *
 * Usa senales y no propiedades simples porque la aplicacion es zoneless: la
 * barra de navegacion muestra la cantidad de productos, y sin senales no se
 * enteraria de los cambios que hace el catalogo.
 *
 * Se guarda en sessionStorage para que no se pierda al recargar la pagina ni en
 * la ida y vuelta del login de Microsoft. sessionStorage y no localStorage: el
 * carrito es de esta pestana y de esta sesion, no debe sobrevivir a cerrar el
 * navegador en un computador compartido.
 *
 * La validacion definitiva del stock la hace el microservicio al crear el
 * pedido. Aqui solo se evita pedir mas de lo que el catalogo mostraba, para no
 * dejar armar un pedido que de antemano se sabe que va a ser rechazado.
 */
@Injectable({ providedIn: 'root' })
export class CarritoService {

  private readonly lineas = signal<LineaCarrito[]>(this.leerDeSesion());

  readonly items = this.lineas.asReadonly();

  readonly cantidadTotal = computed(() =>
    this.lineas().reduce((suma, l) => suma + l.cantidad, 0));

  readonly total = computed(() =>
    this.lineas().reduce((suma, l) => suma + l.precio * l.cantidad, 0));

  constructor() {
    effect(() => this.guardarEnSesion(this.lineas()));
  }

  /** Cuantas unidades de un producto hay en el carrito. */
  cantidadDe(productoId: number): number {
    return this.lineas().find((l) => l.productoId === productoId)?.cantidad ?? 0;
  }

  /** Tope de unidades para un producto: su stock, sin pasar del maximo por linea. */
  maximoPara(stock: number): number {
    return Math.min(stock, MAXIMO_POR_LINEA);
  }

  /**
   * Suma una unidad del producto.
   *
   * @return false si no se pudo, porque se alcanzo el stock o el tope por linea
   */
  agregar(producto: { id: number; sku: string; nombre: string; precio: number; stock: number }): boolean {
    const maximo = this.maximoPara(producto.stock);
    const actual = this.cantidadDe(producto.id);
    if (actual >= maximo) {
      return false;
    }

    if (actual === 0) {
      this.lineas.update((lineas) => [...lineas, {
        productoId: producto.id,
        sku: producto.sku,
        nombre: producto.nombre,
        precio: Number(producto.precio),
        stock: producto.stock,
        cantidad: 1,
      }]);
    } else {
      this.cambiarCantidad(producto.id, actual + 1);
    }
    return true;
  }

  /** Fija la cantidad de una linea, acotada entre 1 y el maximo que admite. */
  cambiarCantidad(productoId: number, cantidad: number): void {
    this.lineas.update((lineas) => lineas.map((l) => {
      if (l.productoId !== productoId) {
        return l;
      }
      const acotada = Math.max(1, Math.min(Math.floor(cantidad) || 1, this.maximoPara(l.stock)));
      return { ...l, cantidad: acotada };
    }));
  }

  quitar(productoId: number): void {
    this.lineas.update((lineas) => lineas.filter((l) => l.productoId !== productoId));
  }

  vaciar(): void {
    this.lineas.set([]);
  }

  private leerDeSesion(): LineaCarrito[] {
    try {
      const guardado = sessionStorage.getItem(CLAVE_SESION);
      return guardado ? JSON.parse(guardado) : [];
    } catch {
      // Navegacion privada o almacenamiento bloqueado: el carrito igual funciona,
      // solo que no sobrevive a una recarga.
      return [];
    }
  }

  private guardarEnSesion(lineas: LineaCarrito[]): void {
    try {
      sessionStorage.setItem(CLAVE_SESION, JSON.stringify(lineas));
    } catch {
      // Ver leerDeSesion.
    }
  }
}
