import { Component, Input, OnInit } from '@angular/core';
import { Chart, ChartType, LayoutPosition, registerables } from 'chart.js';
import { CHART_COLORS } from './util';
import { Subject } from 'rxjs';
import { MappingStatus } from '../../shared';
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

  statusMappingChart: Chart;
  public lineChartType: ChartType = 'bar';
  public positionChart: LayoutPosition = 'left';

  ngOnInit() {
    const statistic = [0, 0, 0, 0];
    const data = {
      labels: [
        '# Errors',
        '# Messages Received',
        '# Snooped Templates Total',
        '# Snooped Templates Active'
      ],
      datasets: [
        {
          data: statistic,
          backgroundColor: Object.values(CHART_COLORS)
        }
      ]
    };
    this.mappingStatus
      .pipe(
        map((data01) => {
          // console.log('data01', acc01, data01);
          return data01.reduce(
            (acc02, data02) => {
              // console.log('data02', acc02, data02);
              return {
                errors: acc02.errors + data02.errors,
                messagesReceived:
                  acc02.messagesReceived + data02.messagesReceived,
                snoopedTemplatesTotal:
                  acc02.snoopedTemplatesTotal + data02.snoopedTemplatesTotal,
                snoopedTemplatesActive:
                  acc02.snoopedTemplatesActive + data02.snoopedTemplatesActive
              } as Partial<MappingStatus>;
            },
            {
              errors: 0,
              messagesReceived: 0,
              snoopedTemplatesTotal: 0,
              snoopedTemplatesActive: 0
            } as Partial<MappingStatus>
          );
        })
      )
      .subscribe((total) => {
        // console.log('Statistic', total);
        data.datasets[0].data = [
          total.errors,
          total.messagesReceived,
          total.snoopedTemplatesTotal,
          total.snoopedTemplatesActive
        ];
        // statistic[0] = total.errors;
        // statistic[1] = total.messagesReceived;
        // statistic[2] = total.snoopedTemplatesTotal;
        // statistic[3] = total.snoopedTemplatesActive;
        this.statusMappingChart.update();
      });

    const config = {
      type: this.lineChartType,
      data: data,
      options: {
        responsive: true,
        plugins: {
          legend: {
            display: false
          }
        },
        // scales: {
        //   xAxes: [
        //     {
        //       ticks: {
        //         maxRotation: 90,
        //         minRotation: 80
        //       }
        //     }
        //   ]
        // }
      }
    };
    this.statusMappingChart = new Chart('myChart', config);
  }
}
