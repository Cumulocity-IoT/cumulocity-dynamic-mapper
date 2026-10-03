/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack
 */

import { Component, ElementRef, OnDestroy, OnInit } from '@angular/core';
import { CoreModule } from '@c8y/ngx-components';
import { ECharts, EChartsOption } from 'echarts';
import { NgxEchartsModule, provideEchartsCore } from 'ngx-echarts';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { Direction, MAPPING_STATUS_UNSPECIFIED, MappingStatus } from '../../shared';
import { MonitoringService } from '../shared/monitoring.service';

/** Outcome counts of one bucket (a direction, or the catch-all "no mapping" row). */
interface OutcomeStats {
  processed: number;
  filtered: number;
  errors: number;
}

/** How many mappings the latency chart lists; more would not fit a readable bar chart. */
const MAX_SLOWEST_MAPPINGS = 10;

@Component({
  selector: 'd11r-monitoring-chart',
  templateUrl: './chart.component.html',
  styleUrls: ['./chart.component.css'],
  standalone: true,
  imports: [CoreModule, NgxEchartsModule],
  providers: [provideEchartsCore({ echarts: () => import('echarts') })]
})
export class MonitoringChartComponent implements OnInit, OnDestroy {
  outcomeOptions: EChartsOption;
  outcomeUpdateOptions: EChartsOption;
  latencyOptions: EChartsOption;
  latencyUpdateOptions: EChartsOption;
  hasLatencyData = false;

  private readonly destroy$ = new Subject<void>();
  private readonly outcomeCategories = ['Inbound', 'Outbound', 'No mapping matched / failed early'];
  private textStyle: { fontSize: number; color: string; fontFamily: string };
  private colors: { success: string; danger: string; info: string; warning: string; neutral: string };

  constructor(
    private readonly el: ElementRef,
    public readonly monitoringService: MonitoringService
  ) {}

  ngOnInit(): void {
    this.initializeThemeVariables();
    this.outcomeOptions = this.createOutcomeOptions();
    this.latencyOptions = this.createLatencyOptions();
    void this.initializeMonitoringService();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.monitoringService.stopMonitoring();
  }

  onChartInit(_ec: ECharts): void {
    // Kept for the template binding; updates go through the [merge] inputs.
  }

  private initializeThemeVariables(): void {
    const root = this.el.nativeElement.ownerDocument.documentElement;
    const computedStyle = getComputedStyle(root);
    // Series colours come from the Cumulocity design-system palette tokens (codex: Design tokens >
    // Color tokens), read from the document so the charts follow the active light/dark theme and
    // any branding. The literals are the light-theme values, used only when a token is missing.
    const token = (name: string, fallback: string) => computedStyle.getPropertyValue(name).trim() || fallback;
    this.colors = {
      success: token('--c8y-palette-status-success', '#71A112'),
      danger: token('--c8y-palette-status-danger', '#DC5B68'),
      info: token('--c8y-palette-status-info', '#0092db'),
      warning: token('--c8y-palette-status-warning', '#E07800'),
      neutral: token('--c8y-palette-gray-50', '#7D7F82')
    };
    this.textStyle = {
      fontSize: parseInt(computedStyle.getPropertyValue('--c8y-font-size-base').trim()),
      color: computedStyle.getPropertyValue('--c8y-text-color').trim(),
      fontFamily: computedStyle.getPropertyValue('--c8y-font-family-sans-serif').trim()
    };
  }

  private async initializeMonitoringService(): Promise<void> {
    await this.monitoringService.startMonitoring();
    this.monitoringService
      .getMappingStatus()
      .pipe(takeUntil(this.destroy$))
      .subscribe((statuses) => {
        this.updateOutcomeChart(statuses ?? []);
        this.updateLatencyChart(statuses ?? []);
      });
  }

