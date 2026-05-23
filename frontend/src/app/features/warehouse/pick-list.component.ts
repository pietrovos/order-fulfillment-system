import { Component, OnInit, computed, inject, input, numberAttribute, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { FulfillmentApi } from '../../core/api/fulfillment.api';
import { PickLine, PickList } from '../../core/api/models';
import { errorMessage } from '../../core/http/api-error';
import { LoadStateComponent } from '../../shared/load-state.component';
import { Notify } from '../../shared/notify.service';

@Component({
  selector: 'app-pick-list',
  imports: [RouterLink, MatButtonModule, MatCheckboxModule, MatIconModule, MatProgressBarModule, LoadStateComponent],
  template: `
    <app-load-state [loading]="loading()" [error]="error()" (retry)="load()">
      @if (pick(); as p) {
        <a routerLink="/warehouse/pick" class="back"><mat-icon inline>arrow_back</mat-icon> Picking</a>
        <h1>Pick {{ p.orderNumber }}</h1>
        <mat-progress-bar mode="determinate" [value]="progress()" />
        <p class="meta">{{ pickedCount() }} of {{ p.lines.length }} lines picked</p>
        @if (p.orderStatus !== 'PICKING') {
          <p class="banner" role="alert">This order is now {{ p.orderStatus }}. Stop picking and return items to their locations.</p>
        }
        <ul class="lines">
          @for (l of p.lines; track l.lineNo) {
            <li [class.done]="l.picked">
              <button class="line-button" (click)="toggle(l)" [disabled]="busy() || p.status !== 'OPEN' || p.orderStatus !== 'PICKING'"
                      [attr.aria-pressed]="l.picked" [attr.data-testid]="'pick-line-' + l.lineNo">
                <mat-icon class="check">{{ l.picked ? 'check_circle' : 'radio_button_unchecked' }}</mat-icon>
                <span class="qty">{{ l.quantity }}×</span>
                <span class="what"><span class="sku">{{ l.sku }}</span><span class="name">{{ l.productName }}</span></span>
              </button>
            </li>
          }
        </ul>
        <div class="footer">
          <button mat-flat-button (click)="complete()" [disabled]="busy() || pickedCount() < p.lines.length || p.status !== 'OPEN'"
                  data-testid="complete-pick">
            <mat-icon>done_all</mat-icon> Complete pick
          </button>
        </div>
      }
    </app-load-state>
  `,
  styles: `
    .back { color: var(--mat-sys-primary); text-decoration: none; display: inline-flex; gap: 4px; align-items: center; }
    h1 { margin: 8px 0 12px; }
    .meta { color: var(--mat-sys-on-surface-variant); }
    .banner { padding: 12px 16px; border-radius: 12px; background: var(--mat-sys-error-container); color: var(--mat-sys-on-error-container); }
    .lines { list-style: none; padding: 0; margin: 0 0 80px; display: flex; flex-direction: column; gap: 8px; max-width: 720px; }
    .line-button { width: 100%; min-height: 64px; display: flex; align-items: center; gap: 16px; padding: 12px 16px; border-radius: 14px;
                   border: 1px solid var(--mat-sys-outline-variant); background: var(--mat-sys-surface); color: inherit;
                   font: var(--mat-sys-body-large); text-align: left; cursor: pointer; }
    .line-button:disabled { cursor: default; opacity: 0.7; }
    .done .line-button { background: #e4f6e8; border-color: #9fd8ae; }
    .check { color: var(--mat-sys-primary); }
    .done .check { color: #1b873f; }
    .qty { font: var(--mat-sys-title-large); min-width: 3ch; }
    .what { display: flex; flex-direction: column; }
    .sku { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 13px; color: var(--mat-sys-on-surface-variant); }
    .footer { position: sticky; bottom: 0; padding: 12px 0; background: var(--mat-sys-surface); }
    .footer button { width: 100%; max-width: 720px; height: 52px; }
  `,
})
export class PickListComponent implements OnInit {
  private readonly api = inject(FulfillmentApi);
  private readonly notify = inject(Notify);
  private readonly router = inject(Router);

  readonly id = input.required({ transform: numberAttribute });

  protected readonly pick = signal<PickList | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly pickedCount = computed(() => this.pick()?.lines.filter((l) => l.picked).length ?? 0);
  protected readonly progress = computed(() => {
    const p = this.pick();
    return p && p.lines.length ? (100 * this.pickedCount()) / p.lines.length : 0;
  });

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.api.pickList(this.id()).subscribe({
      next: (p) => {
        this.pick.set(p);
        this.loading.set(false);
      },
      error: (e) => {
        this.error.set(errorMessage(e));
        this.loading.set(false);
      },
    });
  }

  toggle(line: PickLine): void {
    this.busy.set(true);
    this.api.confirmLine(this.id(), line.lineNo, !line.picked).subscribe({
      next: (p) => {
        this.pick.set(p);
        this.busy.set(false);
      },
      error: (e) => {
        this.busy.set(false);
        this.notify.fail(e);
        this.load();
      },
    });
  }

  complete(): void {
    this.busy.set(true);
    this.api.completePick(this.id()).subscribe({
      next: (p) => {
        this.notify.ok(`${p.orderNumber} picked. It's in the packing queue.`);
        void this.router.navigate(['/warehouse/pack']);
      },
      error: (e) => {
        this.busy.set(false);
        this.notify.fail(e);
      },
    });
  }
}
