import { Component } from '@angular/core';

@Component({
  selector: 'container-component',
  template: `
    <c8y-wizard-header> New header </c8y-wizard-header>
    <c8y-wizard-body> New body </c8y-wizard-body>
  `
})
export class MinimalSetupComponent {
  // In this case cancel method is not required, because it is the default button when the <c8y-wizard-footer> tag is missing.
}
