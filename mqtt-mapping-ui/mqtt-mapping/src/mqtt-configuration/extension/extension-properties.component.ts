import { Component, OnInit } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import {
  IManagedObject,
} from '@c8y/client';
import {
  gettext,
} from '@c8y/ngx-components';

import { ExtensionService } from './extension.service';

@Component({
  selector: 'c8y-extension-properties',
  templateUrl: './extension-properties.component.html'
})
export class ExtensionPropertiesComponent implements OnInit {
  extensionsEntryForm: FormGroup;
  extension: IManagedObject;

  isLoading: boolean = true;

  breadcrumbConfig: { icon: string; label: string; path: string };

  constructor(
    private activatedRoute: ActivatedRoute,
    private formBuilder: FormBuilder,
    private extensionService: ExtensionService,
  ) { }

  async ngOnInit() {
    await this.refresh();
  }

  async refresh() {
    await this.load();
    this.setBreadcrumbConfig();
  }

  async load() {
    this.isLoading = true;
    this.initForm();
    await this.loadExtension();
    this.isLoading = false;
  }

  async loadExtension() {
    const { id } = this.activatedRoute.snapshot.params;
    let filter = { id: id }
    let result = await this.extensionService.getExtensionsEnriched(filter);
    let ext = result[0];
    this.extension = result[0];
    this.extensionsEntryForm.patchValue({ ...this.extension.extensionEntries });

    this.extension.extensionEntries.forEach(entry => {
      const extensionEntriesForm = this.formBuilder.group({
        name: [entry.name, Validators.required],
        event: [entry.event, Validators.required]
      });
      this.extensionEntries.push(extensionEntriesForm);
    })

  }

  private initForm(): void {
    this.extensionsEntryForm = this.formBuilder.group({
      extensionEntries: this.formBuilder.array([])
    });

  }

  get extensionEntries() {
    return this.extensionsEntryForm.controls["extensionEntries"] as FormArray;
  }

  private setBreadcrumbConfig() {
    this.breadcrumbConfig = {
      icon: 'c8y-modules',
      label: gettext('Extensions'),
      path: 'mqtt-mapping/extensions'
    };
  }

}
