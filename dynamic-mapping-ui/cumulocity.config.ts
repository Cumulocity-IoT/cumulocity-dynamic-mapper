import { EnvironmentOptions } from '@c8y/devkit/dist/options';
import { author, description, version, license } from './package.json';

export default {
  runTime: {
    author,
    description,
    license,
    version,
    name: 'dynamic-mapping-ui',
    contextPath: 'sag-ps-pkg-dynamic-mapping',
    key: 'sag-ps-pkg-dynamic-mapping-key',
    contentSecurityPolicy:
      "base-uri 'none'; default-src 'self' 'unsafe-inline' http: https: ws: wss:; connect-src 'self' http: https: ws: wss:;  script-src 'self' *.bugherd.com *.twitter.com *.twimg.com *.aptrinsic.com 'unsafe-inline' 'unsafe-eval' data:; style-src * 'unsafe-inline' blob:; img-src * data: blob:; font-src * data:; frame-src *; worker-src 'self' blob:;",
    dynamicOptionsUrl: '/apps/public/public-options/options.json',
    remotes: {
      'sag-ps-pkg-dynamic-mapping': ['DynamicMappingModule']
    },
    package: 'plugin',
    tabsHorizontal: true,
    isPackage: true,
    noAppSwitcher: true,
    exports: [
      {
        name: 'Dynamic Mapping Widget',
        module: 'DynamicMappingModule',
        path: './src/dynamic-mapping.module',
        description: 'Adds a Dynamic Mapping Widget'
      }
    ]
  },
  buildTime: {
    extraWebpackConfig: './extra-webpack.config.js',
    copy: [
      {
        from: 'README.md',
        to: 'README.md'
      },
      {
        from: '../resources/image/Generic_Mapping_AddMapping.png',
        to: 'images/Generic_Mapping_AddMapping.png'
      },
      {
        from: '../resources/image/Generic_Mapping_MappingTemplate.png',
        to: 'images/Generic_Mapping_MappingTemplate.png'
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
      '@ngx-translate/core'
    ]
  }
} as const satisfies EnvironmentOptions;
