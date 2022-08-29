import { Injectable } from '@angular/core';
import { TabFactory, Tab } from '@c8y/ngx-components';
import { Router } from '@angular/router';

@Injectable()
export class MQTTConfigurationTabFactory implements TabFactory {
  constructor(public router: Router) {}

  get() {
    const tabs: Tab[] = [];
    if (this.router.url.match(/mqtt/g)) {
      tabs.push({
        path: 'mqtt/configuration',
        priority: 1000,
        label: 'Configuration',
        icon: 'lock',
        orientation: 'horizontal',
      } as Tab);
      tabs.push({
        path: 'mqtt/mapping',
        priority: 1000,
        label: 'Mapping',
        icon: 'split-table',
        orientation: 'horizontal',
      } as Tab);
    }

    return tabs;
  }
}
