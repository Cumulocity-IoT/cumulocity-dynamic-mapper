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
import dotenv from 'dotenv';
import { defineConfig } from 'cypress';

dotenv.config({ path: '.env' });

export default defineConfig({
  viewportWidth: 1920,
  viewportHeight: 1080,
  responseTimeout: 60000,
  pageLoadTimeout: 300000,
  video: true,
  videoCompression: 0,
  e2e: {
    baseUrl: process.env['C8Y_CYPRESS_URL'] || 'http://localhost:4200',
    specPattern: 'cypress/e2e/**/*.cy.ts',
    supportFile: 'cypress/support/e2e.ts',
    env: {
      // https://github.com/bahmutov/cypress-slow-down
      commandDelay: 150,
      // Cumulocity credentials — support CYPRESS_* prefix overrides
      C8Y_TENANT: process.env['CYPRESS_C8Y_TENANT'] || process.env['C8Y_TENANT'],
      C8Y_BASEURL: process.env['CYPRESS_C8Y_BASEURL'] || process.env['C8Y_BASEURL'],
      C8Y_USERNAME: process.env['CYPRESS_C8Y_USERNAME'] || process.env['C8Y_USERNAME'],
      C8Y_PASSWORD: process.env['CYPRESS_C8Y_PASSWORD'] || process.env['C8Y_PASSWORD'],
      // Shell configuration for visitShellAndWaitForSelector
      C8Y_SHELL_TARGET:
        process.env['CYPRESS_C8Y_SHELL_TARGET'] ||
        process.env['C8Y_SHELL_TARGET'] ||
        'administration',
      C8Y_SHELL_EXTENSION: JSON.stringify({
        'c8y-pkg-dynamic-mapper': ['DynamicMappingModule'],
      }),
    },
  },
});
