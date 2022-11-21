import { Injectable } from '@angular/core';
import { CanActivate } from '@angular/router';
import { BrokerConfigurationService } from '../mqtt-configuration/broker-configuration.service';

@Injectable({ providedIn: 'root' })
export class ExtensionGuard implements CanActivate {
  private activateExtensionNavigationPromise: Promise<boolean>;

  constructor(private configurationService: BrokerConfigurationService) { }

  canActivate(): Promise<boolean> {
    if (!this.activateExtensionNavigationPromise) {
      this.activateExtensionNavigationPromise = 
      this.configurationService.getServiceConfiguration().then (conf => {
        console.log("External Extension :", conf.externalExtensionEnabled);
        return conf.externalExtensionEnabled
      }
      )
    }
    return this.activateExtensionNavigationPromise;
  }
}
