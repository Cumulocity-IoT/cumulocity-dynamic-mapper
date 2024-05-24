import { EnvironmentOptions } from '@c8y/devkit/dist/options';
import { author, description, version, license } from './package.json';

const asset = process.env['npm_config_asset'];
const isApp = asset == 'app' ? true : false;

console.log('Building asset:', asset, asset == 'app', isApp);

export default {
  runTime: {
    author,
    description,
    license,
    version,
    name: 'dynamic-mapping',
    contextPath: 'sag-ps-pkg-dynamic-mapping',
    icon: {
      url: 'url(./image/DM_App-Icon_01.svg)',
      class: 'custom-app-icon'
    },
    key: 'sag-ps-pkg-dynamic-mapping-key',
    contentSecurityPolicy:
      "base-uri 'none'; default-src 'self' 'unsafe-inline' http: https: ws: wss:; connect-src 'self' http: https: ws: wss:;  script-src 'self' *.bugherd.com *.twitter.com *.twimg.com *.aptrinsic.com 'unsafe-inline' 'unsafe-eval' data:; style-src * 'unsafe-inline' blob:; img-src * data: blob:; font-src * data:; frame-src *; worker-src 'self' blob:;",
    dynamicOptionsUrl: '/apps/public/public-options/options.json',
    remotes: {
      'sag-ps-pkg-dynamic-mapping': ['DynamicMappingModule']
    },
    tabsHorizontal: true,
    noAppSwitcher: false,
    // comment the following properties to create a standalone app
    // comment begin
    package: 'plugin',
    isPackage: !isApp,
    exports:
      isApp
        ? []
        : [
            {
              name: 'Dynamic Mapping Widget',
              module: 'DynamicMappingModule',
              path: './src/dynamic-mapping.module',
              description: 'Adds a Dynamic Mapping Plugin'
            }
          ]
    // isPackage: true,
    // exports: [
    //   {
    //     name: 'Dynamic Mapping Widget',
    //     module: 'DynamicMappingModule',
    //     path: './src/dynamic-mapping.module',
    //     description: 'Adds a Dynamic Mapping Plugin'
    //   }
    // ]
    // comment end
  },
  buildTime: {
    // extraWebpackConfig: './extra-webpack.config.js',
    entryModule: './app.module.ts',
    copy: [
      {
        from: 'README.md',
        to: 'README.md'
      },
      {
        from: '../resources/image/Generic_Mapping_AddMapping.png',
        to: 'image/Generic_Mapping_AddMapping.png'
      },
      {
        from: '../resources/image/Generic_Mapping_MappingTemplate.png',
        to: 'image/Generic_Mapping_MappingTemplate.png'
      },
      {
        from: '../resources/image/DM_App-Icon_01.svg',
        to: 'image/DM_App-Icon_01.svg'
      }
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
      '@ngx-translate/core',
      '@ngx-formly/core'
    ]
  }
} as const satisfies EnvironmentOptions;
