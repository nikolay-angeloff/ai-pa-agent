import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ExpenseView, SummaryResponse } from './models';
import { RuntimeConfigService } from './runtime-config.service';

// Goes through Gateway (not straight to facade) — same single entry point every
// other client uses, per the architecture boundary in CLAUDE.md.
@Injectable({ providedIn: 'root' })
export class ExpenseApiService {
  private readonly http = inject(HttpClient);
  private readonly config = inject(RuntimeConfigService);
  private readonly base = `${this.config.apiBaseUrl}/api/expenses`;

  list(from: string, to: string, page = 0, size = 50): Observable<ExpenseView[]> {
    return this.http.get<ExpenseView[]>(this.base, { params: { from, to, page, size } });
  }

  summary(from: string, to: string): Observable<SummaryResponse> {
    return this.http.get<SummaryResponse>(`${this.base}/summary`, { params: { from, to } });
  }
}
