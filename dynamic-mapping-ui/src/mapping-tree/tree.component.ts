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
import { Component, OnInit, ViewChild, ViewEncapsulation } from '@angular/core';
import { MappingTreeService } from './tree.service';
import { JsonEditor2Component } from '../shared';

@Component({
  selector: 'd11r-mapping-tree-grid',
  templateUrl: 'tree.component.html',
  styleUrls: ['./tree.style.css'],

  encapsulation: ViewEncapsulation.None
})
export class MappingTreeComponent implements OnInit {
  constructor(private service: MappingTreeService) {}
  @ViewChild('editorTree', { static: false }) editorTree: JsonEditor2Component;
  templateTree: any;
  editorOptionsTree: any = {};

  ngOnInit(): void {
    this.editorOptionsTree = {
      ...this.editorOptionsTree,
      mode: 'tree',
      mainMenuBar: true,
      navigationBar: false,
      statusBar: false,
      readOnly: false,
      name: 'root'
    };
    this.loadMappingTree();
  }
  async loadMappingTree() {
    this.templateTree = await this.service.loadMappingTree();
    console.log('MappingTree:', this.templateTree);
  }
}
