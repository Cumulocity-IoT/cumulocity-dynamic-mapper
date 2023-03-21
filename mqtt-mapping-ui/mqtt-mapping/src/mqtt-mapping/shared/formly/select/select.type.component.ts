import { ChangeDetectionStrategy, Component, OnInit } from '@angular/core';
import { gettext } from '@c8y/ngx-components';
import { ConfigOption, FieldType } from '@ngx-formly/core';
import { TranslateService } from '@ngx-translate/core';
import { get } from 'lodash-es';
import { defer, isObservable, of } from 'rxjs';
import { map, startWith, switchMap } from 'rxjs/operators';

@Component({
  selector: 'c8y-select-type',
  templateUrl: './select.type.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class SelectComponent extends FieldType implements OnInit {
  static readonly CONFIG: ConfigOption = {
    types: [
      { name: 'select', component: SelectComponent, wrappers: ['c8y-form-field'] },
      { name: 'enum', extends: 'select' }
    ]
  };

  labelProp = 'label';
  valueProp = 'value';

  placeholder$ = defer(() => of(this.to?.placeholder)).pipe(
    switchMap(placeholder =>
      placeholder
        ? of(placeholder)
        : this.defaultPlaceholder$.pipe(
            startWith(this.translateService.instant(gettext('Select your option')))
          )
    )
  );

  defaultPlaceholder$ = defer(() =>
    isObservable(this.to?.options) ? this.to?.options : of(this.to?.options)
  ).pipe(
    map(data => get(data[0], this.labelProp)),
    map(example =>
      this.translateService.instant(
        !example ? gettext('No items') : gettext('Select your option, for example, {{ example }}'),
        { example }
      )
    )
  );

  constructor(private translateService: TranslateService) {
    super();
  }

  ngOnInit() {
    if (this.to?.labelProp?.length > 0) {
      this.labelProp = this.to.labelProp;
    }

    if (this.to?.valueProp?.length > 0) {
      this.valueProp = this.to.valueProp;
    }
  }
}
