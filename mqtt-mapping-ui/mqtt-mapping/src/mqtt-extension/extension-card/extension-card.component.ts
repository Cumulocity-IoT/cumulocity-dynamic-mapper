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
import { Component, EventEmitter, Input, OnInit, Output } from "@angular/core";
import { ActivatedRoute, Router } from "@angular/router";
import { IManagedObject } from "@c8y/client";
import { AlertService } from "@c8y/ngx-components";
import { ExtensionStatus } from "../../shared/mapping.model";
import { ExtensionService } from "../share/extension.service";

@Component({
  selector: "mapping-extension-card",
  templateUrl: "./extension-card.component.html",
})
export class ExtensionCardComponent implements OnInit {
  @Input() app: IManagedObject;
  @Output() onAppDeleted: EventEmitter<void> = new EventEmitter();

  @Input() loaded: any = true;
  ExtensionStatus = ExtensionStatus;
  external: boolean = true;

  constructor(
    private extensionService: ExtensionService,
    private alertService: AlertService,
    private router: Router,
    private activatedRoute: ActivatedRoute
  ) {}

  async ngOnInit() {
    this.external = this.app?.external;
  }

  async detail() {
    //this.router.navigateByUrl(`/sag-ps-pkg-mqtt-mapping/extensions/${this.app.id}`);
    this.router.navigate(["properties/", this.app.id], {
      relativeTo: this.activatedRoute,
    });
    console.log("Details clicked now:", this.app.id);
  }

  async delete() {
    try {
      await this.extensionService.deleteExtension(this.app);
      this.onAppDeleted.emit();
    } catch (ex) {
      if (ex) {
        this.alertService.addServerFailure(ex);
      }
    }
  }
}
