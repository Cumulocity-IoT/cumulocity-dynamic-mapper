/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { FormlyFieldConfig } from '@ngx-formly/core';
import { TranslateService } from '@ngx-translate/core';
import { translateFormlyProps } from './translate-props.extension';

describe('translateFormlyProps', () => {
  const translations: Record<string, string> = {
    'Use external id': 'Externe ID verwenden',
    'Enter a name': 'Namen eingeben',
    'Shown below the field': 'Unter dem Feld angezeigt',
    'More about this field': 'Mehr zu diesem Feld'
  };
  const translate = { instant: (key: string) => translations[key] ?? key } as unknown as TranslateService;
  const onPopulate = (field: FormlyFieldConfig) =>
    (translateFormlyProps(translate).extensions![0].extension as any).onPopulate(field);

  it('translates label, placeholder, description and tooltip', () => {
    const field: FormlyFieldConfig = {
      props: {
        label: 'Use external id',
        placeholder: 'Enter a name',
        description: 'Shown below the field',
        tooltip: 'More about this field'
      }
    };

    onPopulate(field);

    expect(field.props).toEqual({
      label: 'Externe ID verwenden',
      placeholder: 'Namen eingeben',
      description: 'Unter dem Feld angezeigt',
      tooltip: 'Mehr zu diesem Feld'
    });
  });

  it('leaves a text without a translation, and other properties, as they are', () => {
    const field: FormlyFieldConfig = { props: { label: 'pollIntervalSeconds', required: true, rows: 4 } };

    onPopulate(field);

    expect(field.props).toEqual({ label: 'pollIntervalSeconds', required: true, rows: 4 });
  });

  it('ignores fields without props and values that are not text', () => {
    expect(() => onPopulate({})).not.toThrow();

    const field: FormlyFieldConfig = { props: { label: undefined, description: '' } };
    onPopulate(field);
    expect(field.props).toEqual({ label: undefined, description: '' });
  });
});
