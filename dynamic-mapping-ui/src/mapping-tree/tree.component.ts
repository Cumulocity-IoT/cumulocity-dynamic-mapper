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
import { Component, OnInit, ViewChild, ViewEncapsulation } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { JsonEditorComponent } from '../shared';
import { MappingTreeService } from './tree.service';

@Component({
  selector: 'd11r-mapping-tree-grid',
  templateUrl: 'tree.component.html',
  styleUrls: ['./tree.style.css'],
  encapsulation: ViewEncapsulation.None,
  standalone: false
})
export class MappingTreeComponent implements OnInit {
  constructor(
    private service: MappingTreeService,
    private route: ActivatedRoute,
  ) { }

  @ViewChild('editorTree', { static: false }) editorTree: JsonEditorComponent;
  mappingTree$: BehaviorSubject<any> = new BehaviorSubject([]);
  editorOptionsTree: any = {
    mode: 'tree',
    removeModes: ['text', 'table'],
    mainMenuBar: true,
    navigationBar: false,
    statusBar: false,
    readOnly: true,
  };

  ngOnInit() {
    this.mappingTree$.next(this.route.snapshot.data['mappingTree']);
  }

  async loadMappingTree() {
    this.mappingTree$.next(await this.service.loadMappingTree());
  }

  //   ngAfterViewInit() {
  //     setTimeout(async () => {
  //       await this.loadMappingTree();
  //     }, 0);
  //   }
}
