/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
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
import { Component, Input } from "@angular/core";
import { get, isUndefined } from "lodash-es";

@Component({
  selector: "d11r-mapping-help",
  templateUrl: "./help.component.html",
})
export class HelpComponent {
  /**
   * The source of the documentation. Used to link to the documentation as well as
   * to parse the source to display.
   */
  @Input()
  src: string = "";

  /**
   * Indicates if the help dialog is collapsed.
   */
  @Input()
  isCollapsed = true;

  /**
   * The priority where the help icon should be shown in the action bar
   */
  @Input()
  priority = Infinity;

  /**
   * An custom icon. If not set, the navigator icon is resolved
   */
  @Input()
  icon;

  /**
   * An title. Set in open by passing the source.
   */
  title = "";

  /**
   * Indicates if the component is loading.
   */
  isLoading = false;

  /**
   * Indicates if the component failed loading the source.
   */
  hasError = false;

  /**
   * @ignore
   */
  isInit = false;

  /**
   * @ignore Only private DI
   */
  constructor() {}

  /**
   * The component is shown by default and therefore breaks e2e test. This is
   * to prevent the visibility on first navigation.
   * @ignore
   */
  onCollapsed() {
    this.isInit = true;
  }

  /**
   * Builds the URL based on the src. The Base URL can be set in the application options docBaseUrl.
   * @param src The source of the help on the guide.
   * @param index This flag is used to call the index.json content of a guide. For example, "https://www.cumulocity.com/guides/users-guide/cockpit/index.json".
   */
  getUrl(src = "", index = false) {
    let docsUrl;
    //const srcUrl = new URL(`${docsUrl}${src}`);
    // const srcUrl = new URL(`${src}`);
    //const srcUrl = (new URL(`${src}`, document.location)).href

    const [url, hashFragment] = src.split("#");

    if (index) {
      src = `${url}index.json`;
    }
    // return `${src}`;
    return ".";
  }

  /**
   * Toggles the visibility of the help dialog.
   */
  toggle() {
    if (this.isCollapsed) {
      this.open();
      return;
    }
    this.close();
  }

  /**
   * Closes the help dialog.
   */
  close() {
    this.isCollapsed = true;
    this.clean();
  }

  /**
   * Opens the help dialog.
   */
  open() {
    this.isCollapsed = false;
    this.isLoading = true;
    this.requestContent();
    if (!this.icon) {
      this.icon = "life-saver";
    }
  }

  private requestContent() {
    const req = new XMLHttpRequest();
    req.onreadystatechange = () => this.render(req);
    req.addEventListener("load", () => this.render(req));
    req.open("GET", this.getUrl(this.src, true));
    req.responseType = "json";
    req.setRequestHeader("Accept", "text/html");
    req.send();
  }

  private clean() {
    this.title = "";
    this.hasError = false;
  }

  private render(req: XMLHttpRequest) {
    if (req.readyState === 4) {
      this.isLoading = false;
      if (req.status === 404) {
        this.open();
      }
      if (req.status === 200) {
        this.hasError = false;
      } else {
        this.hasError = true;
      }
    }
  }
}
