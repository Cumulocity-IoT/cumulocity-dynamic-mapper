import { Component, Input, OnInit } from '@angular/core';
import {
  Chart,
  ChartType,
  registerables
} from 'chart.js';
import { CHART_COLORS, transparentize } from './util';
import { Subject } from 'rxjs';
import { Direction, MappingStatus } from '../../shared';
import { map } from 'rxjs/operators';
Chart.register(...registerables);
@Component({
  selector: 'd11r-monitoring-chart',
  templateUrl: './chart.component.html',
  styleUrls: ['./chart.component.css']
})
export class ChartComponent implements OnInit {
  constructor() {}
  @Input()
  mappingStatus: Subject<MappingStatus[]>;

  private statusMappingChart: Chart;
  private lineChartType: ChartType = 'bar';

  ngOnInit() {
    const statistic = [0, 0, 0, 0];
    const data = {
      labels: [
        'Errors',
        'Messages Received',
        'Snooped Templates Total',
        'Snooped Templates Active'
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
    this.mappingStatus
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
      type: this.lineChartType,
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
          },
          title: {
            align: 'left' as any,
            display: true,
            text: 'Messages Inbound & Outbound',
            position: 'top' as any,
            font: {
              size: 14,
              weight: 'normal' as any
            }
          }
        },
        indexAxis: 'y' as any,
        scales: {
          y: {
            grid: {
              display: true
            },
            ticks: {
              sampleSize: 4,
            }
          }
        }
        // scales: {
        //   // xAxes: [
        //   //   {
        //   //     ticks: {
        //   //       maxRotation: 90,
        //   //       minRotation: 80
        //   //     }
        //   //   }
        //   // ]

        // }
      }
    };
    this.statusMappingChart = new Chart('monitoringChart', config);
  }
}
