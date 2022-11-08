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
  
  @ViewChild('editorTree', { static: false }) editorSource: JsonEditorComponent;
  templateTree: any;
  editorOptionsTree: JsonEditorOptions = new JsonEditorOptions();
  ngOnInit(): void {
    this.editorOptionsTree = {
      ...this.editorOptionsTree,
      modes: ['form'],
      statusBar: false,
      navigationBar: false,
      enableSort: false,
      enableTransform: false,
    };
    this.loadMappingTree();
  }
  async loadMappingTree() {
    this.templateTree = await this.service.loadMappingTree();
    console.log ("MappingTree:", this.templateTree);
  }
}
