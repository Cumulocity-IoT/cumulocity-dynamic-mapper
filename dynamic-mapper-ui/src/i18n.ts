/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

/**
 * Internationalizing files in po format (https://en.wikipedia.org/wiki/Gettext#Translating)
 * You can always add additional strings by adding your own po file. All po files are
 * combined to one JSON file per language and are loaded if the specific language is needed.
 */
import '@c8y/ngx-components/locales/de.po';
import '@c8y/ngx-components/locales/en.po';
import '@c8y/ngx-components/locales/en_US.po';
import '@c8y/ngx-components/locales/es.po';
import '@c8y/ngx-components/locales/fr.po';
import '@c8y/ngx-components/locales/ja_JP.po';
import '@c8y/ngx-components/locales/ko.po';
import '@c8y/ngx-components/locales/nl.po';
import '@c8y/ngx-components/locales/pl.po';
import '@c8y/ngx-components/locales/pt_BR.po';
import '@c8y/ngx-components/locales/zh_CN.po';
import '@c8y/ngx-components/locales/zh_TW.po';

// The app's own messages. Generate with scripts/sync-app-catalogs.js; an empty msgstr shows English.
import './locales/de.po';
