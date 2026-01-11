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
import { NgxEchartsModule, provideEcharts } from 'ngx-echarts';
import { Subject } from 'rxjs';
import { map, takeUntil } from 'rxjs/operators';
import { Direction, MappingStatus } from '../../shared';
import { MonitoringService } from '../shared/monitoring.service';
import { CHART_COLORS } from './util';

interface AccumulatedStats {
  direction: Direction;
  errors: number;
  messagesReceived: number;
  snoopedTemplatesTotal: number;
  snoopedTemplatesActive: number;
}

@Component({
  selector: 'd11r-monitoring-chart',
  templateUrl: './chart.component.html',
  styleUrls: ['./chart.component.css'],
  standalone: true,
  imports: [CoreModule, NgxEchartsModule],
  providers: [provideEcharts()]
})
export class MonitoringChartComponent implements OnInit, OnDestroy {
  mappingStatus$ = new Subject<MappingStatus[]>();
  echartOptions: EChartsOption;
  echartUpdateOptions: EChartsOption;
  echartsInstance: ECharts;

  private readonly destroy$ = new Subject<void>();
  private readonly yAxisData = ['Errors', 'Messages received', 'Snooped templates total', 'Snooped templates active'];
  private textColor: string;
  private fontFamily: string;
  private fontWeight: number;
  private fontSize: number;

  constructor(
    private readonly el: ElementRef,
    public readonly monitoringService: MonitoringService
  ) {}

  ngOnInit(): void {
    this.initializeThemeVariables();
    this.initializeMonitoringService();
    this.setupChartDataSubscription();
    this.echartOptions = this.createChartOptions();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.monitoringService.stopMonitoring();
  }

  onChartInit(ec: ECharts): void {
    this.echartsInstance = ec;
  }

  private initializeThemeVariables(): void {
    const root = this.el.nativeElement.ownerDocument.documentElement;
    const computedStyle = getComputedStyle(root);

    this.textColor = computedStyle.getPropertyValue('--c8y-text-color').trim();
    this.fontFamily = computedStyle.getPropertyValue('--c8y-font-family-sans-serif').trim();
    this.fontWeight = parseInt(computedStyle.getPropertyValue('--c8y-font-weight-headings').trim());
    this.fontSize = parseInt(computedStyle.getPropertyValue('--c8y-font-size-base').trim());
  }

  private async initializeMonitoringService(): Promise<void> {
    await this.monitoringService.startMonitoring();
    this.monitoringService
      .getMappingStatus()
      .pipe(takeUntil(this.destroy$))
      .subscribe((status) => this.mappingStatus$.next(status));
  }

  private setupChartDataSubscription(): void {
    this.mappingStatus$
      .pipe(
        map((statuses) => this.aggregateStatsByDirection(statuses)),
        takeUntil(this.destroy$)
      )
      .subscribe((aggregated) => {
        if (aggregated) {
          this.updateChartData(aggregated);
        }
      });
  }

  private aggregateStatsByDirection(statuses: MappingStatus[]): [AccumulatedStats, AccumulatedStats] {
    const initialStats: [AccumulatedStats, AccumulatedStats] = [
      this.createEmptyStats(Direction.INBOUND),
      this.createEmptyStats(Direction.OUTBOUND)
    ];

    return statuses?.reduce((acc, status) => {
      const [inbound, outbound] = acc;
      const target = status.direction === Direction.INBOUND || !status.direction ? inbound : outbound;

      this.accumulateStats(target, status);
      return [inbound, outbound];
    }, initialStats) || initialStats;
  }

  private createEmptyStats(direction: Direction): AccumulatedStats {
    return {
      direction,
      errors: 0,
      messagesReceived: 0,
      snoopedTemplatesTotal: 0,
      snoopedTemplatesActive: 0
    };
  }

  private accumulateStats(target: AccumulatedStats, status: MappingStatus): void {
    target.errors += status.errors;
    target.messagesReceived += status.messagesReceived;
    target.snoopedTemplatesTotal += status.snoopedTemplatesTotal;
    target.snoopedTemplatesActive += status.snoopedTemplatesActive;
  }

  private updateChartData([inbound, outbound]: [AccumulatedStats, AccumulatedStats]): void {
    const inboundData = [
      inbound.errors,
      inbound.messagesReceived,
      inbound.snoopedTemplatesTotal,
      inbound.snoopedTemplatesActive
    ];

    const outboundData = [
      outbound.errors,
      outbound.messagesReceived,
      outbound.snoopedTemplatesTotal,
      outbound.snoopedTemplatesActive
    ];

    this.echartUpdateOptions = {
      series: [
        { type: 'bar', data: inboundData },
        { type: 'bar', data: outboundData }
      ]
    };
  }

  private createChartOptions(): EChartsOption {
    const textStyle = {
      fontSize: this.fontSize,
      color: this.textColor,
      fontFamily: this.fontFamily
    };

    return {
      title: {
        show: false,
        text: 'Messages processed',
        textStyle: {
          ...textStyle,
          fontSize: this.fontSize + 4,
          fontWeight: this.fontWeight
        }
      },
      legend: {
        data: ['Inbound', 'Outbound'],
        align: 'left',
        textStyle
      },
      tooltip: {},
      grid: {
        left: '20%'
      },
      xAxis: {
        type: 'value',
        boundaryGap: [0, 0.01],
        axisLabel: textStyle
      },
      yAxis: {
        axisTick: { show: false },
        type: 'category',
        data: this.yAxisData,
        silent: false,
        splitLine: { show: true },
        axisLabel: textStyle
      },
      series: [
        {
          name: 'Inbound',
          color: CHART_COLORS.green,
          type: 'bar',
          data: undefined
        },
        {
          name: 'Outbound',
          color: CHART_COLORS.orange,
          type: 'bar',
          data: undefined
        }
      ]
    };
  }
}
