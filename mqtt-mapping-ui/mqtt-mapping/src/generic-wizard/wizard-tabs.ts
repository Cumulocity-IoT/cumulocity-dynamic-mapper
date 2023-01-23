import { Injectable } from '@angular/core';
import { Tab } from '@c8y/ngx-components';
import { Router } from '@angular/router';

@Injectable()
export class WizardTabs {
  constructor(public router: Router) {}

  get() {
    const tabs: Tab[] = [];

    if (this.router.url.match(/wizard/g)) {
      tabs.push({
        icon: 'rocket',
        priority: 1000,
        label: 'Minimal setup',
        path: 'wizard/minimal-setup'
      } as Tab);
    }
    return tabs;
  }
}
