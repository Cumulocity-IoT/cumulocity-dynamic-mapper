import './polyfills';
import '@angular/compiler';

import { enableProdMode } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { BootstrapMetaData } from '@c8y/bootstrap';
import { BootstrapComponent, provideBootstrapMetadata } from '@c8y/ngx-components';
import { appConfig } from './app.config';

declare const __MODE__: string;
if (__MODE__ === 'production') {
  enableProdMode();
}

export function bootstrap(metadata: BootstrapMetaData) {
  appConfig.providers.push(...provideBootstrapMetadata(metadata));
  return bootstrapApplication(BootstrapComponent, appConfig).catch(err => console.log(err));
}