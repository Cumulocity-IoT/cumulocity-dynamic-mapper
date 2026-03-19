/*
 * Copyright (c) 2025 Cumulocity GmbH
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
import { Injectable, inject } from '@angular/core';
import { AlertService, DataGridComponent } from '@c8y/ngx-components';
import { saveAs } from 'file-saver';
import { BehaviorSubject, finalize, take, takeUntil, Subject } from 'rxjs';
import { Direction, Mapping, MappingEnriched } from '../../shared';
import { MappingService } from './mapping.service';
import { ConfirmationModalService } from '../../shared/service/confirmation-modal.service';

/**
 * Service responsible for handling bulk operations on mappings.
 * Extracted from MappingComponent to improve separation of concerns and testability.
 */
@Injectable({
  providedIn: 'root'
})
export class MappingBulkOperationsService {
  private readonly mappingService = inject(MappingService);
  private readonly alertService = inject(AlertService);
  private readonly confirmationService = inject(ConfirmationModalService);

  /**
   * Activates multiple mappings in bulk
   * @param ids Array of mapping IDs to activate
   * @param mappingsEnriched$ Observable of enriched mappings
   * @param direction The mapping direction (inbound/outbound)
   * @param mappingGrid Reference to the data grid component
   * @param destroy$ Subject for cleanup
   * @param setLoading Callback to set loading state
   */
  activateBulk(
    ids: string[],
    mappingsEnriched$: BehaviorSubject<MappingEnriched[]>,
    direction: Direction,
    mappingGrid: DataGridComponent,
    destroy$: Subject<void>,
    setLoading: (loading: boolean) => void
  ): void {
    setLoading(true);
    mappingsEnriched$
      .pipe(
        take(1),
        takeUntil(destroy$),
        finalize(() => {
          setLoading(false);
        })
      )
      .subscribe(async (ms) => {
        try {
          const mappings2Activate = ms
            .filter((m) => ids.includes(m.id))
            .map((me) => me.mapping);

          let successCount = 0;
          const errors: string[] = [];
          for (const mapping of mappings2Activate) {
            try {
              const parameter = { id: mapping.id, active: true };
              await this.mappingService.changeActivationMapping(parameter);
              successCount++;
            } catch (error) {
              errors.push(mapping.name);
            }
          }

          if (errors.length === 0) {
            this.alertService.success(`Activated ${successCount} mapping(s) successfully.`);
          } else {
            this.alertService.warning(`Activated ${successCount} mapping(s). Failed for: ${errors.join(', ')}.`);
          }
          this.mappingService.refreshMappings(direction);
        } catch (error) {
          this.alertService.danger('Failed to activate mappings', error);
        }
      });

    mappingGrid.setAllItemsSelected(false);
  }

  /**
   * Deactivates multiple mappings in bulk
   * @param ids Array of mapping IDs to deactivate
   * @param mappingsEnriched$ Observable of enriched mappings
   * @param direction The mapping direction (inbound/outbound)
   * @param mappingGrid Reference to the data grid component
   * @param destroy$ Subject for cleanup
   * @param setLoading Callback to set loading state
   */
  deactivateBulk(
    ids: string[],
    mappingsEnriched$: BehaviorSubject<MappingEnriched[]>,
    direction: Direction,
    mappingGrid: DataGridComponent,
    destroy$: Subject<void>,
    setLoading: (loading: boolean) => void
  ): void {
    setLoading(true);
    mappingsEnriched$
      .pipe(
        take(1),
        takeUntil(destroy$),
        finalize(() => {
          setLoading(false);
        })
      )
      .subscribe(async (ms) => {
        try {
          const mappings2Deactivate = ms
            .filter((m) => ids.includes(m.id))
            .map((me) => me.mapping);

          let successCount = 0;
          const errors: string[] = [];
          for (const mapping of mappings2Deactivate) {
            try {
              const parameter = { id: mapping.id, active: false };
              await this.mappingService.changeActivationMapping(parameter);
              successCount++;
            } catch (error) {
              errors.push(mapping.name);
            }
          }

          if (errors.length === 0) {
            this.alertService.success(`Deactivated ${successCount} mapping(s) successfully.`);
          } else {
            this.alertService.warning(`Deactivated ${successCount} mapping(s). Failed for: ${errors.join(', ')}.`);
          }
          this.mappingService.refreshMappings(direction);
        } catch (error) {
          this.alertService.danger('Failed to deactivate mappings', error);
        }
      });

    mappingGrid.setAllItemsSelected(false);
  }

