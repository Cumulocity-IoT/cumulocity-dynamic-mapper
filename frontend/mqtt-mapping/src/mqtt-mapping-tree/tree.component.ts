import { Component, OnInit, ViewChild, ViewEncapsulation } from '@angular/core';
import { JsonEditorComponent, JsonEditorOptions } from '../shared/editor/jsoneditor.component';
import { MappingTreeService } from './tree.service';

@Component({
  selector: 'tree-grid',
  templateUrl: 'tree.component.html',
  styleUrls: ['./tree.style.css'],

  encapsulation: ViewEncapsulation.None,
})

export class MappingTreeComponent implements OnInit {
  constructor(private service: MappingTreeService) {
  }
  @ViewChild('editorTree', { static: false }) editorTree: JsonEditorComponent;
  templateTree: any;
  editorOptionsTree: JsonEditorOptions = new JsonEditorOptions();

  ngOnInit(): void {
    this.editorOptionsTree = {
      ...this.editorOptionsTree,
      modes: ['tree'],
      statusBar: true,
      navigationBar: true,
      enableSort: true,
      enableTransform: false,
      mainMenuBar: true,
      search: true
    };
    this.loadMappingTree();
  }
  async loadMappingTree() {
    this.templateTree = await this.service.loadMappingTree();
    console.log("MappingTree:", this.templateTree);
  }
}
