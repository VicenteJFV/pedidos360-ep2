import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { CommonModule } from '@angular/common'; 
import { AuthService } from '../../services/auth.service'; 

@Component({
  imports: [RouterLink, CommonModule], 
  selector: 'app-navbar',
  styleUrl: './navbar.scss',
  templateUrl: './navbar.html',
})
export class Navbar {
  authService = inject(AuthService); 

  login() {
    this.authService.login();
  }

  logout() {
    this.authService.logout();
  }
}