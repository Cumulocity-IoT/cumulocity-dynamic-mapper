// /*
//  * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
//  * and/or its subsidiaries and/or its affiliates and/or their licensors.
//  *
//  * SPDX-License-Identifier: Apache-2.0
//  *
//  * Licensed under the Apache License, Version 2.0 (the "License");
//  * you may not use this file except in compliance with the License.
//  * You may obtain a copy of the License at
//  *
//  *      http://www.apache.org/licenses/LICENSE-2.0
//  *
//  * Unless required by applicable law or agreed to in writing, software
//  * distributed under the License is distributed on an "AS IS" BASIS,
//  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  * See the License for the specific language governing permissions and
//  * limitations under the License.
//  *
//  * @authors Christof Strack
//  */

import { ChangeDetectionStrategy, Component, OnInit } from '@angular/core';
import { ConfigOption, FieldType, FormlyFieldProps } from '@ngx-formly/core';
import { TranslateService } from '@ngx-translate/core';
import { get } from 'lodash-es';
import { defer, isObservable, of } from 'rxjs';
import { map, startWith, switchMap } from 'rxjs/operators';
import { gettext } from '@c8y/ngx-components';

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

  private properties: FormlyFieldProps & { [additioinalProperties: string]: unknown };

  placeholder$ = defer(() => of(this.properties?.placeholder)).pipe(
    switchMap(placeholder =>
      placeholder
        ? of(placeholder)
        : this.defaultPlaceholder$.pipe(
            startWith(this.translateService.instant(gettext('Select your option')))
          )
    )
  );

  defaultPlaceholder$ = defer(() =>
    isObservable(this.properties?.options) ? this.properties?.options : of(this.properties?.options)
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
    this.properties = this.props || this.to;
    if ((this.properties?.labelProp as string)?.length > 0) {
      this.labelProp = this.properties.labelProp as string;
    }

    if ((this.properties?.valueProp as string)?.length > 0) {
      this.valueProp = this.properties.valueProp as string;
    }
  }
}