  /**
   * Enables or disables debug for multiple mappings in bulk
   */
  debugBulk(
    ids: string[],
    debug: boolean,
    mappingsEnriched$: BehaviorSubject<MappingEnriched[]>,
    direction: Direction,
    mappingGrid: DataGridComponent,
    destroy$: Subject<void>,
    setLoading: (loading: boolean) => void
  ): void {
    const action = debug ? 'Enabled' : 'Disabled';
    setLoading(true);
    mappingsEnriched$
      .pipe(
        take(1),
        takeUntil(destroy$),
        finalize(() => {
          setLoading(false);
        })
      )
      .subscribe(async (ms) => {
        try {
          const mappings2Update = ms
            .filter((m) => ids.includes(m.id))
            .map((me) => me.mapping);

          let successCount = 0;
          const errors: string[] = [];
          for (const mapping of mappings2Update) {
            try {
              const parameter = { id: mapping.id, debug };
              await this.mappingService.changeDebuggingMapping(parameter);
              successCount++;
            } catch (error) {
              errors.push(mapping.name);
            }
          }

          if (errors.length === 0) {
            this.alertService.success(`${action} debug for ${successCount} mapping(s) successfully.`);
          } else {
            this.alertService.warning(`${action} debug for ${successCount} mapping(s). Failed for: ${errors.join(', ')}.`);
          }
          this.mappingService.refreshMappings(direction);
        } catch (error) {
          this.alertService.danger(`Failed to ${action.toLowerCase()} debug for mappings`, error);
        }
      });

    mappingGrid.setAllItemsSelected(false);
  }

  /**
   * Exports multiple mappings in bulk
   * @param ids Array of mapping IDs to export
   * @param mappingsEnriched$ Observable of enriched mappings
   * @param direction The mapping direction (inbound/outbound)
   * @param mappingGrid Reference to the data grid component
   * @param destroy$ Subject for cleanup
   * @param setLoading Callback to set loading state
   */
  exportBulk(
    ids: string[],
    mappingsEnriched$: BehaviorSubject<MappingEnriched[]>,
    direction: Direction,
    mappingGrid: DataGridComponent,
    destroy$: Subject<void>,
    setLoading: (loading: boolean) => void
  ): void {
    setLoading(true);
    mappingsEnriched$
      .pipe(
        take(1),
        takeUntil(destroy$),
        finalize(() => {
          setLoading(false);
        })
      )
      .subscribe((ms) => {
        try {
          const mappings2Export = ms
            .filter((m) => ids.includes(m.id))
            .map((me) => me.mapping);

          this.exportMappings(mappings2Export, direction);
        } catch (error) {
          this.alertService.danger('Failed to export mappings', error);
        }
      });

    mappingGrid.setAllItemsSelected(false);
  }

  /**
   * Deletes multiple mappings in bulk with confirmation
   * @param ids Array of mapping IDs to delete
   * @param mappingsEnriched$ Observable of enriched mappings
   * @param direction The mapping direction (inbound/outbound)
   * @param mappingGrid Reference to the data grid component
   * @param destroy$ Subject for cleanup
   * @param setLoading Callback to set loading state
   * @param deleteCallback Callback to delete a single mapping
   */
  async deleteBulkWithConfirmation(
    ids: string[],
    mappingsEnriched$: BehaviorSubject<MappingEnriched[]>,
    direction: Direction,
    mappingGrid: DataGridComponent,
    destroy$: Subject<void>,
    setLoading: (loading: boolean) => void
  ): Promise<void> {
    const confirmed = await this.confirmationService.confirmDeletion('mapping', true);
    if (!confirmed) return;

    setLoading(true);
    mappingsEnriched$
      .pipe(
        take(1),
        takeUntil(destroy$),
        finalize(() => {
          setLoading(false);
        })
      )
      .subscribe(async (ms) => {
        try {
          const mappings2Delete = ms
            .filter((m) => ids.includes(m.id))
            .map((me) => me.mapping);

          let successCount = 0;
          const errors: string[] = [];
          for (const mapping of mappings2Delete) {
            try {
              await this.mappingService.deleteMapping(mapping.id);
              successCount++;
            } catch (error) {
              errors.push(mapping.name);
            }
          }

          if (errors.length === 0) {
            this.alertService.success(`Deleted ${successCount} mapping(s) successfully.`);
          } else {
            this.alertService.warning(`Deleted ${successCount} mapping(s). Failed for: ${errors.join(', ')}.`);
          }
        } catch (error) {
          this.alertService.danger('Failed to delete mappings', error);
        }
      });

    this.mappingService.refreshMappings(direction);
    mappingGrid.setAllItemsSelected(false);
  }

  /**
   * Exports an array of mappings to a JSON file
   * @param mappings Array of mappings to export
   * @param direction The mapping direction (for filename)
   */
  private exportMappings(mappings: Mapping[], direction: Direction): void {
    const json = JSON.stringify(mappings, undefined, 2);
    const blob = new Blob([json]);
    saveAs(blob, `mappings-${direction}.json`);
  }
}
