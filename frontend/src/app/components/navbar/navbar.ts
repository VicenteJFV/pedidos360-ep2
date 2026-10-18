import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { CommonModule } from '@angular/common'; 
import { AuthService } from '../../services/auth.service';
import { CarritoService } from '../../services/carrito.service';

@Component({
  imports: [RouterLink, CommonModule], 
  selector: 'app-navbar',
  styleUrl: './navbar.scss',
  templateUrl: './navbar.html',
})
export class Navbar {
  authService = inject(AuthService);
  // Senal: la barra se actualiza sola cuando el catalogo agrega productos.
  carrito = inject(CarritoService); 

  login() {
    this.authService.login();
  }

  logout() {
    this.authService.logout();
  }
}