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

import './i18n';

/**
 * Local `ng serve` proxies this app through localhost while "Dynamic Mapper" is also a real,
 * already-deployed Application on the tenant. Since the Web SDK 1024 update, ngx-components'
 * UiStateService attaches an 'X-Cumulocity-Application-Key' header to every request even in dev
 * mode, once its manifest lookup resolves that real Application (previously this header was never
 * sent in dev mode at all). The backend then rejects every request carrying it from an origin
 * (localhost) that isn't valid for the real deployed app, producing a wall of 401s (currentUser,
 * tenant/options, ...) despite a perfectly valid session — confirmed by the same session working
 * normally against the real tenant directly.
 *
 * FetchClient.fetch() (@c8y/client) ultimately always calls the global `window.fetch`, regardless
 * of which FetchClient instance sets the header or when — so stripping it here, once, as early as
 * possible (before any bootstrap network call fires), is the one place guaranteed to catch every
 * request. This must run before `applicationSetup()` below.
 */
if (typeof window !== 'undefined' && window.location.hostname === 'localhost') {
  const HEADER = 'x-cumulocity-application-key';
  const originalFetch = window.fetch.bind(window);
  window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
    if (init?.headers) {
      if (init.headers instanceof Headers) {
        init.headers.delete(HEADER);
      } else if (Array.isArray(init.headers)) {
        init.headers = init.headers.filter(([key]) => key.toLowerCase() !== HEADER);
      } else {
        const headers = { ...init.headers } as Record<string, string>;
        for (const key of Object.keys(headers)) {
          if (key.toLowerCase() === HEADER) delete headers[key];
        }
        init.headers = headers;
      }
    }
    return originalFetch(input, init);
  }) as typeof window.fetch;
}

const barHolder: HTMLElement | null = document.querySelector('body > .init-load');
export const removeProgress = () => barHolder?.parentNode?.removeChild(barHolder);

applicationSetup();

async function applicationSetup() {
  const { loadMetaDataAndPerformBootstrap } = await import('@c8y/bootstrap');
  const loadBootstrapModule = () =>
    import(
      /* webpackPreload: true */
      './bootstrap'
    );

  loadMetaDataAndPerformBootstrap(loadBootstrapModule).then(removeProgress);
}