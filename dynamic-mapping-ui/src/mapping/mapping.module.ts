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
import { NgModule } from "@angular/core";
import {
  CoreModule,
  DynamicFormsModule,
  hookRoute,
  ModalModule,
} from "@c8y/ngx-components";
import { AssetSelectorModule } from "@c8y/ngx-components/assets-navigator";
import { FORMLY_CONFIG } from "@ngx-formly/core";
import { PopoverModule } from "ngx-bootstrap/popover";
import { BrokerConfigurationModule } from "../configuration/broker-configuration.module";
import { SharedModule } from "../shared/shared.module";
import {
} from "../shared";
import { EditSubstitutionComponent } from "./edit/edit-substitution-modal.component";
import { MappingComponent } from "./grid/mapping.component";
import { MappingTypeComponent } from "./mapping-type/mapping-type.component";
import { OverwriteSubstitutionModalComponent } from "./overwrite/overwrite-substitution-modal.component";
import { ActiveRendererComponent } from "./renderer/active.renderer.component";
import { APIRendererComponent } from "./renderer/api.renderer.component";
import { NameRendererComponent } from "./renderer/name.renderer.component";
import { QOSRendererComponent } from "./renderer/qos-cell.renderer.component";
import { SnoopedTemplateRendererComponent } from "./renderer/snoopedTemplate.renderer.component";
import { StatusActivationRendererComponent } from "./renderer/status-activation-renderer.component";
import { StatusRendererComponent } from "./renderer/status-cell.renderer.component";
import { TemplateRendererComponent } from "./renderer/template.renderer.component";
import { FormlyFieldButton } from "./shared/formly/button-type";
import { C8YSwitchField } from "./shared/formly/c8y-switch-field";
import { FieldCheckbox } from "./shared/formly/checkbox/checkbox.type.component";
import { FormlyFiller } from "./shared/formly/filler";
import { WrapperCustomFormField } from "./shared/formly/form-field/custom-form-field-wrapper";
import { FormlyHorizontalWrapper } from "./shared/formly/horizontal-wrapper";
import { FieldInputCustom } from "./shared/formly/input-custom-field";
import { MessageField } from "./shared/formly/message-field";
import { SelectComponent } from "./shared/formly/select/select.type.component";
import { FormlyTextField } from "./shared/formly/text-field";
import { FieldTextareaCustom } from "./shared/formly/textarea-custom";
import { SnoopingModalComponent } from "./snooping/snooping-modal.component";
import { MappingStepperComponent } from "./step-main/mapping-stepper.component";
import { SubstitutionRendererComponent } from "./step-main/substitution/substitution-renderer.component";
import { MappingStepPropertiesComponent } from "./step-one/mapping-properties.component";
import { MappingStepTestingComponent } from "./step-three/mapping-testing.component";
import { MappingSubscriptionComponent } from "./subscription/mapping-subscription.component";
import { ImportMappingsComponent } from "./import-modal/import.component";
import { checkTopicsInboundAreValid, checkTopicsOutboundAreValid } from "./shared/util";

@NgModule({
  declarations: [
    MappingComponent,
    MappingStepperComponent,
    MappingStepTestingComponent,
    MappingStepPropertiesComponent,
    MappingSubscriptionComponent,
    OverwriteSubstitutionModalComponent,
    EditSubstitutionComponent,
    ImportMappingsComponent,
    StatusRendererComponent,
    QOSRendererComponent,
    TemplateRendererComponent,
    SnoopedTemplateRendererComponent,
    SubstitutionRendererComponent,
    StatusActivationRendererComponent,
    APIRendererComponent,
    NameRendererComponent,
    ActiveRendererComponent,
    SnoopingModalComponent,
    MappingTypeComponent,
    MessageField,
    FormlyHorizontalWrapper,
    WrapperCustomFormField,
    C8YSwitchField,
    SelectComponent,
    FieldCheckbox,
    FieldInputCustom,
  ],
  imports: [
    CoreModule,
    AssetSelectorModule,
    PopoverModule,
    DynamicFormsModule,
    ModalModule,
    SharedModule,
    BrokerConfigurationModule,
  ],
  exports: [],
  providers: [
    hookRoute({
      path: "sag-ps-pkg-dynamic-mapping/mappings/inbound",
      component: MappingComponent,
    }),
    hookRoute({
      path: "sag-ps-pkg-dynamic-mapping/mappings/outbound",
      component: MappingComponent,
    }),
    {
      provide: FORMLY_CONFIG,
      multi: true,
      useValue: {
        validators: [
          {
            name: "checkTopicsInboundAreValid",
            validation: checkTopicsInboundAreValid,
          },
          {
            name: "checkTopicsOutboundAreValid",
            validation: checkTopicsOutboundAreValid,
          },
        ],
        types: [
          { name: "text", component: FormlyTextField },
          { name: "filler", component: FormlyFiller },
          { name: "textarea-custom", component: FieldTextareaCustom },
          { name: "input-custom", component: FieldInputCustom },
          { name: "button", component: FormlyFieldButton },
          { name: "message-field", component: MessageField },
          { name: "c8y-switch", component: C8YSwitchField },
          {
            name: "select",
            component: SelectComponent,
            wrappers: ["c8y-form-field"],
          },
          { name: "enum", extends: "select" },
          { name: "checkbox", component: FieldCheckbox },
          { name: "boolean", extends: "checkbox" },
        ],
        wrappers: [
          { name: "form-field-horizontal", component: FormlyHorizontalWrapper },
          { name: "custom-form-field", component: WrapperCustomFormField },
        ],
      },
    },
  ],
})
export class MappingModule {}
