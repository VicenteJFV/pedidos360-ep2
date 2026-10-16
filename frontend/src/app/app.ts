import { Component, inject, OnInit } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { CommonModule } from '@angular/common';
import { AuthService } from './services/auth.service';
import { MsalService } from '@azure/msal-angular';
import { Navbar } from './components/navbar/navbar';
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, CommonModule,Navbar],
  templateUrl: './app.html',
  styleUrl: './app.scss'
})
export class App implements OnInit {
  authService = inject(AuthService);
  msalService = inject(MsalService); // Inyectamos el motor de Microsoft

  ngOnInit() {
    // Esta es la línea mágica que atrapa el "#code=..." de la URL y lo procesa
    this.msalService.handleRedirectObservable().subscribe();
  }

  login() {
    this.authService.login();
  }

  logout() {
    this.authService.logout();
  }
}