  /**
   * Processed / filtered / failed per direction. The catch-all row has no direction and used to be
   * folded into "inbound", which inflated that bar with failures no inbound mapping caused; it
   * gets its own bucket instead.
   */
  private aggregateOutcomes(statuses: MappingStatus[]): OutcomeStats[] {
    const buckets: OutcomeStats[] = this.outcomeCategories.map(() => ({ processed: 0, filtered: 0, errors: 0 }));
    for (const status of statuses) {
      const index =
        status.identifier === MAPPING_STATUS_UNSPECIFIED || !status.direction
          ? 2
          : status.direction === Direction.INBOUND
            ? 0
            : 1;
      const errors = status.errors ?? 0;
      const filtered = status.messagesFiltered ?? 0;
      // An older backend has no processed counter: derive it so the chart still shows something.
      const processed = status.messagesProcessed ?? Math.max(0, (status.messagesReceived ?? 0) - errors - filtered);
      buckets[index].processed += processed;
      buckets[index].filtered += filtered;
      buckets[index].errors += errors;
    }
    return buckets;
  }

  private updateOutcomeChart(statuses: MappingStatus[]): void {
    const buckets = this.aggregateOutcomes(statuses);
    this.outcomeUpdateOptions = {
      series: [
        { type: 'bar', data: buckets.map((b) => b.processed) },
        { type: 'bar', data: buckets.map((b) => b.filtered) },
        { type: 'bar', data: buckets.map((b) => b.errors) }
      ]
    };
  }

  /** Slowest mappings by average processing time; mappings that never ran are left out. */
  private updateLatencyChart(statuses: MappingStatus[]): void {
    const timed = statuses
      .filter((st) => (st.timedMessages ?? 0) > 0)
      .map((st) => ({
        name: st.name,
        avg: Math.round((st.processingTimeTotalMs ?? 0) / st.timedMessages),
        max: st.processingTimeMaxMs ?? 0
      }))
      .sort((a, b) => b.avg - a.avg)
      .slice(0, MAX_SLOWEST_MAPPINGS)
      // echarts draws the first category at the bottom of a horizontal bar chart.
      .reverse();

    this.hasLatencyData = timed.length > 0;
    this.latencyUpdateOptions = {
      yAxis: { type: 'category', data: timed.map((t) => t.name) },
      series: [
        { type: 'bar', data: timed.map((t) => t.avg) },
        { type: 'bar', data: timed.map((t) => t.max) }
      ]
    };
  }

  private createOutcomeOptions(): EChartsOption {
    const textStyle = this.textStyle;
    // Zero-length segments would print a stray "0" on top of the neighbouring bar.
    const label = {
      show: true,
      color: '#fff',
      formatter: (p: { value?: unknown }) => (Number(p.value) > 0 ? String(p.value) : '')
    };
    return {
      legend: { data: ['Processed', 'Filtered', 'Errors'], textStyle },
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      grid: { left: '25%', right: '5%', containLabel: false },
      xAxis: {
        type: 'value',
        minInterval: 1,
        axisLabel: { ...textStyle, formatter: (v: number) => Math.round(v).toString() }
      },
      yAxis: {
        type: 'category',
        data: this.outcomeCategories,
        inverse: true,
        axisTick: { show: false },
        axisLabel: textStyle
      },
      series: [
        { name: 'Processed', type: 'bar', stack: 'outcome', color: this.colors.success, label, data: undefined },
        { name: 'Filtered', type: 'bar', stack: 'outcome', color: this.colors.neutral, label, data: undefined },
        { name: 'Errors', type: 'bar', stack: 'outcome', color: this.colors.danger, label, data: undefined }
      ]
    };
  }

  private createLatencyOptions(): EChartsOption {
    const textStyle = this.textStyle;
    return {
      legend: { data: ['Average', 'Slowest'], textStyle },
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, valueFormatter: (v) => `${v} ms` },
      grid: { left: '25%', right: '5%' },
      xAxis: {
        type: 'value',
        name: 'ms',
        axisLabel: { ...textStyle, formatter: (v: number) => Math.round(v).toString() }
      },
      yAxis: { type: 'category', data: [], axisTick: { show: false }, axisLabel: textStyle },
      series: [
        { name: 'Average', type: 'bar', color: this.colors.info, data: undefined },
        { name: 'Slowest', type: 'bar', color: this.colors.warning, data: undefined }
      ]
    };
  }
}
