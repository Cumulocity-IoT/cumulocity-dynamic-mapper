import { Component } from '@angular/core';
import { WizardConfig, WizardModalService } from '@c8y/ngx-components';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';

@Component({
  selector: 'container-component',
  templateUrl: 'wizard.component.html'
})
export class ContainerComponent {
  destroy$: Subject<boolean> = new Subject<boolean>();

  constructor(private wizardModalService: WizardModalService) {}

  showOneEntryExample() {
    const wizardConfig: WizardConfig = {
      headerText: 'One Menu Entry Example',
      headerIcon: 'rocket',
      bodyHeaderIcon: 'rocket'
    };

    const initialState: any = {
      id: 'singleEntry',
      wizardConfig
    };
    const modalOptions = { initialState };

    this.wizardModalService.show(modalOptions);
  }

  showMultipleEntriesExample() {
    const wizardConfig: WizardConfig = {
      headerText: 'Multiple Entries example',
      headerIcon: 'rocket',
      bodyHeaderText: 'Select methods',
      bodyHeaderIcon: 'rocket'
    };

    const initialState: any = {
      id: 'multipleEntries',
      wizardConfig
    };

    const modalOptions = { initialState };

    const modalRef = this.wizardModalService.show(modalOptions);

    modalRef.content.onSelect.pipe(takeUntil(this.destroy$)).subscribe(menuEntry => {
      // handle menu entry
      console.log(menuEntry);
    });

    modalRef.content.onClose.pipe(takeUntil(this.destroy$)).subscribe(result => {
      // handle result
      console.log(result);
    });

    modalRef.content.onReset.pipe(takeUntil(this.destroy$)).subscribe(result => {
      // handle result
      console.log(result);
    });
  }

  showStepperExample() {
    const wizardConfig: WizardConfig = {
      headerText: 'Stepper Example',
      headerIcon: 'c8y-icon-modules',
      bodyHeaderIcon: 'c8y-icon-modules'
    };

    const initialState: any = {
      id: 'stepperExample',
      wizardConfig
    };
    const modalOptions = { initialState };

    this.wizardModalService.show(modalOptions);
  }

  ngOnDestroy() {
    this.destroy$.next(true);
    this.destroy$.unsubscribe();
  }
}
