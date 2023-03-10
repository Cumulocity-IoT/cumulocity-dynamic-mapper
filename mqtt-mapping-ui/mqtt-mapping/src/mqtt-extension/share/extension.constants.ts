/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
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
import { ERROR_TYPE } from './extension.model';
import { gettext, PropertiesListItem } from '@c8y/ngx-components';

export const ERROR_MESSAGES = {
  [ERROR_TYPE.TYPE_VALIDATION]: gettext(
    'Wrong file format. Expected a *.zip file with a valid manifest.'
  ),
  [ERROR_TYPE.ALREADY_SUBSCRIBED]: gettext(
    'Could not subscribe to the microservice because another application with the same context path is already subscribed.'
  ),
  [ERROR_TYPE.NO_MANIFEST_FILE]: gettext('Could not find a manifest.'),
  [ERROR_TYPE.INVALID_PACKAGE]: gettext('You have not uploaded a valid package.'),
  [ERROR_TYPE.INVALID_APPLICATION]: gettext('You have not uploaded a valid application.'),
  [ERROR_TYPE.INTERNAL_ERROR]: gettext('An internal error occurred, try to upload again.')
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
    transform: (repository: any) => (repository?.url ? repository.url : repository),
    type: 'link',
    action: (e, link) => window.open(link as string, '_blank', 'noopener,noreferrer')
  },
  {
    label: gettext('Homepage'),
    key: 'homepage',
    type: 'link',
    action: (e, link) => window.open(link as string, '_blank', 'noopener,noreferrer')
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
