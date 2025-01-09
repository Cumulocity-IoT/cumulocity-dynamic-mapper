import { Component, ElementRef, OnDestroy, OnInit } from '@angular/core';
import { CHART_COLORS } from './util';
import { Subject } from 'rxjs';
import { Direction, MappingStatus } from '../../shared';
import { map } from 'rxjs/operators';
import { MonitoringService } from '../shared/monitoring.service';
import { ECharts, EChartsOption } from 'echarts';

@Component({
  selector: 'd11r-monitoring-chart',
  templateUrl: './chart.component.html',
  styleUrls: ['./chart.component.css']
})
export class MonitoringChartComponent implements OnInit, OnDestroy {
  constructor(
    private el: ElementRef,
    public monitoringService: MonitoringService
  ) {}

  mappingStatus$: Subject<MappingStatus[]> = new Subject<MappingStatus[]>();
  echartOptions: EChartsOption;
  echartUpdateOptions: EChartsOption;
  echartsInstance: any;
  textColor: string;
  fontFamily: string;
  fontWeight: number;
  fontSize: number;

  ngOnInit() {
    this.initializeMonitoringService();

    const root = this.el.nativeElement.ownerDocument.documentElement;
    this.textColor = getComputedStyle(root)
      .getPropertyValue('--c8y-text-color')
      .trim();
    this.fontFamily = getComputedStyle(root)
      .getPropertyValue('--c8y-font-family-sans-serif')
      .trim();
    this.fontWeight = parseInt(
      getComputedStyle(root)
        .getPropertyValue('--c8y-font-weight-headings')
        .trim()
    );
    this.fontSize = parseInt(
      getComputedStyle(root).getPropertyValue('--c8y-font-size-base').trim()
    );

    const data = [undefined, undefined];
    this.mappingStatus$
      .pipe(
        map((data01) => {
          // console.log('data01', acc01, data01);
          return data01?.reduce(
            (acc02, data02) => {
              const [inbound, outbound] = acc02;
              // console.log('data02', acc02, data02);
              if (data02.direction == Direction.INBOUND || !data02.direction) {
                inbound.errors = inbound.errors + data02.errors;
                inbound.messagesReceived =
                  inbound.messagesReceived + data02.messagesReceived;
                inbound.snoopedTemplatesTotal =
                  inbound.snoopedTemplatesTotal + data02.snoopedTemplatesTotal;
                inbound.snoopedTemplatesActive =
                  inbound.snoopedTemplatesActive +
                  data02.snoopedTemplatesActive;
              } else {
                outbound.errors = outbound.errors + data02.errors;
                outbound.messagesReceived =
                  outbound.messagesReceived + data02.messagesReceived;
                outbound.snoopedTemplatesTotal =
                  outbound.snoopedTemplatesTotal + data02.snoopedTemplatesTotal;
                outbound.snoopedTemplatesActive =
                  outbound.snoopedTemplatesActive +
                  data02.snoopedTemplatesActive;
              }
              return [inbound, outbound];
            },
            [
              {
                direction: Direction.INBOUND,
                errors: 0,
                messagesReceived: 0,
                snoopedTemplatesTotal: 0,
                snoopedTemplatesActive: 0
              } as Partial<MappingStatus>,
              {
                direction: Direction.OUTBOUND,
                errors: 0,
                messagesReceived: 0,
                snoopedTemplatesTotal: 0,
                snoopedTemplatesActive: 0
              } as Partial<MappingStatus>
            ]
          );
        })
      )
      .subscribe((total) => {
        // console.log('Statistic', total);
        if (total) {
          const [inbound, outbound] = total;
          data[0] = [
            inbound.errors,
            inbound.messagesReceived,
            inbound.snoopedTemplatesTotal,
            inbound.snoopedTemplatesActive
          ];
          data[1] = [
            outbound.errors,
            outbound.messagesReceived,
            outbound.snoopedTemplatesTotal,
            outbound.snoopedTemplatesActive
          ];
          //   this.statusMappingChart.update();
          this.echartUpdateOptions = {
            series: [
              {
                type: 'bar',
                data: data[0]
              },
              {
                type: 'bar',
                data: data[1]
              }
            ]
          };
          // this.echartsInstance?.setOption(this.echartUpdateOptions);
        }
      });

    const yAxisData = [
      'Errors',
      'Messages received',
      'Snooped templates total',
      'Snooped templates active'
    ];

    this.echartOptions = {
      title: {
        show: false,
        text: 'Messages processed',
        textStyle: {
          color: this.textColor,
          fontFamily: this.fontFamily,
          fontSize: this.fontSize + 4,
          fontWeight: this.fontWeight
        }
      },
      legend: {
        data: ['Inbound', 'Outbound'],
        align: 'left'
      },
      tooltip: {},
      grid: {
        left: '20%' // Adjust this value as needed
      },
      xAxis: {
        type: 'value',
        boundaryGap: [0, 0.01]
      },
      yAxis: {
        axisTick: {
          show: false
        },
        type: 'category',
        data: yAxisData,
        silent: false,
        splitLine: {
          show: true
        }
      },
      series: [
        {
          name: 'Inbound',
          color: CHART_COLORS.green,
          type: 'bar',
          data: data[0]
          //   animationDelay: (idx) => idx * 10 + 100
        },
        {
          name: 'Outbound',
          color: CHART_COLORS.orange,
          type: 'bar',
          data: data[1]
          //   animationDelay: (idx) => idx * 10
        }
      ]
      //   animationEasing: 'elasticOut',
      //   animationDelayUpdate: (idx) => idx * 5
    };
  }

  onChartInit(ec: ECharts) {
    this.echartsInstance = ec;
  }

  private async initializeMonitoringService() {
    await this.monitoringService.startMonitoring();
    this.monitoringService
      .getCurrentMappingStatus()
      .subscribe((status) => this.mappingStatus$.next(status));
  }

  ngOnDestroy(): void {
    // console.log('Stop subscription');
    this.monitoringService.stopMonitoring();
  }

  randomIntFromInterval(min, max) {
    // min and max included
    return Math.floor(Math.random() * (max - min + 1) + min);
  }
}
