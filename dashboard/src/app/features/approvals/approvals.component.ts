import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DocumentReviewApiService } from '../../core/document-review-api.service';
import { ApproveRequest, DocumentReviewView } from '../../core/models';

@Component({
  selector: 'app-approvals',
  standalone: true,
  imports: [FormsModule, DatePipe, DecimalPipe],
  templateUrl: './approvals.component.html',
  styleUrl: './approvals.component.css',
})
export class ApprovalsComponent {
  private readonly api = inject(DocumentReviewApiService);

  readonly pending = signal<DocumentReviewView[]>([]);
  readonly drafts = signal<Record<string, ApproveRequest>>({});
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly busyId = signal<string | null>(null);

  constructor() {
    this.refresh();
  }

  draftFor(doc: DocumentReviewView): ApproveRequest {
    return this.drafts()[doc.id];
  }

  updateDraft(id: string, patch: Partial<ApproveRequest>): void {
    this.drafts.update((d) => ({ ...d, [id]: { ...d[id], ...patch } }));
  }

  approve(doc: DocumentReviewView): void {
    this.busyId.set(doc.id);
    this.api.approve(doc.id, this.draftFor(doc)).subscribe({
      next: () => this.removeFromQueue(doc.id),
      error: () => {
        this.error.set(`Approve failed for "${doc.subject ?? doc.id}" — is the agent svc running?`);
        this.busyId.set(null);
      },
    });
  }

  reject(doc: DocumentReviewView): void {
    this.busyId.set(doc.id);
    this.api.reject(doc.id).subscribe({
      next: () => this.removeFromQueue(doc.id),
      error: () => {
        this.error.set(`Reject failed for "${doc.subject ?? doc.id}" — is the agent svc running?`);
        this.busyId.set(null);
      },
    });
  }

  private removeFromQueue(id: string): void {
    this.pending.update((rows) => rows.filter((r) => r.id !== id));
    this.drafts.update((d) => {
      const { [id]: _removed, ...rest } = d;
      return rest;
    });
    this.busyId.set(null);
  }

  private refresh(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.awaitingReview().subscribe({
      next: (rows) => {
        this.pending.set(rows);
        this.drafts.set(
          Object.fromEntries(
            rows.map((r) => [
              r.id,
              {
                documentType: r.extractedFields?.documentType ?? null,
                merchant: r.extractedFields?.merchant ?? null,
                amount: r.extractedFields?.amount ?? null,
                currency: r.extractedFields?.currency ?? null,
                date: r.extractedFields?.date ?? null,
              } satisfies ApproveRequest,
            ]),
          ),
        );
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Failed to load pending reviews — is the gateway running?');
        this.loading.set(false);
      },
    });
  }
}
