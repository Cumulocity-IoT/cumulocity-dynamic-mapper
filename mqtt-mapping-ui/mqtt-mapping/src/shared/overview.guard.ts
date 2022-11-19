import { Injectable } from '@angular/core';
import { CanActivate } from '@angular/router';
import { ApplicationService } from '@c8y/client';

@Injectable({ providedIn: 'root' })
export class OverviewGuard implements CanActivate {
  private static readonly APPLICATION_MQTT = 'mqtt-mapping-service';

  private activateOverviewNavigationPromise: Promise<boolean>;

  constructor(private applicationService: ApplicationService) {}

  canActivate(): Promise<boolean> {
    if (!this.activateOverviewNavigationPromise) {
      this.activateOverviewNavigationPromise = this.applicationService
        .isAvailable(OverviewGuard.APPLICATION_MQTT)
        .then((result) => {
          if (!(result && result.data)) {
            console.error('Generic MQTT Agent Microservice not subscribed!');
          }

          return result && result.data;
        });
    }

    return this.activateOverviewNavigationPromise;
  }
}
