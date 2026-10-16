import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-catalogo',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './catalogo.html',
  styleUrls: ['./catalogo.scss']
})
export class Catalogo implements OnInit {
  private http = inject(HttpClient);
  productos: any[] = [];

  ngOnInit() {
    this.http.get<any[]>(`${environment.azure.api.url}/api/catalog`)
      .subscribe({
        next: (data) => this.productos = data,
        error: (err) => console.error('Error cargando catálogo', err)
      });
  }
}