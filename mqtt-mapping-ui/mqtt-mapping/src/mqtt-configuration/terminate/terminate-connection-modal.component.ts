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
import { Component, OnInit, ViewChild } from "@angular/core";
import {
  ConfirmModalComponent,
  gettext,
  ModalLabels,
  Status,
  StatusType,
} from "@c8y/ngx-components";
import { TranslateService } from "@ngx-translate/core";
import { Subject } from "rxjs";

@Component({
  selector: "d11r-mapping-terminate-connection",
  templateUrl: "terminate-connection-modal.component.html",
})
export class TerminateBrokerConnectionModalComponent implements OnInit {
  @ViewChild("modalRef", { static: false }) modalRef: ConfirmModalComponent;
  message: string;
  closeSubject: Subject<boolean> = new Subject();
  labels: ModalLabels = {
    ok: gettext("Disconnect"),
    cancel: gettext("Cancel"),
  };
  title = gettext("Disconnect");
  status: StatusType = Status.WARNING;

  constructor(private translateService: TranslateService) {}

  ngOnInit() {
    this.message = this.translateService.instant(
      gettext("You are about to diconnect. Do you want to proceed?")
    );
  }

  async ngAfterViewInit() {
    try {
      await this.modalRef.result;
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
