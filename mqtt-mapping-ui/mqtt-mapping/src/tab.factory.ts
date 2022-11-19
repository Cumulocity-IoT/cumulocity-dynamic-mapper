import { Injectable } from '@angular/core';
import { TabFactory, Tab } from '@c8y/ngx-components';
import { Router } from '@angular/router';

@Injectable()
export class MappingTabFactory implements TabFactory {
  constructor(public router: Router) {}

  get() {
    //console.log("MappingTabFactory",this.router.url, this.router.url.match(/mqtt-mapping/g));
    const tabs: Tab[] = [];
    if (this.router.url.match(/mqtt-mapping/g)) {
      tabs.push({
        path: 'mqtt-mapping/configuration',
        priority: 930,
        label: 'Configuration',
        icon: 'cog',
        orientation: 'horizontal',
      } as Tab);
      tabs.push({
        path: 'mqtt-mapping/mappings',
        priority: 920,
        label: 'Mapping',
        icon: 'grid-view',
        orientation: 'horizontal',
      } as Tab);
      tabs.push({
        path: 'mqtt-mapping/monitoring',
        priority: 910,
        label: 'Monitoring',
        icon: 'monitoring',
        orientation: 'horizontal',
      } as Tab);
      tabs.push({
        path: 'mqtt-mapping/testing',
        priority: 900,
        label: 'Testing Devices',
        icon: 'reflector-bulb',
        orientation: 'horizontal',
      } as Tab);
      tabs.push({
        path: 'mqtt-mapping/tree',
        priority: 890,
        label: 'Mapping Tree',
        icon: 'tree-structure',
        orientation: 'horizontal',
      } as Tab);
      tabs.push({
        path: 'mqtt-mapping/extensions',
        priority: 880,
        label: 'Processor Extension',
        icon: 'plugin',
        orientation: 'horizontal',
      } as Tab);
    }

    return tabs;
  }
}
