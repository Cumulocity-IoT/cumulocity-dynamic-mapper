import { BuiltInActionType } from "@c8y/ngx-components";

export interface ActionVisibilityRule {
    type: 'enabled' | 'readOnly' | 'connectorType';
    value?: boolean;
  }
  
  export type ActionVisibilityRules = ActionVisibilityRule[];
  
  export interface ActionControlConfig {
    type: string | BuiltInActionType;
    icon?: string;
    text?: string;
    callbackName: 'onConfigurationUpdate' | 'onConfigurationCopy' | 'onConfigurationDelete';
    visibilityRules: ActionVisibilityRule[];
  }