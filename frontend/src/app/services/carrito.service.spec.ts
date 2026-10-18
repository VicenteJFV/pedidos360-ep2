import { TestBed } from '@angular/core/testing';
import { CarritoService } from './carrito.service';

/**
 * Logica del carrito. No necesita la API ni el login: es lo que decide cuanto
 * se puede pedir y cuanto suma el pedido antes de enviarlo.
 */
describe('CarritoService', () => {
  let carrito: CarritoService;

  const notebook = { id: 1, sku: 'SKU-001', nombre: 'Notebook', precio: 500000, stock: 2 };
  const mouse = { id: 2, sku: 'SKU-002', nombre: 'Mouse', precio: 15000, stock: 5000 };

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({});
    carrito = TestBed.inject(CarritoService);
  });

  it('agrega productos y suma cantidades y total', () => {
    carrito.agregar(notebook);
    carrito.agregar(notebook);
    carrito.agregar(mouse);

    expect(carrito.cantidadDe(1)).toBe(2);
    expect(carrito.cantidadTotal()).toBe(3);
    expect(carrito.total()).toBe(2 * 500000 + 15000);
  });

  it('no deja pedir mas que el stock que mostraba el catalogo', () => {
    expect(carrito.agregar(notebook)).toBe(true);
    expect(carrito.agregar(notebook)).toBe(true);
    expect(carrito.agregar(notebook)).toBe(false);
    expect(carrito.cantidadDe(1)).toBe(2);
  });

  it('no pasa del tope de 999 por linea que exige el microservicio', () => {
    carrito.agregar(mouse);
    carrito.cambiarCantidad(2, 5000);

    expect(carrito.cantidadDe(2)).toBe(999);
  });

  it('nunca deja una cantidad menor que 1: para eso esta quitar', () => {
    carrito.agregar(mouse);
    carrito.cambiarCantidad(2, 0);

    expect(carrito.cantidadDe(2)).toBe(1);
  });

  it('quita una linea y vacia el carrito', () => {
    carrito.agregar(notebook);
    carrito.agregar(mouse);

    carrito.quitar(1);
    expect(carrito.items().map((l) => l.productoId)).toEqual([2]);

    carrito.vaciar();
    expect(carrito.cantidadTotal()).toBe(0);
  });

  it('se guarda en sessionStorage para sobrevivir a la recarga y al login', () => {
    carrito.agregar(notebook);
    TestBed.tick();

    const guardado = JSON.parse(sessionStorage.getItem('pedidos360.carrito') ?? '[]');
    expect(guardado).toHaveLength(1);
    expect(guardado[0].sku).toBe('SKU-001');
  });

  it('al iniciar recupera el carrito guardado', () => {
    sessionStorage.setItem('pedidos360.carrito', JSON.stringify([
      { productoId: 2, sku: 'SKU-002', nombre: 'Mouse', precio: 15000, stock: 5000, cantidad: 3 }
    ]));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});

    const recuperado = TestBed.inject(CarritoService);
    expect(recuperado.cantidadDe(2)).toBe(3);
  });
});
