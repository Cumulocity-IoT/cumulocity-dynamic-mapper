// panel-wrapper.component.ts
import { Component } from '@angular/core';
import { FieldType } from '@ngx-formly/core';

@Component({
    selector: 'formly-field-message',
    template: `
    <c8y-messages>
        <c8y-message [class]='to.textClass' *ngIf='to.enabled'>
          {{to.content}}
        </c8y-message>
    </c8y-messages>
  `,
})
export class MessageField extends FieldType {
}