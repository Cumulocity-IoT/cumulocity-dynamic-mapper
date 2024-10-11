/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack
 */

import { Component, Input, OnInit } from '@angular/core';
import { ModalLabels } from '@c8y/ngx-components';
import { Subject } from 'rxjs';
import { Mapping, MappingSubstitution } from '../../shared';
import { isDisabled } from '../shared/util';
import { MappingEnriched } from '../../shared/model/shared.model';

@Component({
  selector: 'd11r-snoop-explorer-modal',
  templateUrl: './snoop-explorer-modal.component.html'
})
export class SnoopExplorerComponent implements OnInit {
  @Input() enrichedMapping: MappingEnriched;
  mapping: Mapping;
  closeSubject: Subject<MappingSubstitution> = new Subject();
  labels: ModalLabels;
  isDisabled = isDisabled;
  template: any;
  editorOptions: any = {
    mode: 'tree',
    mainMenuBar: true,
    navigationBar: false,
    readOnly: true,
    statusBar: true
  };

  ngOnInit(): void {
    this.mapping = this.enrichedMapping.mapping;
    this.labels = {
      cancel: 'Cancel'
    };
  }

  onDismiss() {
    // console.log('Dismiss');
    this.closeSubject.next(undefined);
  }

  async onSelectSnoopedTemplate(index: any) {
    this.template = JSON.parse(this.mapping.snoopedTemplates[index]);
  }
}
