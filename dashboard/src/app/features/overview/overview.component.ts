import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { ChartConfiguration, ChartData } from 'chart.js';
import { BaseChartDirective } from 'ng2-charts';
import { ExpenseApiService } from '../../core/expense-api.service';
import { LiveUpdatesService } from '../../core/live-updates.service';
import { CategorySummary, ExpenseView } from '../../core/models';

type PeriodDays = 7 | 30 | 90 | 365;

@Component({
  selector: 'app-overview',
  standalone: true,
  imports: [BaseChartDirective, CurrencyPipe, DatePipe],
  templateUrl: './overview.component.html',
  styleUrl: './overview.component.css',
})
export class OverviewComponent {
  private readonly api = inject(ExpenseApiService);
  private readonly live = inject(LiveUpdatesService);

  readonly periodOptions: { label: string; days: PeriodDays }[] = [
    { label: '7d', days: 7 },
    { label: '30d', days: 30 },
    { label: '90d', days: 90 },
    { label: '365d', days: 365 },
  ];

  readonly selectedDays = signal<PeriodDays>(30);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly grandTotal = signal(0);
  readonly byCategory = signal<CategorySummary[]>([]);
  readonly expenses = signal<ExpenseView[]>([]);

  readonly liveConnected = this.live.connected;
  readonly lastLiveEvent = this.live.lastEvent;
  readonly insights = this.live.insights;

  readonly categoryChartType = 'pie' as const;
  readonly categoryChartData = computed<ChartData<'pie', number[], string>>(() => ({
    labels: this.byCategory().map((c) => c.category),
    datasets: [{ data: this.byCategory().map((c) => c.total) }],
  }));
  readonly categoryChartOptions: ChartConfiguration<'pie'>['options'] = {
    plugins: { legend: { position: 'right' } },
  };

  constructor() {
    this.live.connect();

    // Refetch whenever the period changes.
    effect(() => {
      this.selectedDays();
      this.refresh();
    });

    // A live expense_created push is a cheap signal to refetch — personal-scale
    // data volume, so no debouncing needed.
    effect(() => {
      if (this.lastLiveEvent()) this.refresh();
    });
  }

  selectPeriod(days: PeriodDays): void {
    this.selectedDays.set(days);
  }

  private refresh(): void {
    const { from, to } = this.dateRange();
    this.loading.set(true);
    this.error.set(null);

    this.api.summary(from, to).subscribe({
      next: (res) => {
        this.grandTotal.set(res.grandTotal);
        this.byCategory.set(res.byCategory);
      },
      error: () => this.error.set('Failed to load summary — is the gateway running?'),
    });

    this.api.list(from, to).subscribe({
      next: (rows) => {
        this.expenses.set(rows);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Failed to load expenses — is the gateway running?');
        this.loading.set(false);
      },
    });
  }

  private dateRange(): { from: string; to: string } {
    const to = new Date();
    const from = new Date();
    from.setDate(to.getDate() - this.selectedDays());
    return { from: this.toIsoDate(from), to: this.toIsoDate(to) };
  }

  private toIsoDate(d: Date): string {
    return d.toISOString().slice(0, 10);
  }
}
