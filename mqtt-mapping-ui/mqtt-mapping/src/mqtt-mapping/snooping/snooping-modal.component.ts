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
import { Component, Input, OnInit, ViewChild } from "@angular/core";
import {
  ConfirmModalComponent,
  gettext,
  ModalLabels,
  Status,
  StatusType,
} from "@c8y/ngx-components";
import { TranslateService } from "@ngx-translate/core";
import { Subject } from "rxjs";
import { SnoopStatus } from "../../shared/mapping.model";

@Component({
  selector: "mapping-snooping-modal",
  templateUrl: "snooping-modal.component.html",
})
export class SnoopingModalComponent implements OnInit {
  @ViewChild("snoopingRef", { static: false })
  snoopingRef: ConfirmModalComponent;

  @Input()
  snoopStatus: SnoopStatus;
  @Input()
  numberSnooped: number;

  SnoopStatus = SnoopStatus;
  labels: ModalLabels = { ok: gettext("Confirm") };
  title = gettext("Snooping");
  status: StatusType = Status.INFO;
  closeSubject: Subject<boolean> = new Subject();

  constructor(private translateService: TranslateService) {}

  ngOnInit() {}

  async ngAfterViewInit() {
    try {
      await this.snoopingRef.result;
      this.onClose();
    } catch (error) {
      this.onDismiss();
    }
  }

  onClose() {
    this.closeSubject.next(true);
    this.closeSubject.complete();
  }

  onDismiss() {
    this.closeSubject.next(false);
    this.closeSubject.complete();
  }
}
