import { NgModule } from '@angular/core';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import { RouterModule, Routes } from '@angular/router';
import {
  CoreModule,
  HOOK_NAVIGATOR_NODES,
  HOOK_TABS,
  HOOK_WIZARD,
  NavigatorNode
} from '@c8y/ngx-components';
import { BsModalRef } from 'ngx-bootstrap/modal';
import { MinimalSetupComponent } from './minimal-setup/minimal-setup.component';
import { MultipleEntriesOne } from './minimal-setup/multiple-entries-one.component';
import { MultipleEntriesTwo } from './minimal-setup/multiple-entries-two.component';
import { StepperExampleComponent } from './minimal-setup/stepper-example.component';
import { WizardTabs } from './wizard-tabs';
import { ContainerComponent } from './wizard.component';

const wizardNode = new NavigatorNode({
  label: 'Wizard',
  icon: 'body',
  priority: 0,
  path: 'mqtt-mapping/wizard'
});

const routes: Routes = [
  {
    path: 'mqtt-mapping/wizard',
    component: ContainerComponent
  }
];

export const tabs = [
  {
    provide: HOOK_TABS,
    useClass: WizardTabs,
    multi: true
  }
];

@NgModule({
  declarations: [
    ContainerComponent,
    MinimalSetupComponent,
    MultipleEntriesOne,
    MultipleEntriesTwo,
    StepperExampleComponent
  ],
  imports: [RouterModule.forChild(routes), CoreModule, FormsModule, ReactiveFormsModule],
  entryComponents: [
    ContainerComponent,
    MinimalSetupComponent,
    MultipleEntriesOne,
    MultipleEntriesTwo,
    StepperExampleComponent
  ],
  providers: [
    BsModalRef,
    ...tabs,
    { provide: HOOK_NAVIGATOR_NODES, useValue: { get: () => wizardNode }, multi: true },
    {
      provide: HOOK_WIZARD,
      useValue: {
        wizardId: 'singleEntry',
        // The container component is responsible for handling subsequent steps in the wizard.
        component: MinimalSetupComponent,
        // Menu entry name
        name: "Doesn't matter as it won't be shown anyway since it is a single entry.",
        // Menu entry icon
        c8yIcon: 'upload'
      },
      multi: true
    },
    {
      provide: HOOK_WIZARD,
      useValue: {
        // entry ID, observe that it shares the same ID as the entry below.
        wizardId: 'multipleEntries',
        // The container component is responsible for handling subsequent steps in the wizard.
        component: MultipleEntriesOne,
        // Menu entry name
        name: 'Entry 1',
        // Menu entry icon
        c8yIcon: 'upload'
      },
      multi: true
    },
    {
      provide: HOOK_WIZARD,
      useValue: {
        wizardId: 'multipleEntries',
        // The container component is responsible for handling subsequent steps in the wizard.
        component: MultipleEntriesTwo,
        // Menu entry name
        name: 'Entry 2',
        // Menu entry icon
        c8yIcon: 'upload'
      },
      multi: true
    },
    {
      provide: HOOK_WIZARD,
      useValue: {
        wizardId: 'stepperExample',
        // The container component is responsible for handling subsequent steps in the wizard.
        component: StepperExampleComponent,
        // Menu entry name
        name: "Doesn't matter as it won't be shown anyway since it is a single entry.",
        // Menu entry icon
        c8yIcon: 'upload'
      },
      multi: true
    }
  ]
})
export class WizardModule {}
