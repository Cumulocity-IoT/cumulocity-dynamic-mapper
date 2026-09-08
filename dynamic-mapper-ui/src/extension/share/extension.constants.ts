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
import { gettext } from '@c8y/ngx-components/gettext';
import { ERROR_TYPE } from './extension.model';
import { PropertiesListItem } from '@c8y/ngx-components';

export const ERROR_MESSAGES = {
  [ERROR_TYPE.UPLOAD_FAILED]: gettext(
    'Could not upload the *.jar file. Please check your connection and try again.'
  )
};

export const APP_STATE = {
  SUBSCRIBED: {
    label: gettext('Subscribed`application`'),
    class: 'label-primary'
  },
  CUSTOM: {
    label: gettext('Custom`application`'),
    class: 'label-info'
  },
  EXTERNAL: {
    label: gettext('External`application`'),
    class: 'label-warning'
  },
  UNPACKED: {
    label: gettext('Unpacked`application`'),
    class: 'label-success'
  },
  PACKAGE_APP: {
    label: gettext('Application'),
    class: 'label-success'
  },
  PACKAGE_PLUGIN: {
    label: gettext('Plugins'),
    class: 'label-info'
  }
};

export const packageProperties: PropertiesListItem[] = [
  {
    label: gettext('Version'),
    key: 'version'
  },
  {
    label: gettext('Author'),
    key: 'author'
  },
  {
    label: gettext('Keywords'),
    key: 'keywords'
  },
  {
    label: gettext('Source'),
    key: 'repository',
    transform: (repository: any) =>
      repository?.url ? repository.url : repository,
    type: 'link',
    action: (e, link) =>
      window.open(link as string, '_blank', 'noopener,noreferrer')
  },
  {
    label: gettext('Homepage'),
    key: 'homepage',
    type: 'link',
    action: (e, link) =>
      window.open(link as string, '_blank', 'noopener,noreferrer')
  },
  {
    label: gettext('Required platform version'),
    key: 'requiredPlatformVersion'
  },
  {
    label: gettext('License'),
    key: 'license'
  }
];
