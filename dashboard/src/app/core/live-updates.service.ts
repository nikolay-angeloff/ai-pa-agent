import { Injectable, OnDestroy, inject, signal } from '@angular/core';
import { RuntimeConfigService } from './runtime-config.service';

interface ExpenseCreatedEvent {
  type: 'expense_created';
  data: { id: string; merchant: string; amount: number; currency: string; date: string };
  at: string;
}

interface InsightEvent {
  type: 'insight';
  data: string[];
  at: string;
}

const MAX_INSIGHTS = 20; // keep the panel from growing unbounded across a long-open dashboard session

// Connects to the webhook svc's WebSocket channel (already broadcasting
// expense_created on every facade ingest, and insight on every scheduled
// anomaly scan — see webhook/index.js). Reconnects with a fixed backoff
// since this is a best-effort live badge, not a critical path.
@Injectable({ providedIn: 'root' })
export class LiveUpdatesService implements OnDestroy {
  private readonly config = inject(RuntimeConfigService);
  private socket?: WebSocket;
  private reconnectTimer?: ReturnType<typeof setTimeout>;

  readonly lastEvent = signal<ExpenseCreatedEvent | null>(null);
  readonly insights = signal<string[]>([]);
  readonly connected = signal(false);

  connect(): void {
    if (this.socket) return;
    this.socket = new WebSocket(`${this.config.wsBaseUrl}/ws`);

    this.socket.onopen = () => this.connected.set(true);

    this.socket.onmessage = (msg) => {
      try {
        const event = JSON.parse(msg.data) as ExpenseCreatedEvent | InsightEvent;
        if (event.type === 'expense_created') this.lastEvent.set(event);
        if (event.type === 'insight') {
          this.insights.update((existing) => [...event.data, ...existing].slice(0, MAX_INSIGHTS));
        }
      } catch {
        // ignore malformed frames
      }
    };

    this.socket.onclose = () => {
      this.connected.set(false);
      this.socket = undefined;
      this.reconnectTimer = setTimeout(() => this.connect(), 5000);
    };

    this.socket.onerror = () => this.socket?.close();
  }

  ngOnDestroy(): void {
    clearTimeout(this.reconnectTimer);
    this.socket?.close();
  }
}
