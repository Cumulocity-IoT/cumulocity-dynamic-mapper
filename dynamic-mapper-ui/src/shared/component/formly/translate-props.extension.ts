/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { ConfigOption, FormlyFieldConfig } from '@ngx-formly/core';
import { TranslateService } from '@ngx-translate/core';

/** The texts of a form field that the user reads. Each one is a message ID in the catalogs. */
export const TRANSLATED_FIELD_PROPS = ['label', 'placeholder', 'description', 'tooltip'] as const;

/**
 * Translates the texts of every form field when the field is built, so a configuration only has
 * to mark them with `gettext('...')`. The field wrappers and types then show the translation.
 *
 * Texts that a field computes later through `expressions` are not covered. Those are built in
 * code, where they are translated with the `TranslateService`.
 */
export function translateFormlyProps(translate: TranslateService): ConfigOption {
  return {
    extensions: [
      {
        name: 'd11r-translate-props',
        extension: {
          onPopulate(field: FormlyFieldConfig): void {
            const props = field.props;
            if (!props) {
              return;
            }
            for (const name of TRANSLATED_FIELD_PROPS) {
              const value = props[name];
              if (typeof value === 'string' && value) {
                props[name] = translate.instant(value);
              }
            }
          }
        }
      }
    ]
  };
}
