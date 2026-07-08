import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApproveRequest, DocumentReviewView, ExtractionDetail } from './models';
import { RuntimeConfigService } from './runtime-config.service';

// Goes through Gateway, same as ExpenseApiService — HITL queue for documents
// the agent paused on (DocumentStatus.AWAITING_REVIEW).
@Injectable({ providedIn: 'root' })
export class DocumentReviewApiService {
  private readonly http = inject(HttpClient);
  private readonly config = inject(RuntimeConfigService);
  private readonly base = `${this.config.apiBaseUrl}/api/documents`;

  awaitingReview(): Observable<DocumentReviewView[]> {
    return this.http.get<DocumentReviewView[]>(`${this.base}/awaiting-review`);
  }

  approve(id: string, corrected?: ApproveRequest): Observable<ExtractionDetail> {
    return this.http.post<ExtractionDetail>(`${this.base}/${id}/approve`, corrected ?? {});
  }

  reject(id: string): Observable<ExtractionDetail> {
    return this.http.post<ExtractionDetail>(`${this.base}/${id}/reject`, {});
  }
}
