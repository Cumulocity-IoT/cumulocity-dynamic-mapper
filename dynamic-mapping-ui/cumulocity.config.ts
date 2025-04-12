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
import { ConfigurationOptions } from '@c8y/devkit';
import { author, description, version, license } from './package.json';

// const asset = process.env['npm_config_asset'];
// const isApp = asset == 'app' ? true : false;
// console.log('Building asset:', asset, asset == 'app', isApp);

export default {
  runTime: {
    author,
    description,
    license,
    version,
    name: 'dynamic-mapping',
    contextPath: 'sag-ps-pkg-dynamic-mapping',
    icon: {
      class: 'c8y-icon-dynamic-mapper'
    },
    key: 'sag-ps-pkg-dynamic-mapping-key',
    contentSecurityPolicy:
      "base-uri 'none'; default-src 'self' 'unsafe-inline' http: https: ws: wss:; connect-src 'self' http: https: ws: wss:;  script-src 'self' *.bugherd.com *.twitter.com *.twimg.com *.aptrinsic.com 'unsafe-inline' 'unsafe-eval' data:; style-src * 'unsafe-inline' blob:; img-src * data: blob:; font-src * data:; frame-src *; worker-src 'self' blob:;",
    dynamicOptionsUrl: '/apps/public/public-options/options.json',
    tabsHorizontal: true,
    noAppSwitcher: false,
    package: 'blueprint',
    contextHelp: true,
    isPackage: true,
  },
  buildTime: {
    brandingEntry: './src/styles.less',
    copy: [
      {
        from: 'README.md',
        to: 'README.md'
      },
      {
        from: '../resources/image/Dynamic_Mapper_Mapping_Table_Add_Modal.png',
        to: 'image/Dynamic_Mapper_Mapping_Table_Add_Modal.png'
      },
      {
        from: '../resources/image/Dynamic_Mapper_Mapping_Stepper_Substitution_Basic.png',
        to: 'image/Dynamic_Mapper_Mapping_Stepper_Substitution_Basic.png'
      },
      {
        from: '../resources/image/Dynamic_Mapper_Mapping_Stepper_Substitution_Basic_Annotated.png',
        to: 'image/Dynamic_Mapper_Mapping_Stepper_Substitution_Basic_Annotated.png'
      },
      {
        from: '../resources/image/Dynamic_Mapper_Mapping_Table_Add_Modal_Snooping.png',
        to: 'image/Dynamic_Mapper_Mapping_Table_Add_Modal_Snooping.png'
      },
      {
        from: '../resources/image/Dynamic_Mapper_Mapping_Stepper_Mapping_Animated.gif',
        to: 'image/Dynamic_Mapper_Mapping_Stepper_Mapping_Animated.gif'
      },
      {
        from: '../resources/image/Dynamic_Mapper_Mapping_Stepper_Substitution_JavaScript.png',
        to: 'image/Dynamic_Mapper_Mapping_Stepper_Substitution_JavaScript.png'
      },
      {
        from: '../resources/image/Dynamic_Mapper_Mapping_Stepper_Mapping_Substitution_Animated.gif',
        to: 'image/Dynamic_Mapper_Mapping_Stepper_Mapping_Substitution_Animated.gif'
      },
      {
        from: '../resources/image/Dynamic_Mapper_Mapping_Stepper_Snooping_Animated.gif',
        to: 'image/Dynamic_Mapper_Mapping_Stepper_Snooping_Animated.gif'
      },
      {
        from: '../resources/image/Dynamic_Mapper_Mapping_Stepper_Process.png',
        to: 'image/Dynamic_Mapper_Mapping_Stepper_Process.png'
      },
      {
        from: '../resources/image/Dynamic_Mapper_Snooping_Stepper_Process.png',
        to: 'image/Dynamic_Mapper_Snooping_Stepper_Process.png'
      },
      {
        from: '../resources/image/Dynamic_Mapper_Snooping_Stepper_Process.svg',
        to: 'image/Dynamic_Mapper_Snooping_Stepper_Process.svg'
      },
      {
        from: '../resources/image/Dynamic_Mapper_Connector_Matrix.png',
        to: 'image/Dynamic_Mapper_Connector_Matrix.png'
      },
      {
        from: '../LICENSE',
        to: 'LICENSE.txt'
      },
      {
        from: './src/help',
        to: 'help'
      },
    ],
    federation: [
      '@angular/animations',
      '@angular/cdk',
      '@angular/common',
      '@angular/compiler',
      '@angular/core',
      '@angular/forms',
      '@angular/platform-browser',
      '@angular/platform-browser-dynamic',
      '@angular/router',
      '@angular/upgrade',
      '@c8y/client',
      '@c8y/ngx-components',
      'ngx-bootstrap',
      'angular',
      '@ngx-translate/core',
      '@ngx-formly/core'
    ]
  }
} as const satisfies ConfigurationOptions;
