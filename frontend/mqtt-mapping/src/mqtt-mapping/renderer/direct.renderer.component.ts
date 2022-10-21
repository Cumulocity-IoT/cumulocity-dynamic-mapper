import { Component } from '@angular/core';
import { CellRendererContext } from '@c8y/ngx-components';

@Component({
  templateUrl: './direct.renderer.component.html'
})
export class DirectRendererComponent {
  constructor(
    public context: CellRendererContext,
  ) {
   /* console.log(typeof(context.item.direct) );
    if(typeof(context.item.direct) == 'undefined'){
      context.item.direct = false;
      console.log('init context.item.direct to false');
    }*/
  }
}