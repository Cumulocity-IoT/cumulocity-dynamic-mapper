/*
 * Copyright (c) 2025 Cumulocity GmbH
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
import {
  Component,
  EventEmitter,
  Input,
  OnInit,
  Output,
  ViewEncapsulation
} from '@angular/core';
import { IIdentified } from '@c8y/client';
import { CoreModule } from '@c8y/ngx-components';
import { AssetSelectionChangeEvent, AssetSelectorModule } from '@c8y/ngx-components/assets-navigator';

@Component({
  selector: 'd11r-device-selector-subscription3',
  templateUrl: 'device-selector-subscription3.component.html',
  styleUrls: ['../../shared/mapping.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: true,
  imports: [CoreModule, AssetSelectorModule]

})
export class DeviceSelectorSubscription3Component implements OnInit {
  @Input() deviceList: IIdentified[];

  @Output() cancel = new EventEmitter<any>();
  @Output() commit = new EventEmitter<IIdentified[]>();

  constructor() {
  }

  ngOnInit(): void {
  }

  selectionChanged(event: AssetSelectionChangeEvent) {
    // console.log(event);
  }

  clickedUpdateSubscription() {
    this.commit.emit(this.deviceList);
  }

  clickedCancel() {
    this.cancel.emit();
  }
}
