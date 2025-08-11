// input-list-formly.component.ts
import { Component } from '@angular/core';
import { FieldType, FieldTypeConfig } from '@ngx-formly/core';

@Component({
  selector: 'd11r-input-list-formly',
  template: `
    <d11r-input-list 
      [data]="getCurrentData()"
      (dataChange)="onDataChange($event)"
      [disabled]="props.disabled">
    </d11r-input-list>
  `,
  standalone: false,
})
export class InputListFormlyComponent extends FieldType<FieldTypeConfig> {
  private isUpdating = false;
  
  getCurrentData(): Record<string, string> | null {
    return this.formControl.value || {};
  }
  
  onDataChange(arrayData: Array<{ key: string; value: string | undefined }>) {
    // Prevent recursive updates
    if (this.isUpdating) {
      return;
    }
    
    this.isUpdating = true;
    
    try {
      // Convert array back to object format for storage
      const objectData: Record<string, string> = {};
      
      arrayData.forEach(item => {
        if (item.key && item.key.trim() !== '') {
          objectData[item.key] = item.value || '';
        }
      });
      
      // Only update if the data actually changed
      const currentValue = this.formControl.value || {};
      if (JSON.stringify(currentValue) !== JSON.stringify(objectData)) {
        this.formControl.setValue(objectData);
        this.formControl.markAsDirty();
        this.formControl.updateValueAndValidity();
      }
    } finally {
      // Use setTimeout to ensure the flag is reset after change detection
      setTimeout(() => {
        this.isUpdating = false;
      }, 0);
    }
  }
}