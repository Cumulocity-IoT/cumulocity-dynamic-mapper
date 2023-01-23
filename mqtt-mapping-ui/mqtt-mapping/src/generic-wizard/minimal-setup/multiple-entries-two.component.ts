import { Component } from '@angular/core';
import { WizardComponent } from '@c8y/ngx-components';

@Component({
  selector: 'multiple-entries-two-component',
  template: `
    <c8y-wizard-header> New header entry 2 </c8y-wizard-header>
    <c8y-wizard-body> New body </c8y-wizard-body>
    <c8y-wizard-footer>
      <button (click)="back()" class="btn btn-default" title="{{ 'Back' }}">Back</button>
      <button (click)="cancel()" class="btn btn-default" title="{{ 'Cancel' }}">Cancel</button>
    </c8y-wizard-footer>
  `
})
export class MultipleEntriesTwo {
  constructor(private wizardComponent: WizardComponent) {}

  cancel() {
    this.wizardComponent.close('Cancel triggered');
  }

  back() {
    this.wizardComponent.reset();
  }
}
