import type { ConfigurationOptions } from '@c8y/devkit';
import { author, description, version, license } from './package.json';

export default {
  runTime: {
    author,
    description,
    license,
    version,
    name: 'Dynamic Mapper',
    contextPath: 'c8y-pkg-dynamic-mapper',
    icon: {
      class: 'c8y-icon-dynamic-mapper'
    },
    key: 'c8y-pkg-dynamic-mapper-key',
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
        from: '../resources/image/Dynamic_Mapper_Mapping_Stepper_Topic_Definition.png',
        to: 'image/Dynamic_Mapper_Mapping_Stepper_Topic_Definition.png'
      },
      {
        from: '../resources/image/Dynamic_Mapper_Mapping_Stepper_Substitution_JavaScript.png',
        to: 'image/Dynamic_Mapper_Mapping_Stepper_Substitution_JavaScript.png'
      },
      {
        from: '../resources/image/Dynamic_Mapper_Mapping_Stepper_SmartFunction.png',
        to: 'image/Dynamic_Mapper_Mapping_Stepper_SmartFunction.png'
      },
      {
        from: '../resources/image/Dynamic_Mapper_Mapping_Stepper_Substitution_ExpertMode.png',
        to: 'image/Dynamic_Mapper_Mapping_Stepper_Substitution_ExpertMode.png'
      },
      {
        from: '../resources/image/Dynamic_Mapper_Mapping_Stepper_Snooping_Started.png',
        to: 'image/Dynamic_Mapper_Mapping_Stepper_Snooping_Started.png'
      },
      {
        from: '../resources/image/Dynamic_Mapper_Mapping_Table_Add_Modal_TransformationType.png',
        to: 'image/Dynamic_Mapper_Mapping_Table_Add_Modal_TransformationType.png'
      },

      {
        from: '../resources/image/Dynamic_Mapper_Mapping_Stepper_Substitution_Generate_JSONata.png',
        to: 'image/Dynamic_Mapper_Mapping_Stepper_Substitution_Generate_JSONata.png'
      },
      {
        from: '../resources/image/Dynamic_Mapper_Mapping_Table_Add_Modal_Payload.png',
        to: 'image/Dynamic_Mapper_Mapping_Table_Add_Modal_Payload.png'
      },

      {
        from: '../resources/image/Dynamic_Mapper_Mapping_Substitution_Outbound.png',
        to: 'image/Dynamic_Mapper_Mapping_Substitution_Outbound.png'
      },
      {
        from: '../resources/image/Dynamic_Mapper_Mapping_Stepper_Mapping_Metadata_Inbound.png',
        to: 'image/Dynamic_Mapper_Mapping_Stepper_Mapping_Metadata_Inbound.png'
      },
      {
        from: '../resources/image/Dynamic_Mapper_Mapping_Stepper_Mapping_Metadata_Outbound.png',
        to: 'image/Dynamic_Mapper_Mapping_Stepper_Mapping_Metadata_Outbound.png'
      },
      {
        from: '../resources/image/Dynamic_Mapper_Mapping_Stepper_Substitution_Change_Metadata.png',
        to: 'image/Dynamic_Mapper_Mapping_Stepper_Substitution_Change_Metadata.png'
      },
      {
        from: '../resources/image/Dynamic_Mapper_Connector_New.png',
        to: 'image/Dynamic_Mapper_Connector_New.png'
      },
      {
        from: '../LICENSE',
        to: 'LICENSE.txt'
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
      '@c8y/client',
      '@c8y/ngx-components',
      'ngx-bootstrap',
      '@ngx-translate/core',
      '@ngx-formly/core'
    ]
  }
} as const satisfies ConfigurationOptions;
