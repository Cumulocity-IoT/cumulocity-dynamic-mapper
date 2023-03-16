// panel-wrapper.component.ts
import { Component, ViewChild, ViewContainerRef } from '@angular/core';
import { FieldWrapper } from '@ngx-formly/core';

@Component({
    selector: 'formly-wrapper-message',
    template: `
    <c8y-messages>
        <c8y-message translate>
        <span [class]="to.textClass"> 
          {{to.labely}}
        </span>
        </c8y-message>
    </c8y-messages>
  `,
})
export class MessageWrapper extends FieldWrapper {
}