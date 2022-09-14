import { Component, Input, OnInit, ViewChild, ViewEncapsulation } from '@angular/core';
import { Mapping } from 'src/mqtt-configuration.model';


@Component({
  selector: 'substitution-renderer',
  templateUrl: 'substitution-renderer.component.html',
  styleUrls: ['./substitution-renderer.style.css',
  ],
  encapsulation: ViewEncapsulation.None,
})

export class SubstitutionRendererComponent implements OnInit {

  @Input()
  substitutions: Mapping[] = [];

  @Input()
  setting: {color : 'green', selectedSubstitutionIndex:1};

  constructor() { }

  ngOnInit() {
    console.log ("Setting for renderer:", this.setting)
  }
}
