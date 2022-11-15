export interface ApplicationState {
  label: string;
  class: string;
}

/** Wizard types  */
export enum Wizards {
  APPLICATION_UPLOAD = 'applicationUpload',
  MICROSERVICE_UPLOAD = 'microserviceUpload'
}

export enum ERROR_TYPE {
  TYPE_VALIDATION = 'TYPE_VALIDATION',
  ALREADY_SUBSCRIBED = 'ALREADY_SUBSCRIBED',
  INTERNAL_ERROR = 'INTERNAL_ERROR',
  NO_MANIFEST_FILE = 'NO_MANIFEST_FILE',
  INVALID_PACKAGE = 'INVALID_PACKAGE',
  INVALID_APPLICATION = 'INVALID_APPLICATION'
}

export interface ApplicationPlugin {
  id: string;
  name?: string;
  module: string;
  path: string;
  description?: string;
  version?: string;
  scope?: string;
  installed?: boolean;
  contextPath?: string;
}
