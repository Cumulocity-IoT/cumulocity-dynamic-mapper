import { Component, EventEmitter, Input, OnInit, Output, ViewChild, ViewEncapsulation } from '@angular/core';
import { Mapping } from '../../../shared/configuration.model';


@Component({
  selector: 'mapping-substitution-renderer',
  templateUrl: 'substitution-renderer.component.html',
  styleUrls: ['./substitution-renderer.style.css',
  ],
  encapsulation: ViewEncapsulation.None,
})

export class SubstitutionRendererComponent implements OnInit {

  @Input()
  substitutions: Mapping[] = [];

  @Input()
  setting: any;

  @Output() onSelect = new EventEmitter<number>();

  constructor() { }

  ngOnInit() {
    console.log ("Setting for renderer:", this.setting)
  }

  onSubstitutionSelected (index: number) {
    console.log("Selected substitution:", index);
    this.setting.selectedSubstitutionIndex = index;
    this.onSelect.emit(index);
  }
}
