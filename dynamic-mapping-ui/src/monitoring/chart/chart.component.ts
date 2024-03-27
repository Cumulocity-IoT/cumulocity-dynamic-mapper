import { Component, ElementRef, OnDestroy, OnInit } from '@angular/core';
import { Chart, registerables } from 'chart.js';
import { CHART_COLORS, transparentize } from './util';
import { Subject } from 'rxjs';
import { Direction, MappingStatus } from '../../shared';
import { map } from 'rxjs/operators';
import { MonitoringService } from '../shared/monitoring.service';
import { BrokerConfigurationService } from '../../configuration';
Chart.register(...registerables);
// Chart.defaults.font.family = 'Roboto, Helvetica, Arial, sans-serif';
// Chart.defaults.color = 'green';

@Component({
  selector: 'd11r-monitoring-chart',
  templateUrl: './chart.component.html',
  styleUrls: ['./chart.component.css']
})
export class MonitoringChartComponent implements OnInit, OnDestroy {
  constructor(
    private el: ElementRef,
    public monitoringService: MonitoringService,
    public brokerConfigurationService: BrokerConfigurationService
  ) {}

  mappingStatus$: Subject<MappingStatus[]> = new Subject<MappingStatus[]>();
  subscription: object;
  statusMappingChart: Chart;
  textColor: string;
  fontFamily: string;
  fontWeight: string;
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
    this.fontWeight = getComputedStyle(root)
      .getPropertyValue('--c8y-font-weight-headings')
      .trim();
    this.fontSize = parseInt(
      getComputedStyle(root).getPropertyValue('--c8y-font-size-base').trim(),
      12
    );
    // rgb(100, 31, 61), 'Roboto, Helvetica, Arial, sans-serif'
    // console.log('Text Color', this.textColor);

    const statistic = [0, 0, 0, 0];
    const data = {
      labels: [
        'Errors',
        'Messages received',
        'Snooped templates total',
        'Snooped templates active'
      ],
      datasets: [
        {
          label: 'Inbound',
          data: statistic,
          backgroundColor: transparentize(CHART_COLORS.green, 0.3)
        },
        {
          label: 'Outbound',
          data: statistic,
          backgroundColor: transparentize(CHART_COLORS.orange, 0.3)
        }
      ]
    };
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
          data.datasets[0].data = [
            inbound.errors,
            inbound.messagesReceived,
            inbound.snoopedTemplatesTotal,
            inbound.snoopedTemplatesActive
          ];
          data.datasets[1].data = [
            outbound.errors,
            outbound.messagesReceived,
            outbound.snoopedTemplatesTotal,
            outbound.snoopedTemplatesActive
          ];
          this.statusMappingChart.update();
        }
      });

    const config = {
      type: 'bar' as any,
      data: data,
      options: {
        responsive: true,
        maintainAspectRatio: false,
        layout: {
          padding: {
            left: 0,
            right: 0,
            top: 0,
            bottom: 0
          }
        },
        plugins: {
          legend: {
            display: true,
            position: 'left' as any,
            font: {
              family: this.fontFamily,
              weight: 'normal' as any
            }
          }
        },
        indexAxis: 'y' as any,
        color: this.textColor as any,
        scales: {
          y: {
            ticks: {
              color: this.textColor as any
            }
          },
          x: {
            ticks: {
              color: this.textColor as any,
              stepSize: 1
            }
          }
        }
      }
    };
    this.statusMappingChart = new Chart('monitoringChart', config);
  }

  private async initializeMonitoringService() {
    this.subscription =
      await this.monitoringService.subscribeMonitoringChannel();
    this.monitoringService
      .getCurrentMappingStatus()
      .subscribe((status) => this.mappingStatus$.next(status));
  }

  ngOnDestroy(): void {
    console.log('Stop subscription');
    this.monitoringService.unsubscribeFromMonitoringChannel(this.subscription);
  }
}
