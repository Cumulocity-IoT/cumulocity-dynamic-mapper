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

import { inject, Injectable } from '@angular/core';
import { BsModalRef, BsModalService } from 'ngx-bootstrap/modal';
import { firstValueFrom } from 'rxjs';
import { ConfirmationModalComponent } from '../confirmation/confirmation-modal.component';

/**
 * Configuration options for a confirmation dialog
 */
export interface ConfirmationConfig {
  /** Dialog title */
  title: string;
  /** Dialog message/content */
  message: string;
  /** Optional label overrides for buttons */
  labels?: {
    ok?: string;
    cancel?: string;
  };
}

/**
 * Service for managing confirmation dialogs across the application.
 * Provides a consistent interface for showing confirmation modals and handling user responses.
 */
@Injectable({
  providedIn: 'root'
})
export class ConfirmationModalService {
  private readonly bsModalService = inject(BsModalService);

  /**
   * Show a confirmation modal and wait for user response
   * @param config Configuration for the confirmation dialog
   * @returns Promise resolving to true if confirmed, false if cancelled
   */
  async confirm(config: ConfirmationConfig): Promise<boolean> {
    const initialState = {
      title: config.title,
      message: config.message,
      labels: {
        ok: config.labels?.ok || 'OK',
        cancel: config.labels?.cancel || 'Cancel'
      }
    };

    const modalRef: BsModalRef = this.bsModalService.show(
      ConfirmationModalComponent,
      { initialState }
    );

    try {
      const result = await firstValueFrom(modalRef.content!.closeSubject);
      return (result as boolean) || false;
    } catch (error) {
      // Handle case where modal is dismissed without response
      return false;
    }
  }

  /**
   * Show a deletion confirmation dialog
   * @param itemName Name of the item being deleted
   * @param multiple Whether multiple items are being deleted
   * @returns Promise resolving to true if confirmed, false if cancelled
   */
  async confirmDeletion(itemName: string, multiple: boolean = false): Promise<boolean> {
    return this.confirm({
      title: multiple ? `Delete ${itemName}s` : `Delete ${itemName}`,
      message: multiple
        ? `You are about to delete multiple ${itemName}s. Do you want to proceed to delete ALL?`
        : `You are about to delete this ${itemName}. Do you want to proceed?`,
      labels: {
        ok: 'Delete',
        cancel: 'Cancel'
      }
    });
  }

  /**
   * Show a generic warning confirmation dialog
   * @param title Dialog title
   * @param message Warning message
   * @returns Promise resolving to true if confirmed, false if cancelled
   */
  async confirmWarning(title: string, message: string): Promise<boolean> {
    return this.confirm({
      title,
      message,
      labels: {
        ok: 'Proceed',
        cancel: 'Cancel'
      }
    });
  }

  /**
   * Show a save confirmation dialog for unsaved changes
   * @returns Promise resolving to true if user wants to save, false otherwise
   */
  async confirmUnsavedChanges(): Promise<boolean> {
    return this.confirm({
      title: 'Unsaved Changes',
      message: 'You have unsaved changes. Do you want to save before leaving?',
      labels: {
        ok: 'Save',
        cancel: 'Discard'
      }
    });
  }
}
